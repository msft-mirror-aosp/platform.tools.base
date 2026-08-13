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

#include "perfetto_gpu_counters.h"

#include <cstdint>
#include <ostream>

#include "absl/status/status.h"
#include "perfetto/protozero/proto_decoder.h"
#include "protos/perfetto/trace/gpu/gpu_counter_event.pbzero.h"
#include "protos/perfetto/trace/trace.pbzero.h"
#include "protos/perfetto/trace/trace_packet.pbzero.h"

namespace sherlock {

absl::Status ExtractGpuCounterDescriptor(const uint8_t* data, size_t size,
                                         std::ostream& out) {
  if (data == nullptr && size > 0) {
    return absl::InvalidArgumentError("Null data pointer with non-zero size");
  }

  perfetto::protos::pbzero::Trace::Decoder trace(data, size);

  for (auto it = trace.packet(); it; ++it) {
    perfetto::protos::pbzero::TracePacket::Decoder packet(it->data(),
                                                          it->size());
    if (!packet.has_gpu_counter_event()) {
      continue;
    }

    perfetto::protos::pbzero::GpuCounterEvent::Decoder gpu_evt(
        packet.gpu_counter_event());
    if (gpu_evt.has_counter_descriptor()) {
      auto desc_bytes = gpu_evt.counter_descriptor();
      out.write(reinterpret_cast<const char*>(desc_bytes.data),
                desc_bytes.size);
      if (!out.good()) {
        return absl::InternalError(
            "Failed to write GPU counter descriptor to output stream");
      }
      return absl::OkStatus();
    }
  }

  return absl::NotFoundError(
      "No GPU counter descriptor found in Perfetto trace");
}

absl::Status ExtractGpuCounterDescriptor(absl::Span<const uint8_t> data,
                                         std::ostream& out) {
  return ExtractGpuCounterDescriptor(data.data(), data.size(), out);
}

}  // namespace sherlock
