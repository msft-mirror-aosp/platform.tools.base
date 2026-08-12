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

#include <algorithm>
#include <cstdint>
#include <fstream>
#include <utility>
#include <vector>

#include "absl/container/flat_hash_map.h"
#include "absl/log/log.h"
#include "absl/status/status.h"
#include "perfetto/protozero/proto_decoder.h"
#include "perfetto/protozero/proto_utils.h"
#include "perfetto/protozero/scattered_heap_buffer.h"
#include "protos/perfetto/common/builtin_clock.pbzero.h"
#include "protos/perfetto/trace/clock_snapshot.pbzero.h"
#include "protos/perfetto/trace/gpu/gpu_counter_event.pbzero.h"
#include "protos/perfetto/trace/gpu/gpu_render_stage_event.pbzero.h"
#include "protos/perfetto/trace/gpu/vulkan_api_event.pbzero.h"
#include "protos/perfetto/trace/interned_data/interned_data.pbzero.h"
#include "protos/perfetto/trace/trace.pbzero.h"
#include "protos/perfetto/trace/trace_packet.pbzero.h"
#include "protos/perfetto/trace/track_event/process_descriptor.pbzero.h"
#include "protos/perfetto/trace/track_event/thread_descriptor.pbzero.h"
#include "protos/perfetto/trace/track_event/track_descriptor.pbzero.h"

namespace sherlock {

// Architecture & Performance Note (ProtoZero Implementation):
// This module implements Perfetto trace cropping using Perfetto's
// high-performance zero-copy ProtoZero (`pbzero`) decoder API
// (`protozero::ProtoDecoder`).
//
// Key Advantages of ProtoZero over Standard Protobuf:
// 1. Minimal Heap Allocations: Decodes fields directly from raw byte slices
// without instantiating full C++ Message objects.
// 2. Zero Copy: Field content is decoded as lightweight raw memory views
// (ConstBytes).
// 3. High Performance: ~2x faster execution speed.
// 4. Zero Build Overhead: Links directly against @perfetto//:trace_processor
// without generating .pb.cc files.

namespace {

constexpr uint32_t kClockBoottime =
    perfetto::protos::pbzero::BUILTIN_CLOCK_BOOTTIME;

/**
 * Encapsulates the context required to synchronize trace packet timestamps to
 * the BOOTTIME clock. Maps various clock IDs to BOOTTIME using
 * `clock_snapshots` and resolves default clocks for trusted packet sequences
 * using `sequence_defaults`.
 */
struct TimeSyncContext {
  absl::flat_hash_map<uint32_t, std::vector<std::pair<uint64_t, uint64_t>>>
      clock_snapshots;
  absl::flat_hash_map<uint32_t, uint32_t> sequence_defaults;

