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

#include "perfetto_cropper.h"

#include <gtest/gtest.h>

#include <algorithm>
#include <cctype>
#include <fstream>
#include <limits>
#include <map>
#include <memory>
#include <set>
#include <sstream>
#include <string>
#include <vector>

#include "perfetto/trace_processor/trace_processor.h"

namespace sherlock {

namespace {

// SQL query mirroring all_render_stages.sql to extract all render stage slices.
// See:
// tools/profiler/sherlock-plugin/src/main/resources/sql/frame/benchmark/all_render_stages.sql
constexpr char kAllRenderStagesSql[] = R"sql(
SELECT
  0 AS frame_number,
  COALESCE(extract_arg(s.arg_set_id, 'submission_id'), 0) AS submission_id,
  s.id,
  s.name,
  s.ts,
  s.dur,
  COALESCE(extract_arg(s.arg_set_id, 'render_pass'), 0) AS render_pass,
  COALESCE(extract_arg(s.arg_set_id, 'render_target'), 0) AS render_target,
  COALESCE(extract_arg(s.arg_set_id, 'render_pass_name'), '') AS render_pass_name
FROM slice AS s
JOIN track AS t ON s.track_id = t.id
WHERE t.type = 'gpu_render_stage'
ORDER BY s.ts;
)sql";

constexpr char kProcessTableCountSql[] = R"sql(
SELECT COUNT(*) FROM process WHERE name IS NOT NULL;
)sql";

constexpr char kRenderPassDebugNameCountSql[] = R"sql(
SELECT COUNT(*)
FROM slice
WHERE extract_arg(arg_set_id, 'render_pass_name') IS NOT NULL
  AND extract_arg(arg_set_id, 'render_pass_name') != '';
)sql";

constexpr char kCounterEventsCountSql[] = R"sql(
SELECT COUNT(*) FROM counter;
)sql";

struct RenderStagePart {
  int64_t id;
  std::string name;
  int64_t start_ns;
  int64_t duration_ns;
};

enum class RenderStageType {
  UNKNOWN,
  RENDER_PASS,
  COMPUTE,
  COPY,
};

struct RenderStage {
  int64_t submission_id;
  RenderStageType type;
  std::vector<RenderStagePart> parts;
  std::string debug_name;
};

enum class PartCategory {
  RENDER_PASS_PART_VERTEX,
  RENDER_PASS_PART_FRAGMENT,
  COMPUTE,
  COPY,
  RENDER_PASS,
  RENDER_PASS_DETAIL,
  RENDER_STAGE_UNKNOWN,
  IGNORE,
};

static PartCategory GetPartCategory(const std::string& name) {
  std::string lower = name;
  std::transform(lower.begin(), lower.end(), lower.begin(),
                 [](unsigned char c) { return std::tolower(c); });
  size_t first = lower.find_first_not_of(" \t");
  if (first != std::string::npos) {
    size_t last = lower.find_last_not_of(" \t");
    lower = lower.substr(first, (last - first + 1));
  }

  // Arm & PowerVR
  if (lower == "vertex") return PartCategory::RENDER_PASS_PART_VERTEX;
  if (lower == "fragment") return PartCategory::RENDER_PASS_PART_FRAGMENT;
  if (lower == "compute" || lower == "dispatch") return PartCategory::COMPUTE;
  if (lower == "copy image" || lower == "blit image" || lower == "2d")
    return PartCategory::COPY;
  // Qualcomm
  if (lower == "surface") return PartCategory::RENDER_PASS;
  if (lower == "render" || lower == "binning" || lower == "gmem store color" ||
      lower == "gmem store depth/stencil")
    return PartCategory::RENDER_PASS_DETAIL;
  if (lower == "blit") return PartCategory::COPY;
  // Exynos
  if (lower == "renderpass") return PartCategory::RENDER_PASS;
  if (lower == "subpass" || lower == "draw" || lower == "transfer")
    return PartCategory::RENDER_PASS_DETAIL;
  if (lower == "ib" || lower == "cmdbuffer") return PartCategory::IGNORE;
  return PartCategory::RENDER_STAGE_UNKNOWN;
}

