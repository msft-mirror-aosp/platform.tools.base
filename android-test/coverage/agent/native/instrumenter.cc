/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#include "tools/base/android-test/coverage/agent/native/instrumenter.h"
#include <sys/types.h>
#include <unistd.h>
#include <algorithm>
#include <atomic>
#include <cstring>
#include <limits>
#include <map>
#include <memory>
#include <string_view>
#include <unordered_map>
#include <vector>
#include "slicer/code_ir.h"
#include "slicer/control_flow_graph.h"
#include "slicer/dex_bytecode.h"
#include "slicer/dex_format.h"
#include "slicer/dex_ir.h"
#include "slicer/dex_ir_builder.h"
#include "slicer/instrumentation.h"
#include "slicer/reader.h"
#include "slicer/writer.h"
#include "tools/base/android-test/coverage/agent/native/constructor_analyzer.h"
#include "tools/base/android-test/coverage/agent/native/metadata_collector.h"
#include "tools/base/android-test/coverage/agent/native/parameter_shifter.h"
#include "tools/base/android-test/coverage/agent/native/register_scanner.h"
#include "tools/base/android-test/coverage/common/log.h"

namespace coverage {

bool Instrumenter::IsSyntheticOrCompilerGenerated(std::string_view class_name) {
  return class_name.find("$$") != std::string_view::npos ||
         class_name.find("$Lambda$") != std::string_view::npos ||
         class_name.find("$sam$") != std::string_view::npos ||
         class_name.find("$inlined$") != std::string_view::npos;
}

namespace {

// Thread-safe unique counter for basic blocks across all instrumented classes.
std::atomic<uint32_t> g_next_block_id{0};

// An allocator for Slicer that uses JVMTI's allocation mechanism.
// This is necessary because ART will deallocate the redefined class bytes
// using JVMTI's Deallocate.
class JvmtiAllocator : public dex::Writer::Allocator {
 public:
  explicit JvmtiAllocator(jvmtiEnv* jvmti) : jvmti_(jvmti) {}

  void* Allocate(size_t size) override {
    unsigned char* alloc = nullptr;
    jvmtiError error = jvmti_->Allocate(size, &alloc);
    if (error != JVMTI_ERROR_NONE) {
      Log::E("Error: JVMTI Allocate failed. Error code: %d", error);
      return nullptr;
    }
    return reinterpret_cast<void*>(alloc);
  }

  void Free(void* ptr) override {
    if (ptr == nullptr) {
      return;
    }
    jvmti_->Deallocate(reinterpret_cast<unsigned char*>(ptr));
  }