  /**
   * Converts a trace packet's timestamp to the BOOTTIME clock domain using
   * O(log N) binary search over pre-sorted clock snapshots (or the first
   * available snapshot if none precede the timestamp).
   *
   * @param packet The trace packet decoder.
   * @return A std::pair containing the converted timestamp (synchronized to
   * BOOTTIME if successful) and a boolean flag indicating if the conversion
   * succeeded.
   */
  std::pair<uint64_t, bool> ConvertToBoottime(
      const perfetto::protos::pbzero::TracePacket::Decoder& packet) const {
    uint32_t clock_id = kClockBoottime;
    if (packet.has_timestamp_clock_id()) {
      clock_id = packet.timestamp_clock_id();
    } else if (packet.has_trusted_packet_sequence_id()) {
      auto it = sequence_defaults.find(packet.trusted_packet_sequence_id());
      if (it != sequence_defaults.end()) {
        clock_id = it->second;
      }
    }

    if (clock_id == kClockBoottime) {
      return {packet.timestamp(), true};
    }

    auto it = clock_snapshots.find(clock_id);
    if (it == clock_snapshots.end() || it->second.empty()) {
      return {packet.timestamp(), false};
    }

    const auto& snapshots = it->second;
    auto snap_it = std::upper_bound(
        snapshots.begin(), snapshots.end(), packet.timestamp(),
        [](uint64_t ts, const std::pair<uint64_t, uint64_t>& p) {
          return ts < p.second;
        });

    const auto& used_snapshot = (snap_it != snapshots.begin())
                                    ? *std::prev(snap_it)
                                    : snapshots.front();
    uint64_t closest_boottime = used_snapshot.first;
    uint64_t closest_source = used_snapshot.second;
    int64_t offset = static_cast<int64_t>(packet.timestamp()) -
                     static_cast<int64_t>(closest_source);
    return {
        static_cast<uint64_t>(static_cast<int64_t>(closest_boottime) + offset),
        true};
  }
};

/**
 * Collects clock snapshots and sequence timestamp defaults from the original
 * trace. This context is required to synchronize packets with different clock
 * domains to BOOTTIME.
 */
TimeSyncContext CollectTimeSyncContext(const uint8_t* data, size_t size) {
  TimeSyncContext sync;
  protozero::ProtoDecoder trace_decoder(data, size);

  for (auto field = trace_decoder.ReadField(); field.valid();
       field = trace_decoder.ReadField()) {
    if (field.id() != perfetto::protos::pbzero::Trace::kPacketFieldNumber) {
      continue;
    }

    perfetto::protos::pbzero::TracePacket::Decoder packet(field.data(),
                                                          field.size());
    if (packet.has_clock_snapshot()) {
      uint64_t boottime_ts = 0;
      bool found_boottime = false;

      perfetto::protos::pbzero::ClockSnapshot::Decoder snapshot(
          packet.clock_snapshot());
      for (auto clock_it = snapshot.clocks(); clock_it; ++clock_it) {
        perfetto::protos::pbzero::ClockSnapshot::Clock::Decoder clock(
            *clock_it);
        if (clock.clock_id() == kClockBoottime) {
          boottime_ts = clock.timestamp();
          found_boottime = true;
          break;
        }
      }

      if (found_boottime) {
        for (auto clock_it = snapshot.clocks(); clock_it; ++clock_it) {
          perfetto::protos::pbzero::ClockSnapshot::Clock::Decoder clock(
              *clock_it);
          sync.clock_snapshots[clock.clock_id()].push_back(
              {boottime_ts, clock.timestamp()});
        }
      }
    }

    if (packet.has_trusted_packet_sequence_id() &&
        packet.has_timestamp_clock_id()) {
      sync.sequence_defaults[packet.trusted_packet_sequence_id()] =
          packet.timestamp_clock_id();
    }
  }

  for (auto& [_, list] : sync.clock_snapshots) {
    std::sort(list.begin(), list.end(),
              [](const auto& a, const auto& b) { return a.second < b.second; });
  }
  return sync;
}

struct GpuCounterData {
  uint32_t counter_id;
  std::vector<uint8_t> raw_bytes;
};

/**
 * Collects all GPU counter events grouped by counter ID.
 */
absl::flat_hash_map<uint32_t, std::vector<std::pair<uint64_t, GpuCounterData>>>
CollectGpuCounters(const uint8_t* data, size_t size,
                   const TimeSyncContext& sync) {
  absl::flat_hash_map<uint32_t,
                      std::vector<std::pair<uint64_t, GpuCounterData>>>
      all_counters;
  protozero::ProtoDecoder trace_decoder(data, size);

  for (auto field = trace_decoder.ReadField(); field.valid();
       field = trace_decoder.ReadField()) {
    if (field.id() != perfetto::protos::pbzero::Trace::kPacketFieldNumber) {
      continue;
    }

    perfetto::protos::pbzero::TracePacket::Decoder packet(field.data(),
                                                          field.size());
    if (packet.has_gpu_counter_event() && packet.has_timestamp() &&
        packet.timestamp() != 0) {
      auto [ts, ok] = sync.ConvertToBoottime(packet);
      if (ok) {
        perfetto::protos::pbzero::GpuCounterEvent::Decoder gpu_evt(
            packet.gpu_counter_event());
        for (auto c_it = gpu_evt.counters(); c_it; ++c_it) {
          perfetto::protos::pbzero::GpuCounterEvent::GpuCounter::Decoder
              counter(*c_it);
          if (counter.has_counter_id()) {
            GpuCounterData counter_data{
                counter.counter_id(),
                std::vector<uint8_t>(c_it->data(),
                                     c_it->data() + c_it->size())};
            all_counters[counter.counter_id()].push_back({ts, counter_data});
          }
        }
      }
    }
  }

  for (auto& [_, list] : all_counters) {
    std::stable_sort(
        list.begin(), list.end(),
        [](const auto& a, const auto& b) { return a.first < b.first; });
  }
  return all_counters;
}

/**
 * Writes a length-delimited protobuf field (Field Tag 1 for TracePacket) to the
 * output stream.
 */
void WriteTracePacket(std::ostream& out, const uint8_t* packet_data,
                      size_t packet_size) {
  uint32_t tag = protozero::proto_utils::MakeTagLengthDelimited(
      perfetto::protos::pbzero::Trace::kPacketFieldNumber);

  // Encode varint tag and length
  uint8_t varint_buf[20];
  uint8_t* p = varint_buf;
  p = protozero::proto_utils::WriteVarInt(tag, p);
  p = protozero::proto_utils::WriteVarInt(packet_size, p);

  out.write(reinterpret_cast<const char*>(varint_buf), p - varint_buf);
  out.write(reinterpret_cast<const char*>(packet_data), packet_size);
}

/**
 * Injects boundary GPU counters at start_ns and end_ns.
 */
void InjectInitialGpuCounters(
    std::ostream& out,
    const absl::flat_hash_map<uint32_t,
                              std::vector<std::pair<uint64_t, GpuCounterData>>>&
        all_counters,
    int64_t start_ns, int64_t end_ns) {
  // Inject latest known counters before start_ns at timestamp start_ns
  std::vector<GpuCounterData> last_known;
  for (const auto& [id, events] : all_counters) {
    auto it =
        std::find_if(events.rbegin(), events.rend(), [start_ns](const auto& p) {
          return p.first < static_cast<uint64_t>(start_ns);
        });
    if (it != events.rend()) {
      last_known.push_back(it->second);
    }
  }

  if (!last_known.empty()) {
    protozero::HeapBuffered<perfetto::protos::pbzero::TracePacket> pkt;
    pkt->set_timestamp(start_ns);
    pkt->set_timestamp_clock_id(kClockBoottime);
    auto* gpu_evt = pkt->set_gpu_counter_event();
    for (const auto& c : last_known) {
      gpu_evt->AppendBytes(
          perfetto::protos::pbzero::GpuCounterEvent::kCountersFieldNumber,
          c.raw_bytes.data(), c.raw_bytes.size());
    }
    std::vector<uint8_t> vec = pkt.SerializeAsArray();
    WriteTracePacket(out, vec.data(), vec.size());
  }

  // Inject first known counters after end_ns at timestamp end_ns
  std::vector<GpuCounterData> first_known_after;
  for (const auto& [id, events] : all_counters) {
    auto it =
        std::find_if(events.begin(), events.end(), [end_ns](const auto& p) {
          return p.first > static_cast<uint64_t>(end_ns);
        });
    if (it != events.end()) {
      first_known_after.push_back(it->second);
    }
  }

  if (!first_known_after.empty()) {
    protozero::HeapBuffered<perfetto::protos::pbzero::TracePacket> pkt;
    pkt->set_timestamp(end_ns);
    pkt->set_timestamp_clock_id(kClockBoottime);
    auto* gpu_evt = pkt->set_gpu_counter_event();
    for (const auto& c : first_known_after) {
      gpu_evt->AppendBytes(
          perfetto::protos::pbzero::GpuCounterEvent::kCountersFieldNumber,
          c.raw_bytes.data(), c.raw_bytes.size());
    }
    std::vector<uint8_t> vec = pkt.SerializeAsArray();
    WriteTracePacket(out, vec.data(), vec.size());
  }
}

/**
 * Strips PID information from a TracePacket to prevent the `process` table
 * from being populated with unwanted entries.
 *
 * This involves clearing trusted_pid from the packet, `graphics_contexts` from
 * InternedData, `context_spec` from GpuRenderStageEvent specifications, and
 * PID/TID fields from TrackDescriptors. These fields often contain PIDs which
 * cause Trace Processor to create unwanted process entries.
 *
 * @param out Output stream to write the resulting packet to.
 * @param data Pointer to the raw packet data.
 * @param size Size of the raw packet data.
 * @param keep_metadata_only If true, removes timestamp and event fields from
 * the packet.
 */
void WritePacketWithoutPidInfo(std::ostream& out, const uint8_t* data,
                               size_t size, bool keep_metadata_only = false) {
  protozero::ProtoDecoder packet_decoder(data, size);
  protozero::HeapBuffered<perfetto::protos::pbzero::TracePacket> packet_builder;

  while (packet_decoder.bytes_left() > 0) {
    const uint8_t* field_start = data + packet_decoder.read_offset();
    auto field = packet_decoder.ReadField();
    if (!field.valid()) break;
    const uint8_t* field_end = data + packet_decoder.read_offset();
    size_t field_size = field_end - field_start;
    uint32_t field_id = field.id();

    // Clear trusted_pid (field 79)
    if (field_id ==
        perfetto::protos::pbzero::TracePacket::kTrustedPidFieldNumber) {
      continue;
    }

    // Optionally clear timestamp fields
    if (keep_metadata_only &&
        (field_id ==
             perfetto::protos::pbzero::TracePacket::kTimestampFieldNumber ||
         field_id == perfetto::protos::pbzero::TracePacket::
                         kTimestampClockIdFieldNumber)) {
      continue;
    }

    // Clear graphics_contexts from InternedData
    if (field_id ==
        perfetto::protos::pbzero::TracePacket::kInternedDataFieldNumber) {
      protozero::ProtoDecoder interned_decoder(field.data(), field.size());
      auto* interned_builder = packet_builder->set_interned_data();
      const uint8_t* sub_data = field.data();
      while (interned_decoder.bytes_left() > 0) {
        const uint8_t* sf_start = sub_data + interned_decoder.read_offset();
        auto sf = interned_decoder.ReadField();
        if (!sf.valid()) break;
        const uint8_t* sf_end = sub_data + interned_decoder.read_offset();
        if (sf.id() == perfetto::protos::pbzero::InternedData::
                           kGraphicsContextsFieldNumber) {
          continue;
        }
        interned_builder->AppendRawProtoBytes(sf_start, sf_end - sf_start);
      }
      continue;
    }

    // Clear context_spec from GpuRenderStageEvent specifications
    if (field_id == perfetto::protos::pbzero::TracePacket::
                        kGpuRenderStageEventFieldNumber) {
      protozero::ProtoDecoder event_decoder(field.data(), field.size());
      auto* event_builder = packet_builder->set_gpu_render_stage_event();
      const uint8_t* evt_data = field.data();
      while (event_decoder.bytes_left() > 0) {
        const uint8_t* ef_start = evt_data + event_decoder.read_offset();
        auto ef = event_decoder.ReadField();
        if (!ef.valid()) break;
        const uint8_t* ef_end = evt_data + event_decoder.read_offset();
        if (ef.id() == perfetto::protos::pbzero::GpuRenderStageEvent::
                           kSpecificationsFieldNumber) {
          protozero::ProtoDecoder specs_decoder(ef.data(), ef.size());
          auto* specs_builder = event_builder->set_specifications();
          const uint8_t* spec_data = ef.data();
          while (specs_decoder.bytes_left() > 0) {
            const uint8_t* sf_start = spec_data + specs_decoder.read_offset();
            auto sf = specs_decoder.ReadField();
            if (!sf.valid()) break;
            const uint8_t* sf_end = spec_data + specs_decoder.read_offset();
            if (sf.id() == perfetto::protos::pbzero::GpuRenderStageEvent::
                               Specifications::kContextSpecFieldNumber) {
              continue;
            }
            specs_builder->AppendRawProtoBytes(sf_start, sf_end - sf_start);
          }
        } else if (!keep_metadata_only) {
          // Keep event fields only if we are not in metadata-only
          // (keep_metadata_only) mode. This ensures that for pre-window
          // metadata packets, we only keep the specifications and drop the
          // actual event details.
          event_builder->AppendRawProtoBytes(ef_start, ef_end - ef_start);
        }
      }
      continue;
    }

    // Clear pid from VulkanApiEvent debug_utils_object_name
    if (field_id ==
        perfetto::protos::pbzero::TracePacket::kVulkanApiEventFieldNumber) {
      protozero::ProtoDecoder vulkan_decoder(field.data(), field.size());
      auto* vulkan_builder = packet_builder->set_vulkan_api_event();
      const uint8_t* vulkan_data = field.data();
      while (vulkan_decoder.bytes_left() > 0) {
        const uint8_t* vf_start = vulkan_data + vulkan_decoder.read_offset();
        auto vf = vulkan_decoder.ReadField();
        if (!vf.valid()) break;
        const uint8_t* vf_end = vulkan_data + vulkan_decoder.read_offset();
        if (vf.id() == perfetto::protos::pbzero::VulkanApiEvent::
                           kVkDebugUtilsObjectNameFieldNumber) {
          protozero::ProtoDecoder name_decoder(vf.data(), vf.size());
          auto* name_builder = vulkan_builder->set_vk_debug_utils_object_name();
          const uint8_t* name_data = vf.data();
          while (name_decoder.bytes_left() > 0) {
            const uint8_t* nf_start = name_data + name_decoder.read_offset();
            auto nf = name_decoder.ReadField();
            if (!nf.valid()) break;
            const uint8_t* nf_end = name_data + name_decoder.read_offset();
            if (nf.id() == perfetto::protos::pbzero::VulkanApiEvent::
                               VkDebugUtilsObjectName::kPidFieldNumber) {
              continue;
            }
            name_builder->AppendRawProtoBytes(nf_start, nf_end - nf_start);
          }
        } else {
          vulkan_builder->AppendRawProtoBytes(vf_start, vf_end - vf_start);
        }
      }
      continue;
    }

    // Clear pid/tid from TrackDescriptor (process/thread)
    if (field_id ==
        perfetto::protos::pbzero::TracePacket::kTrackDescriptorFieldNumber) {
      protozero::ProtoDecoder track_decoder(field.data(), field.size());
      auto* track_builder = packet_builder->set_track_descriptor();
      const uint8_t* track_data = field.data();
      while (track_decoder.bytes_left() > 0) {
        const uint8_t* tf_start = track_data + track_decoder.read_offset();
        auto tf = track_decoder.ReadField();
        if (!tf.valid()) break;
        const uint8_t* tf_end = track_data + track_decoder.read_offset();
        if (tf.id() ==
            perfetto::protos::pbzero::TrackDescriptor::kProcessFieldNumber) {
          protozero::ProtoDecoder proc_decoder(tf.data(), tf.size());
          auto* proc_builder = track_builder->set_process();
          const uint8_t* proc_data = tf.data();
          while (proc_decoder.bytes_left() > 0) {
            const uint8_t* pf_start = proc_data + proc_decoder.read_offset();
            auto pf = proc_decoder.ReadField();
            if (!pf.valid()) break;
            const uint8_t* pf_end = proc_data + proc_decoder.read_offset();
            if (pf.id() ==
                perfetto::protos::pbzero::ProcessDescriptor::kPidFieldNumber) {
              continue;
            }
            proc_builder->AppendRawProtoBytes(pf_start, pf_end - pf_start);
          }
        } else if (tf.id() == perfetto::protos::pbzero::TrackDescriptor::
                                  kThreadFieldNumber) {
          protozero::ProtoDecoder thread_decoder(tf.data(), tf.size());
          auto* thread_builder = track_builder->set_thread();
          const uint8_t* thread_data = tf.data();
          while (thread_decoder.bytes_left() > 0) {
            const uint8_t* thf_start =
                thread_data + thread_decoder.read_offset();
            auto thf = thread_decoder.ReadField();
            if (!thf.valid()) break;
            const uint8_t* thf_end = thread_data + thread_decoder.read_offset();
            if (thf.id() == perfetto::protos::pbzero::ThreadDescriptor::
                                kPidFieldNumber ||
                thf.id() == perfetto::protos::pbzero::ThreadDescriptor::
                                kTidFieldNumber) {
              continue;
            }
            thread_builder->AppendRawProtoBytes(thf_start, thf_end - thf_start);
          }
        } else {
          track_builder->AppendRawProtoBytes(tf_start, tf_end - tf_start);
        }
      }
      continue;
    }

    // Pass through all other raw field bytes without modifications
    packet_builder->AppendRawProtoBytes(field_start, field_size);
  }

  std::vector<uint8_t> vec = packet_builder.SerializeAsArray();
  WriteTracePacket(out, vec.data(), vec.size());
}

}  // namespace

absl::Status SplitGpuFrameTimeline(const uint8_t* data, size_t size,
                                   std::ostream& out, int64_t start_ns,
                                   int64_t end_ns) {
  if (data == nullptr || size == 0) {
    return absl::InvalidArgumentError("Input trace data is null or empty");
  }

  TimeSyncContext sync = CollectTimeSyncContext(data, size);
  auto all_gpu_counters = CollectGpuCounters(data, size, sync);

  InjectInitialGpuCounters(out, all_gpu_counters, start_ns, end_ns);

  protozero::ProtoDecoder trace_decoder(data, size);

  for (auto field = trace_decoder.ReadField(); field.valid();
       field = trace_decoder.ReadField()) {
    if (field.id() != perfetto::protos::pbzero::Trace::kPacketFieldNumber) {
      continue;
    }

    const uint8_t* packet_data = field.data();
    size_t packet_size = field.size();
    perfetto::protos::pbzero::TracePacket::Decoder packet(packet_data,
                                                          packet_size);

    if (packet.has_clock_snapshot()) {
      WritePacketWithoutPidInfo(out, packet_data, packet_size);
      continue;
    }

    // Filter out empty packets. These are often used solely to emit a
    // trusted_pid, which we purposefully drop to prevent unwanted process table
    // entries.
    if (!packet.has_interned_data() &&
        !packet.has_incremental_state_cleared() &&
        !packet.has_trace_packet_defaults()) {
      // Check if any payload data field is set
      protozero::ProtoDecoder raw_decoder(packet_data, packet_size);
      bool has_data = false;
      for (auto f = raw_decoder.ReadField(); f.valid();
           f = raw_decoder.ReadField()) {
        if (f.id() !=
                perfetto::protos::pbzero::TracePacket::kTrustedPidFieldNumber &&
            f.id() !=
                perfetto::protos::pbzero::TracePacket::kTimestampFieldNumber &&
            f.id() != perfetto::protos::pbzero::TracePacket::
                          kTimestampClockIdFieldNumber &&
            f.id() != perfetto::protos::pbzero::TracePacket::
                          kSequenceFlagsFieldNumber &&
            f.id() != perfetto::protos::pbzero::TracePacket::
                          kTrustedPacketSequenceIdFieldNumber) {
          has_data = true;
          break;
        }
      }
      if (!has_data) {
        continue;
      }
    }

    // Skip unwanted diagnostic and timeline payload fields
    if (packet.has_process_tree() || packet.has_process_stats() ||
        packet.has_process_descriptor() || packet.has_thread_descriptor() ||
        packet.has_track_event() || packet.has_ftrace_events() ||
        packet.has_graphics_frame_event() || packet.has_sys_stats() ||
        packet.has_frame_timeline_event() || packet.has_vulkan_memory_event()) {
      continue;
    }

    if (packet.has_vulkan_api_event()) {
      perfetto::protos::pbzero::VulkanApiEvent::Decoder vulkan_evt(
          packet.vulkan_api_event());
      if (!vulkan_evt.has_vk_debug_utils_object_name()) {
        continue;
      }
    }

    if (packet.has_gpu_counter_event()) {
      perfetto::protos::pbzero::GpuCounterEvent::Decoder gpu_evt(
          packet.gpu_counter_event());
      if (gpu_evt.has_counter_descriptor()) {
        WritePacketWithoutPidInfo(out, packet_data, packet_size);
        continue;
      }
    }

    if (!packet.has_timestamp() || packet.timestamp() == 0) {
      WritePacketWithoutPidInfo(out, packet_data, packet_size);
      continue;
    }

    auto [packet_ts, ok] = sync.ConvertToBoottime(packet);
    if (!ok) continue;

    if (packet.has_vulkan_api_event()) {
      // Keep object names defined before the crop window so they can be
      // resolved for events inside the window. Note: Unlike interned_data,
      // timestamps must NOT be stripped because Trace Processor requires
      // them to index debug object names. These metadata packets do not create
      // slice tracks, so their timestamps will not inflate the trace
      // start_time.
      if (packet_ts <= static_cast<uint64_t>(end_ns)) {
        WritePacketWithoutPidInfo(out, packet_data, packet_size);
      }
      continue;
    }

    if (packet.has_interned_data()) {
      // Keep interned data defined before the crop window so they can be
      // resolved for events inside the window, stripping timestamp information
      // to prevent Trace Processor from assuming the trace starts earlier than
      // intended.
      if (packet_ts <= static_cast<uint64_t>(end_ns)) {
        bool keep_metadata_only = packet_ts < static_cast<uint64_t>(start_ns);
        WritePacketWithoutPidInfo(out, packet_data, packet_size,
                                  keep_metadata_only);
      }
      continue;
    }

    if (packet_ts >= static_cast<uint64_t>(start_ns) &&
        packet_ts <= static_cast<uint64_t>(end_ns)) {
      WritePacketWithoutPidInfo(out, packet_data, packet_size);
      continue;
    }
  }

  return absl::OkStatus();
}

absl::Status SplitGpuFrameTimeline(absl::Span<const uint8_t> data,
                                   std::ostream& out, int64_t start_ns,
                                   int64_t end_ns) {
  return SplitGpuFrameTimeline(data.data(), data.size(), out, start_ns, end_ns);
}

}  // namespace sherlock