// C++ test helper mirroring RenderStagesUtils.kt 1:1
class RenderStagesUtils {
 public:
  static std::vector<RenderStage> GetAllRenderStages(
      perfetto::trace_processor::TraceProcessor* tp) {
    auto it = tp->ExecuteQuery(kAllRenderStagesSql);
    struct DraftRenderStage {
      RenderStageType type;
      std::vector<RenderStagePart> parts;
      int64_t submission_id;
      std::string debug_name;
    };

    std::vector<DraftRenderStage> draft_stages;
    std::vector<size_t> pending_vertex_indices;

    while (it.Next()) {
      if (it.Get(1).is_null()) continue;
      std::string name = it.Get(3).string_value ? it.Get(3).string_value : "";
      PartCategory category = GetPartCategory(name);
      if (category == PartCategory::IGNORE) continue;

      RenderStagePart part{
          .id = it.Get(2).long_value,
          .name = name,
          .start_ns = it.Get(4).long_value,
          .duration_ns = it.Get(5).long_value,
      };
      int64_t sub_id = it.Get(1).long_value;
      std::string render_pass_name =
          it.Get(8).string_value ? it.Get(8).string_value : "";

      size_t target_idx = std::string::npos;
      if (category == PartCategory::RENDER_PASS_PART_FRAGMENT) {
        if (!pending_vertex_indices.empty()) {
          target_idx = pending_vertex_indices.front();
          pending_vertex_indices.erase(pending_vertex_indices.begin());
        }
      }

      if (target_idx != std::string::npos) {
        draft_stages[target_idx].parts.push_back(part);
      } else {
        RenderStageType type = RenderStageType::UNKNOWN;
        switch (category) {
          case PartCategory::COMPUTE:
            type = RenderStageType::COMPUTE;
            break;
          case PartCategory::RENDER_PASS_PART_VERTEX:
          case PartCategory::RENDER_PASS_PART_FRAGMENT:
          case PartCategory::RENDER_PASS:
          case PartCategory::RENDER_PASS_DETAIL:
            type = RenderStageType::RENDER_PASS;
            break;
          case PartCategory::COPY:
            type = RenderStageType::COPY;
            break;
          default:
            type = RenderStageType::UNKNOWN;
            break;
        }
        draft_stages.push_back(DraftRenderStage{
            .type = type,
            .parts = {part},
            .submission_id = sub_id,
            .debug_name = render_pass_name,
        });
        if (category == PartCategory::RENDER_PASS_PART_VERTEX) {
          pending_vertex_indices.push_back(draft_stages.size() - 1);
        }
      }
    }

    // addRenderPassChildrenParts
    std::vector<DraftRenderStage> remaining_stages;
    std::vector<DraftRenderStage> to_merge;
    for (auto& stage : draft_stages) {
      bool is_detail_only = true;
      for (const auto& part : stage.parts) {
        PartCategory cat = GetPartCategory(part.name);
        if (cat != PartCategory::RENDER_PASS_DETAIL &&
            cat != PartCategory::RENDER_STAGE_UNKNOWN) {
          is_detail_only = false;
          break;
        }
      }
      if (is_detail_only) {
        to_merge.push_back(std::move(stage));
      } else {
        remaining_stages.push_back(std::move(stage));
      }
    }

    for (const auto& stage : to_merge) {
      for (const auto& part : stage.parts) {
        for (auto& target : remaining_stages) {
          if (target.type == RenderStageType::RENDER_PASS &&
              target.submission_id == stage.submission_id) {
            bool matches = false;
            for (const auto& p : target.parts) {
              if (p.start_ns <= part.start_ns &&
                  p.start_ns + p.duration_ns >=
                      part.start_ns + part.duration_ns) {
                matches = true;
                break;
              }
            }
            if (matches) {
              target.parts.push_back(part);
              break;
            }
          }
        }
      }
    }

    std::vector<RenderStage> result;
    for (const auto& ds : remaining_stages) {
      result.push_back(RenderStage{
          .submission_id = ds.submission_id,
          .type = ds.type,
          .parts = ds.parts,
          .debug_name = ds.debug_name,
      });
    }
    return result;
  }
};

std::vector<uint8_t> ReadFile(const std::string& path) {
  std::ifstream input(path, std::ios::binary | std::ios::ate);
  EXPECT_TRUE(input.is_open()) << "Failed to open file: " << path;
  std::streamsize size = input.tellg();
  input.seekg(0, std::ios::beg);
  std::vector<uint8_t> buffer(size);
  input.read(reinterpret_cast<char*>(buffer.data()), size);
  return buffer;
}

std::unique_ptr<perfetto::trace_processor::TraceProcessor> ParseTrace(
    const std::string& data) {
  perfetto::trace_processor::Config config;
  auto tp = perfetto::trace_processor::TraceProcessor::CreateInstance(config);
  std::unique_ptr<uint8_t[]> parse_buf(new uint8_t[data.size()]);
  memcpy(parse_buf.get(), data.data(), data.size());
  auto status = tp->Parse(std::move(parse_buf), data.size());
  EXPECT_TRUE(status.ok()) << "TraceProcessor failed to parse trace: "
                           << status.message();
  tp->NotifyEndOfFile();
  return tp;
}

}  // namespace