 private:
  jvmtiEnv* jvmti_;
};

}  // namespace

Instrumenter* Instrumenter::instance_ = nullptr;

Instrumenter::Instrumenter(jvmtiEnv* jvmti, const std::string& inclusion_prefix)
    : jvmti_(jvmti), inclusion_prefix_(inclusion_prefix) {
  instance_ = this;
  Log::I("Coverage agent instrumenter initialized for prefix: %s",
         inclusion_prefix.c_str());
}

Instrumenter::~Instrumenter() {
  if (instance_ == this) {
    instance_ = nullptr;
  }
}

/**
 * JVMTI callback for the ClassFileLoadHook event.
 */
void JNICALL Instrumenter::OnClassFileLoadHook(
    jvmtiEnv* jvmti, JNIEnv* jni, jclass class_being_redefined, jobject loader,
    const char* name, jobject protection_domain, jint class_data_len,
    const unsigned char* class_data, jint* new_class_data_len,
    unsigned char** new_class_data) {
  if (jvmti == nullptr || name == nullptr || class_data == nullptr ||
      class_data_len <= 0 || new_class_data_len == nullptr ||
      new_class_data == nullptr) {
    return;
  }

  if (instance_ == nullptr ||
      !instance_->ShouldInstrument(loader, name, class_being_redefined)) {
    return;
  }

  Log::I("Instrumenting class: %s (loader: %p)", name, loader);

  // The class name from JVMTI needs to be converted to a JNI descriptor.
  std::string descriptor = "L" + std::string(name) + ";";

  dex::Reader reader(class_data, class_data_len);
  auto class_index = reader.FindClassIndex(descriptor.c_str());
  if (class_index == dex::kNoIndex) {
    Log::W("Could not find class index for %s. Skipping instrumentation.",
           descriptor.c_str());
    return;
  }

  // Create an IR for only the specific class that ART is loading. This
  // effectively "prunes" the DEX so that the resulting byte array only contains
  // one class definition, which is a requirement for JVMTI class
  // redefinition/retransformation in ART.
  reader.CreateClassIr(class_index);
  auto dex_ir = reader.GetIr();
  if (dex_ir == nullptr) {
    Log::E("Slicer failed to generate IR for %s", descriptor.c_str());
    return;
  }

  // Pre-build the declaration for CoverageTracker.hit(int) once
  // per class to avoid redundant lookups for every method.
  ir::Builder builder(dex_ir);
  auto ir_proto = builder.GetProto(builder.GetType("V"),
                                   builder.GetTypeList({builder.GetType("I")}));

  auto hit_method_decl = builder.GetMethodDecl(
      builder.GetAsciiString("hit"), ir_proto,
      builder.GetType("Lcom/android/tools/coverage/CoverageTracker;"));

  // Extract class-level metadata (SourceFile and SMAP)
  std::string source_file = "";
  std::string smap = "";
  if (!dex_ir->classes.empty()) {
    auto& ir_class = dex_ir->classes.front();
    if (ir_class->source_file != nullptr) {
      source_file = ir_class->source_file->c_str();
    }

    if (ir_class->annotations != nullptr &&
        ir_class->annotations->class_annotation != nullptr) {
      for (auto* anno : ir_class->annotations->class_annotation->annotations) {
        if (anno->type != nullptr && anno->type->descriptor != nullptr &&
            strcmp(anno->type->descriptor->c_str(),
                   "Ldalvik/annotation/SourceDebugExtension;") == 0) {
          for (auto* elem : anno->elements) {
            if (elem->name != nullptr &&
                strcmp(elem->name->c_str(), "value") == 0) {
              if (elem->value->type == dex::kEncodedString) {
                smap = elem->value->u.string_value->c_str();
              }
            }
          }
        }
      }
    }
  }

  auto* class_meta =
      MetadataCollector::Instance().AddClass(descriptor, source_file, smap);

  bool modified = false;
  for (auto& ir_class : dex_ir->classes) {
    for (auto& method : ir_class->virtual_methods) {
      if (instance_->InstrumentMethod(method, hit_method_decl, dex_ir,
                                      class_meta)) {
        modified = true;
      }
    }
    for (auto& method : ir_class->direct_methods) {
      if (instance_->InstrumentMethod(method, hit_method_decl, dex_ir,
                                      class_meta)) {
        modified = true;
      }
    }
  }

  if (!modified) {
    return;
  }

  dex::Writer writer(dex_ir);
  JvmtiAllocator allocator(jvmti);
  size_t new_image_size = 0;
  dex::u1* new_image = writer.CreateImage(&allocator, &new_image_size);

  if (new_image != nullptr) {
    if (new_image_size > std::numeric_limits<jint>::max()) {
      Log::E("Error: Generated DEX image size exceeds jint max for %s", name);
      jvmti->Deallocate(reinterpret_cast<unsigned char*>(new_image));
      return;
    }

    *new_class_data_len = static_cast<jint>(new_image_size);
    *new_class_data = reinterpret_cast<unsigned char*>(new_image);

    // After successful instrumentation, tag the class (if it exists)
    // to avoid future redundant instrumentation.
    // TODO: Handle tagging for classes instrumented during their initial load.
    if (class_being_redefined != nullptr) {
      jvmti->SetTag(class_being_redefined, kInstrumentedTag);
    }
  } else {
    Log::E("Slicer failed to produce new DEX image for %s", name);
  }
}

bool Instrumenter::InstrumentMethod(
    ir::EncodedMethod* ir_method, ir::MethodDecl* hit_method_decl,
    const std::shared_ptr<ir::DexFile>& dex_ir,
    android::tools::coverage::proto::ClassMetadata* class_meta) const {
  if (ir_method->code == nullptr) {
    return false;  // Abstract or native method
  }

  std::string_view name(ir_method->decl->name->c_str());
  const bool is_constructor = (name == "<init>");

  // Skip synthetic compiler-generated methods unless they contain user lambdas
  // or Compose logic.
  if (ir_method->access_flags & dex::kAccSynthetic) {
    // We allow synthetic methods if they are likely user-authored lambda bodies
    // or Compose helpers.
    if (name.find("invoke") == std::string_view::npos &&
        name.find("lambda") == std::string_view::npos &&
        name.find("Composable") == std::string_view::npos) {
      return false;
    }
  }

  lir::CodeIr code_ir(ir_method, dex_ir);

  lir::Instruction* super_call_instr = nullptr;
  if (is_constructor) {
    super_call_instr =
        ConstructorAnalyzer::FindSuperCallInstruction(code_ir, name);
    if (super_call_instr == nullptr) {
      Log::I("Constructor %s has complex/missing super delegation. Skipping.",
             ir_method->decl->name->c_str());
      return false;
    }
  }

  // Track the original first bytecode instruction before register allocation to
  // align probes safely after Slicer's prologue.
  lir::Instruction* orig_first_instr = nullptr;
  if (is_constructor) {
    orig_first_instr = super_call_instr->next;
  } else {
    for (auto* instr : code_ir.instructions) {
      if (dynamic_cast<lir::Bytecode*>(instr) != nullptr) {
        orig_first_instr = instr;
        break;
      }
    }
  }

  // 1. Resolve a scratch register to hold our block IDs.
  // We bypass Slicer's AllocateScratchRegs to avoid unstable global register
  // renumbering, which is highly prone to generating out-of-bounds register
  // VerifyErrors in large, complex classes (such as Jetpack Compose screens).
  // Instead, we use a hybrid allocator:
  //
  // Fast Path: Scan the method's existing bytecode instructions to check if
  // there is an untouched/unused register index < 16. If so, we use it directly
  // as our scratch register, requiring zero frame modifications and zero
  // parameter-shifting.
  dex::u4 scratch_reg = 0;
  bool found_unused = false;

  scratch_reg = RegisterScanner::FindUnusedScratchRegister(ir_method, code_ir,
                                                           found_unused);

  // Slow Path: If no unused registers exist, we manually expand the method's
  // register frame by exactly 1 slot, allocating the absolute highest register
  // index as our scratch register.
  if (!found_unused) {
    scratch_reg = ir_method->code->registers;

    // Dalvik const-loading instructions use an 8-bit register field (vAA).
    // Therefore, we cannot encode constants or block IDs into scratch registers
    // >= 256. If a method is so massive that it exceeds 255 registers, we must
    // safely skip it to prevent assembler crashes.
    if (scratch_reg > 255) {
      Log::W("Method %s has too many registers (%d). Skipping.",
             ir_method->decl->name->c_str(), scratch_reg);
      return false;
    }

    // Expand the registers count by 1 to allocate our new scratch register.
    ir_method->code->registers += 1;
    const dex::u4 ins_count = ir_method->code->ins_count;

    // Because Dalvik requires parameters (arguments) to always reside at the
    // absolute end of the method's register frame, expanding the registers
    // count by 1 shifts all incoming parameters upward in memory by exactly 1
    // slot. We delegate the parameter-shifting relocation (prologue moves) to
    // our modular ParameterShifter class to keep code clean, readable, and
    // highly maintainable.
    if (!ParameterShifter::ShiftParameters(ir_method, code_ir,
                                           orig_first_instr)) {
      return false;
    }

    // If we are inside a constructor and we expanded the register frame, any
    // instructions executing before our copy-back moves (such as the super()
    // delegation call itself) must be adjusted to reference the shifted
    // parameter indices. Stop adjusting after super_call_instr.
    if (is_constructor && ins_count > 0) {
      dex::u4 old_param_base = ir_method->code->registers - 1 - ins_count;
      for (auto* instr : code_ir.instructions) {
        if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
          for (auto* operand : bytecode->operands) {
            if (auto* vreg = dynamic_cast<lir::VReg*>(operand)) {
              if (vreg->reg >= old_param_base) {
                vreg->reg += 1;
              }
            } else if (auto* vpair = dynamic_cast<lir::VRegPair*>(operand)) {
              if (vpair->base_reg >= old_param_base) {
                vpair->base_reg += 1;
              }
            } else if (auto* vlist = dynamic_cast<lir::VRegList*>(operand)) {
              for (size_t i = 0; i < vlist->registers.size(); ++i) {
                if (vlist->registers[i] >= old_param_base) {
                  vlist->registers[i] += 1;
                }
              }
            } else if (auto* vrange = dynamic_cast<lir::VRegRange*>(operand)) {
              if (vrange->base_reg >= old_param_base) {
                vrange->base_reg += 1;
              }
            }
          }
        }
        if (instr == super_call_instr) {
          break;
        }
      }
    }
  }

