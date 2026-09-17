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
 * Extracts the raw GpuCounterDescriptor proto bytes from a Perfetto trace file
 * (C++ equivalent of PerfettoGpuCounters.loadGpuCounterSpecs in
 * PerfettoGpuCounters.kt).
 *
 * Implementation Note (ProtoZero):
 * This extractor is implemented using Perfetto's zero-copy ProtoZero
 * (`pbzero`) decoder API (`protozero::ProtoDecoder`). It decodes fields
 * directly off raw binary memory pointers without instantiating full Protobuf
 * objects or generating C++ Protobuf classes.
 *
 * @param data Pointer to raw Perfetto trace binary buffer.
 * @param size Size of input buffer in bytes.
 * @param out Output stream to write the raw GpuCounterDescriptor binary bytes
 * to.
 * @return absl::OkStatus() on success, or NOT_FOUND if no counter descriptor is
 * present.
 */
absl::Status ExtractGpuCounterDescriptor(const uint8_t* data, size_t size,
                                         std::ostream& out);

/**
 * Overloaded version of ExtractGpuCounterDescriptor accepting an absl::Span.
 *
 * @param data Span view over raw Perfetto trace binary buffer.
 * @param out Output stream to write the raw GpuCounterDescriptor binary bytes
 * to.
 * @return absl::OkStatus() on success, or NOT_FOUND if no counter descriptor is
 * present.
 */
absl::Status ExtractGpuCounterDescriptor(absl::Span<const uint8_t> data,
                                         std::ostream& out);

}  // namespace sherlock
