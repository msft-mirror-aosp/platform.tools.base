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

#pragma once

#include <cstddef>
#include <cstdint>
#include <ostream>

#include "absl/status/status.h"
#include "absl/types/span.h"

namespace sherlock {

/**
 * Splits GPU Frame timeline related data from a Perfetto trace file (C++
 * equivalent of PerfettoCropper.splitGpuFrameTimeline in PerfettoCropper.kt).
 *
 * Implementation Note (ProtoZero):
 * This C++ cropper is implemented using Perfetto's zero-copy ProtoZero
 * (`pbzero`) decoder API
 * (`protozero::ProtoDecoder`). Unlike standard C++ Protobuf which allocates
 * heap objects for every message and field, ProtoZero decodes fields directly
 * off raw binary memory pointers. This achieves nearly 2x faster execution
 * speed, minimal memory allocation, and eliminates full C++ Protobuf code
 * generation overhead.
 *
 * Reads a raw Perfetto trace buffer, crops it to the specified time window
 * [start_ns, end_ns], filtering irrelevant packets while preserving boundary
 * condition GPU counters, time sync contexts, and stripping PID information to
 * prevent unwanted process entries.
 *
 * @param data Pointer to the raw Perfetto trace binary buffer.
 * @param size Size of the input buffer in bytes.
 * @param out Output stream to write the resulting cropped binary trace to.
 * @param start_ns The start of the cropping time window in nanoseconds
 * (BOOTTIME).
 * @param end_ns The end of the cropping time window in nanoseconds (BOOTTIME).
 * @return absl::OkStatus() on success, or an error status on failure.
 */
absl::Status SplitGpuFrameTimeline(const uint8_t* data, size_t size,
                                   std::ostream& out, int64_t start_ns,
                                   int64_t end_ns);

/**
 * Overloaded version of SplitGpuFrameTimeline accepting an absl::Span.
 *
 * @param data Span view over the raw Perfetto trace binary buffer.
 * @param out Output stream to write the resulting cropped binary trace to.
 * @param start_ns The start of the cropping time window in nanoseconds
 * (BOOTTIME).
 * @param end_ns The end of the cropping time window in nanoseconds (BOOTTIME).
 * @return absl::OkStatus() on success, or an error status on failure.
 */
absl::Status SplitGpuFrameTimeline(absl::Span<const uint8_t> data,
                                   std::ostream& out, int64_t start_ns,
                                   int64_t end_ns);

}  // namespace sherlock