  // 2. Track line numbers to map instructions to source lines.
  std::unordered_map<lir::Instruction*, int32_t> instr_to_line;
  int32_t current_line = -1;
  if (ir_method->code->debug_info != nullptr) {
    current_line =
        static_cast<int32_t>(ir_method->code->debug_info->line_start);
  }

  for (auto* instr : code_ir.instructions) {
    if (auto* dbg_annot = dynamic_cast<lir::DbgInfoAnnotation*>(instr)) {
      if (dbg_annot->dbg_opcode == dex::DBG_ADVANCE_LINE &&
          !dbg_annot->operands.empty()) {
        if (auto* line_op =
                dynamic_cast<lir::LineNumber*>(dbg_annot->operands[0])) {
          current_line = static_cast<int32_t>(line_op->line);
        }
      }
    }
    instr_to_line[instr] = current_line;
  }

  lir::ControlFlowGraph cfg(&code_ir, false);

  // Use a local structure to hold block metadata until we are certain
  // instrumentation succeeded.
  struct PendingBlock {
    uint32_t id;
    std::vector<std::pair<int32_t, uint32_t>> line_counts;
    uint32_t branch_count;
    std::vector<uint32_t> successor_block_ids;
    const lir::BasicBlock* original_block;
  };
  std::vector<PendingBlock> pending_blocks;
  std::unordered_map<lir::Instruction*, uint32_t> instr_to_block_id;

