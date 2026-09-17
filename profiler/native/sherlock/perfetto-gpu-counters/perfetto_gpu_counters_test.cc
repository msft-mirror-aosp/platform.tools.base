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

#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "gtest/gtest.h"
#include "perfetto/protozero/scattered_heap_buffer.h"
#include "protos/perfetto/common/gpu_counter_descriptor.pbzero.h"
#include "protos/perfetto/trace/gpu/gpu_counter_event.pbzero.h"
#include "protos/perfetto/trace/trace.pbzero.h"
#include "protos/perfetto/trace/trace_packet.pbzero.h"

namespace sherlock {
namespace {

std::vector<uint8_t> ReadTestFile(const std::string& path) {
  std::ifstream file(path, std::ios::binary | std::ios::ate);
  if (!file.is_open()) {
    return {};
  }
  std::streamsize size = file.tellg();
  file.seekg(0, std::ios::beg);
  std::vector<uint8_t> buffer(size);
  if (file.read(reinterpret_cast<char*>(buffer.data()), size)) {
    return buffer;
  }
  return {};
}

TEST(PerfettoGpuCountersTest, ExtractGpuCounterDescriptorFromSyntheticTrace) {
  protozero::HeapBuffered<perfetto::protos::pbzero::Trace> trace;

  // Dummy packet
  trace->add_packet();

  // Packet with GPU counter descriptor
  auto* packet = trace->add_packet();
  auto* gpu_event = packet->set_gpu_counter_event();
  auto* descriptor = gpu_event->set_counter_descriptor();

  auto* spec1 = descriptor->add_specs();
  spec1->set_counter_id(1);
  spec1->set_name("Counter 1");

  auto* spec2 = descriptor->add_specs();
  spec2->set_counter_id(2);
  spec2->set_name("Counter 2");

  std::vector<uint8_t> trace_bytes = trace.SerializeAsArray();

  std::ostringstream out;
  absl::Status status = ExtractGpuCounterDescriptor(trace_bytes, out);
  ASSERT_TRUE(status.ok()) << status.message();

  std::string desc_bytes = out.str();
  perfetto::protos::pbzero::GpuCounterDescriptor::Decoder decoded(
      reinterpret_cast<const uint8_t*>(desc_bytes.data()), desc_bytes.size());

  ASSERT_TRUE(decoded.has_specs());
  std::vector<uint32_t> counter_ids;
  std::vector<std::string> names;

  for (auto it = decoded.specs(); it; ++it) {
    perfetto::protos::pbzero::GpuCounterDescriptor::GpuCounterSpec::Decoder
        spec(it->data(), it->size());
    counter_ids.push_back(spec.counter_id());
    names.push_back(spec.name().ToStdString());
  }

  ASSERT_EQ(counter_ids.size(), 2);
  EXPECT_EQ(counter_ids[0], 1);
  EXPECT_EQ(names[0], "Counter 1");
  EXPECT_EQ(counter_ids[1], 2);
  EXPECT_EQ(names[1], "Counter 2");
}

TEST(PerfettoGpuCountersTest, ExtractGpuCounterDescriptorFromTestTrace) {
  std::string trace_path =
      "tools/base/profiler/native/sherlock/testdata/cropper-test-trace.pftrace";
  std::vector<uint8_t> trace_data = ReadTestFile(trace_path);
  ASSERT_FALSE(trace_data.empty())
      << "Failed to read test trace file at " << trace_path;

  std::ostringstream out;
  absl::Status status = ExtractGpuCounterDescriptor(trace_data, out);
  EXPECT_TRUE(status.ok()) << status.message();

  std::string desc_bytes = out.str();
  EXPECT_FALSE(desc_bytes.empty())
      << "Extracted GPU counter descriptor bytes should not be empty";
}

TEST(PerfettoGpuCountersTest, ReturnsNotFoundWhenNoGpuCounters) {
  std::vector<uint8_t> empty_trace;
  std::ostringstream out;
  absl::Status status = ExtractGpuCounterDescriptor(empty_trace, out);
  EXPECT_FALSE(status.ok());
  EXPECT_EQ(status.code(), absl::StatusCode::kNotFound);
}

}  // namespace
}  // namespace sherlock