TEST(PerfettoCropperTest, TestSplitGpuFrameTimeline) {
  std::string test_trace_path =
      "tools/base/profiler/native/sherlock/testdata/"
      "cropper-test-trace.pftrace";

  auto buffer = ReadFile(test_trace_path);
  ASSERT_FALSE(buffer.empty());

  int64_t start_ns = 789037370000000LL;
  int64_t end_ns = 789038670000000LL;

  std::stringstream cropped_stream;
  absl::Status status = SplitGpuFrameTimeline(buffer.data(), buffer.size(),
                                              cropped_stream, start_ns, end_ns);
  EXPECT_TRUE(status.ok()) << status;

  std::string cropped_data = cropped_stream.str();
  EXPECT_GT(cropped_data.size(), 0);
  EXPECT_LT(cropped_data.size(), buffer.size());

  // Validate the cropped trace using TraceProcessor
  auto tp = ParseTrace(cropped_data);

  // Verify the exact number of render stages
  auto rs = RenderStagesUtils::GetAllRenderStages(tp.get());
  EXPECT_EQ(rs.size(), 9);

  // Verify process table doesn't contain any processes with a not null name
  auto process_it = tp->ExecuteQuery(kProcessTableCountSql);
  ASSERT_TRUE(process_it.Next());
  EXPECT_EQ(process_it.Get(0).long_value, 0);

  // Verify render stage names
  auto debug_names_it = tp->ExecuteQuery(kRenderPassDebugNameCountSql);
  ASSERT_TRUE(debug_names_it.Next());
  EXPECT_EQ(debug_names_it.Get(0).long_value, 3);

  // Verify GPU counter events
  auto counter_it = tp->ExecuteQuery(kCounterEventsCountSql);
  ASSERT_TRUE(counter_it.Next());
  EXPECT_EQ(counter_it.Get(0).long_value, 39703);
}

TEST(PerfettoCropperTest,
     TestSplitGpuFrameTimeline_WithSpecifications_KeepNames) {
  std::string test_trace_path =
      "tools/base/profiler/native/sherlock/testdata/"
      "cropper-test-specifications.perfetto";

  auto buffer = ReadFile(test_trace_path);
  ASSERT_FALSE(buffer.empty());

  // Find the range of the render stages matching PerfettoCropperTest.kt
  auto orig_tp = ParseTrace(std::string(buffer.begin(), buffer.end()));
  auto orig_rs = RenderStagesUtils::GetAllRenderStages(orig_tp.get());
  ASSERT_FALSE(orig_rs.empty());

  int64_t start_ns = std::numeric_limits<int64_t>::max();
  int64_t end_ns = std::numeric_limits<int64_t>::min();
  for (const auto& stage : orig_rs) {
    for (const auto& part : stage.parts) {
      start_ns = std::min(start_ns, part.start_ns);
      end_ns = std::max(end_ns, part.start_ns + part.duration_ns);
    }
  }

  std::stringstream cropped_stream;
  absl::Status status = SplitGpuFrameTimeline(buffer.data(), buffer.size(),
                                              cropped_stream, start_ns, end_ns);
  EXPECT_TRUE(status.ok()) << status;

  std::string cropped_data = cropped_stream.str();
  EXPECT_GT(cropped_data.size(), 0);

  auto cropped_tp = ParseTrace(cropped_data);

  // Verify the exact number of render stages
  auto rs = RenderStagesUtils::GetAllRenderStages(cropped_tp.get());
  EXPECT_EQ(rs.size(), 19);

  // Verify that we have some stages containing RenderPass, Subpass, Draw, etc.
  std::set<std::string> all_part_names;
  for (const auto& stage : rs) {
    for (const auto& part : stage.parts) {
      all_part_names.insert(part.name);
    }
  }
  EXPECT_TRUE(all_part_names.count("RenderPass") > 0);
  EXPECT_TRUE(all_part_names.count("Subpass") > 0);
  EXPECT_TRUE(all_part_names.count("Draw") > 0);
  EXPECT_TRUE(all_part_names.count("Transfer") > 0);
  EXPECT_EQ(all_part_names.count("IB"), 0);
  EXPECT_EQ(all_part_names.count("CmdBuffer"), 0);

  // Verify a specific render stage structure is preserved
  const std::vector<std::string> expected_parts = {
      "RenderPass", "Transfer", "Subpass", "Transfer", "Draw", "Draw"};
  bool found_complex_stage = false;
  for (const auto& stage : rs) {
    std::vector<std::string> part_names;
    for (const auto& part : stage.parts) {
      part_names.push_back(part.name);
    }
    if (part_names == expected_parts) {
      found_complex_stage = true;
      break;
    }
  }
  EXPECT_TRUE(found_complex_stage);

  // Verify process table doesn't contain any processes with a not null name
  auto process_it = cropped_tp->ExecuteQuery(kProcessTableCountSql);
  ASSERT_TRUE(process_it.Next());
  EXPECT_EQ(process_it.Get(0).long_value, 0);
}

}  // namespace sherlock