  bool injected = false;
  for (const auto& block : cfg.basic_blocks) {
    lir::Instruction* trace_point = nullptr;

    if (block.region.first == nullptr) continue;

    // Track metadata for this block: map line_number -> instruction_count
    std::map<int32_t, uint32_t> line_instruction_counts;

    // 1. Gather line numbers for this block
    for (auto* instr = block.region.first; instr != nullptr;
         instr = (instr == block.region.last) ? nullptr : instr->next) {
      if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
        dex::Opcode op = bytecode->opcode;
        if (op == dex::OP_NOP ||
            (op >= dex::OP_MOVE && op <= dex::OP_MOVE_OBJECT_16) ||
            op == dex::OP_CHECK_CAST) {
          continue;
        }

        auto it = instr_to_line.find(instr);
        int32_t line = (it != instr_to_line.end()) ? it->second : -1;
        if (line != -1) {
          line_instruction_counts[line]++;
        }
      }
    }

    // 2. Normal trace point finding for standard methods
    for (auto* instr = block.region.first; instr != nullptr;
         instr = (instr == block.region.last) ? nullptr : instr->next) {
      if (auto* bytecode = dynamic_cast<lir::Bytecode*>(instr)) {
        dex::Opcode op = bytecode->opcode;
        if (op != dex::OP_NOP &&
            !(op >= dex::OP_MOVE && op <= dex::OP_MOVE_OBJECT_16) &&
            op != dex::OP_CHECK_CAST) {
          trace_point = instr;
          break;
        }
      }
    }

    if (trace_point == nullptr) continue;

    // Safety check: For standard methods, ensure we never inject any coverage
    // probes before Slicer's parameter copy-back prologue. If the trace_point
    // is topologically before our orig_first_instr (i.e. it is one of Slicer's
    // prologue moves), we safely shift the injection target down to
    // orig_first_instr.
    if (orig_first_instr != nullptr) {
      bool is_before_orig = false;
      for (auto* instr : code_ir.instructions) {
        if (instr == orig_first_instr) {
          break;
        }
        if (instr == trace_point) {
          is_before_orig = true;
          break;
        }
      }
      if (is_before_orig) {
        trace_point = orig_first_instr;
      }
    }

    if (trace_point == nullptr) continue;

    // Safety check for constructors: we must NOT inject any coverage probes
    // topologically before or at the super()/this() delegation call. In Dalvik
    // bytecode, super() is frequently NOT the first instruction. During this,
    // the 'this' instance is officially "uninitialized" by the ART Verifier.
    // Performing any virtual/static method calls (like our tracker probes)
    // within this region will trigger a runtime VerifyError. Thus, any blocks
    // occurring at or before the super_call_instr are safely skipped.
    if (is_constructor) {
      bool is_before_or_at_super = false;
      for (auto* instr : code_ir.instructions) {
        if (instr == trace_point) {
          is_before_or_at_super = true;
          break;
        }
        if (instr == super_call_instr) {
          break;
        }
      }
      if (is_before_or_at_super) {
        continue;
      }
    }

    // Dalvik requires that OP_MOVE_RESULT_*, OP_MOVE_EXCEPTION, and Slicer's
    // copy-back move instructions immediately follow the instructions that
    // produced them. We cannot safely inject code between them, so we advance
    // the trace point.
    while (auto trace_bytecode = dynamic_cast<lir::Bytecode*>(trace_point)) {
      auto opcode = trace_bytecode->opcode;
      if (opcode == dex::OP_MOVE_16 || opcode == dex::OP_MOVE_OBJECT_16 ||
          opcode == dex::OP_MOVE_WIDE_16 || opcode == dex::OP_MOVE_RESULT ||
          opcode == dex::OP_MOVE_RESULT_WIDE ||
          opcode == dex::OP_MOVE_RESULT_OBJECT ||
          opcode == dex::OP_MOVE_EXCEPTION) {
        trace_point = trace_point->next;
      } else {
        break;
      }
    }

    if (trace_point == nullptr) continue;

    // Determine the branch count for this block using the opcode flags of the
    // last instruction in the block region.
    uint32_t branch_count = 1;  // Default for sequential flow.
    if (auto* last_bytecode =
            dynamic_cast<lir::Bytecode*>(block.region.last)) {
      auto flags = dex::GetFlagsFromOpcode(last_bytecode->opcode);
      if (flags & dex::kBranch) {
        if (flags & dex::kContinue) {
          branch_count = 2;  // Conditional branch (e.g., IF_*)
        } else {
          branch_count = 1;  // Unconditional branch (e.g., GOTO)
        }
      } else if (flags & dex::kSwitch) {
        branch_count = 2;  // Safe fallback
        if (last_bytecode->operands.size() >= 2) {
          if (auto* code_loc = dynamic_cast<lir::CodeLocation*>(last_bytecode->operands[1])) {
            if (auto* label = code_loc->label) {
              if (auto* payload = label->next) {
                if (auto* packed_payload = dynamic_cast<lir::PackedSwitchPayload*>(payload)) {
                  branch_count = packed_payload->targets.size() + 1;
                } else if (auto* sparse_payload = dynamic_cast<lir::SparseSwitchPayload*>(payload)) {
                  branch_count = sparse_payload->switch_cases.size() + 1;
                }
              }
            }
          }
        }
      } else if (flags & (dex::kReturn | dex::kThrow)) {
        branch_count = 0;  // Terminal block (no successors)
      }
    }

    // Assign a globally unique block ID only for blocks we are actually
    // instrumenting.
    uint32_t block_id = g_next_block_id.fetch_add(1);

    // Inject: const vX, <block_id>
    auto load_id = code_ir.Alloc<lir::Bytecode>();
    load_id->opcode = dex::OP_CONST;
    load_id->operands.push_back(code_ir.Alloc<lir::VReg>(scratch_reg));
    load_id->operands.push_back(
        code_ir.Alloc<lir::Const32>(static_cast<dex::u4>(block_id)));

    // Inject: invoke-static/range {vX}, CoverageTracker.hit(I)V
    auto call_mark = code_ir.Alloc<lir::Bytecode>();
    call_mark->opcode = dex::OP_INVOKE_STATIC_RANGE;
    call_mark->operands.push_back(
        code_ir.Alloc<lir::VRegRange>(scratch_reg, 1));
    call_mark->operands.push_back(code_ir.Alloc<lir::Method>(
        hit_method_decl, hit_method_decl->orig_index));

    code_ir.instructions.InsertBefore(trace_point, load_id);
    code_ir.instructions.InsertBefore(trace_point, call_mark);
    injected = true;

    // Record the mapping locally.
    PendingBlock pending;
    pending.id = block_id;
    for (const auto& entry : line_instruction_counts) {
      pending.line_counts.push_back(entry);
    }
    pending.branch_count = branch_count;
    pending.original_block = &block;
    pending_blocks.push_back(std::move(pending));

    // Map each instruction in this block to its assigned block_id for Pass 2 resolution
    for (auto* instr = block.region.first; instr != nullptr;
         instr = (instr == block.region.last) ? nullptr : instr->next) {
      instr_to_block_id[instr] = block_id;
    }
  }

  if (!injected) {
    return false;
  }

  // Pass 2: Resolve successor block IDs for each pending block in the control flow graph
  for (auto& pb : pending_blocks) {
    const auto& block = *pb.original_block;
    std::vector<uint32_t> successor_ids;

    if (auto* last_bytecode = dynamic_cast<lir::Bytecode*>(block.region.last)) {
      auto flags = dex::GetFlagsFromOpcode(last_bytecode->opcode);
      if (flags & dex::kBranch) {
        // Unconditional or conditional branches/jumps
        for (auto* operand : last_bytecode->operands) {
          if (auto* code_loc = dynamic_cast<lir::CodeLocation*>(operand)) {
            if (auto* label = code_loc->label) {
              auto it = instr_to_block_id.find(label);
              if (it != instr_to_block_id.end()) {
                successor_ids.push_back(it->second);
              }
            }
          }
        }
        // Conditional branch also continues to next block sequentially
        if (flags & dex::kContinue) {
          if (auto* next_instr = last_bytecode->next) {
            auto it = instr_to_block_id.find(next_instr);
            if (it != instr_to_block_id.end()) {
              successor_ids.push_back(it->second);
            }
          }
        }
      } else if (flags & dex::kSwitch) {
        // Switch targets from switch table payload
        if (last_bytecode->operands.size() >= 2) {
          if (auto* code_loc = dynamic_cast<lir::CodeLocation*>(last_bytecode->operands[1])) {
            if (auto* label = code_loc->label) {
              if (auto* payload = label->next) {
                if (auto* packed_payload = dynamic_cast<lir::PackedSwitchPayload*>(payload)) {
                  for (auto* target : packed_payload->targets) {
                    auto it = instr_to_block_id.find(target);
                    if (it != instr_to_block_id.end()) {
                      successor_ids.push_back(it->second);
                    }
                  }
                } else if (auto* sparse_payload = dynamic_cast<lir::SparseSwitchPayload*>(payload)) {
                  for (const auto& switch_case : sparse_payload->switch_cases) {
                    auto it = instr_to_block_id.find(switch_case.target);
                    if (it != instr_to_block_id.end()) {
                      successor_ids.push_back(it->second);
                    }
                  }
                }
              }
            }
          }
        }
        // Switch also continues on default fallback
        if (auto* next_instr = last_bytecode->next) {
          auto it = instr_to_block_id.find(next_instr);
          if (it != instr_to_block_id.end()) {
            successor_ids.push_back(it->second);
          }
        }
      } else if (!(flags & (dex::kReturn | dex::kThrow))) {
        // Standard sequential flow
        if (auto* next_instr = last_bytecode->next) {
          auto it = instr_to_block_id.find(next_instr);
          if (it != instr_to_block_id.end()) {
            successor_ids.push_back(it->second);
          }
        }
      }
    } else {
      // Non-bytecode sequential flow
      if (auto* next_instr = block.region.last->next) {
        auto it = instr_to_block_id.find(next_instr);
        if (it != instr_to_block_id.end()) {
          successor_ids.push_back(it->second);
        }
      }
    }

    // Deduplicate successor block IDs
    std::sort(successor_ids.begin(), successor_ids.end());
    successor_ids.erase(std::unique(successor_ids.begin(), successor_ids.end()), successor_ids.end());
    pb.successor_block_ids = std::move(successor_ids);
  }

  // Instrumentation succeeded. Commit metadata to the collector.
  auto* method_meta = MetadataCollector::Instance().AddMethod(
      class_meta, ir_method->decl->name->c_str(),
      ir_method->decl->prototype->Signature().c_str());

  for (const auto& pb : pending_blocks) {
    MetadataCollector::Instance().AddBlock(method_meta, pb.id, pb.line_counts,
                                           pb.branch_count, pb.successor_block_ids);
  }

  code_ir.Assemble();
  return true;
}

bool Instrumenter::ShouldInstrument(jobject loader, const char* name,
                                    jclass klass) const {
  if (name == nullptr) {
    return false;
  }

  // Check if the class is already instrumented.
  if (klass != nullptr) {
    jlong tag = 0;
    jvmti_->GetTag(klass, &tag);
    if (tag == kInstrumentedTag) {
      return false;
    }
  }

  // Don't instrument classes loaded by the bootstrap class loader (eg. OS
  // classes).
  if (loader == nullptr) {
    return false;
  }

  std::string_view class_name(name);

  // Skip synthetic compiler-generated and nested classes.
  if (IsSyntheticOrCompilerGenerated(class_name)) {
    return false;
  }

  // Explicitly skip our own runtime tracker to avoid infinite recursion.
  if (class_name == "com/android/tools/coverage/CoverageTracker") {
    return false;
  }

  if (inclusion_prefix_.empty()) {
    return false;
  }

  bool prefix_match = false;
  std::string_view inc_prefix(inclusion_prefix_);
  if (class_name.size() >= inc_prefix.size() &&
      class_name.rfind(inc_prefix, 0) == 0) {
    if (class_name.size() > inc_prefix.size() && inc_prefix.back() != '/' &&
        inc_prefix.back() != '$') {
      char next_char = class_name[inc_prefix.size()];
      if (next_char != '/' && next_char != '$') {
        return false;
      }
    }
    prefix_match = true;
  }

  return prefix_match;
}

void Instrumenter::RetransformLoadedClasses(JNIEnv* jni) {
  if (inclusion_prefix_.empty()) {
    return;
  }

  jint class_count = 0;
  jclass* classes = nullptr;
  jvmtiError error = jvmti_->GetLoadedClasses(&class_count, &classes);
  if (error != JVMTI_ERROR_NONE) {
    Log::E("Error: GetLoadedClasses failed. Error code: %d", error);
    return;
  }

  std::vector<jclass> classes_to_retransform;
  for (jint i = 0; i < class_count; ++i) {
    jclass klass = classes[i];
    char* signature = nullptr;
    error = jvmti_->GetClassSignature(klass, &signature, nullptr);
    if (error == JVMTI_ERROR_NONE && signature != nullptr) {
      std::string_view class_name(signature);
      if (class_name.front() == 'L' && class_name.back() == ';') {
        class_name = class_name.substr(1, class_name.size() - 2);
      }
      jobject loader = nullptr;
      jvmti_->GetClassLoader(klass, &loader);
      if (ShouldInstrument(loader, std::string(class_name).c_str(), klass)) {
        jboolean modifiable = JNI_FALSE;
        jvmti_->IsModifiableClass(klass, &modifiable);
        if (modifiable) {
          // Use GlobalRef to avoid exceeding JNI local reference limits
          // (typically 512).
          classes_to_retransform.push_back(
              reinterpret_cast<jclass>(jni->NewGlobalRef(klass)));
        }
      }
      if (loader != nullptr) {
        jni->DeleteLocalRef(loader);
      }
      jvmti_->Deallocate(reinterpret_cast<unsigned char*>(signature));
    }
    jni->DeleteLocalRef(klass);
  }

  if (!classes_to_retransform.empty()) {
    Log::I("Retransforming %zu loaded classes...",
           classes_to_retransform.size());
    for (jclass klass : classes_to_retransform) {
      jvmtiError err = jvmti_->RetransformClasses(1, &klass);
      if (err != JVMTI_ERROR_NONE) {
        // Safe to log warning and proceed to the next class
        Log::W("Retransformation failed for class (error: %d). Skipping.", err);
      }
      // Release JNI global reference for the class after retransformation
      jni->DeleteGlobalRef(klass);
    }
  }

  jvmti_->Deallocate(reinterpret_cast<unsigned char*>(classes));
}

}  // namespace coverage
