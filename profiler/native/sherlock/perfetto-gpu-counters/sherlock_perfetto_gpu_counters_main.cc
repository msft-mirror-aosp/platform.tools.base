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
#include "tools/base/profiler/native/sherlock/utils/mapped_file.h"

#include <cstdint>
#include <fstream>
#include <iostream>
#include <string>

#include "absl/flags/flag.h"
#include "absl/flags/parse.h"
#include "absl/log/initialize.h"
#include "absl/log/log.h"
#include "absl/status/status.h"

ABSL_FLAG(std::string, input, "", "Input Perfetto trace file path (required)");
ABSL_FLAG(std::string, output, "",
          "Output GpuCounterDescriptor proto binary file path (required)");

int main(int argc, char** argv) {
  absl::InitializeLog();
  absl::ParseCommandLine(argc, argv);

  std::string input_path = absl::GetFlag(FLAGS_input);
  std::string output_path = absl::GetFlag(FLAGS_output);

  if (input_path.empty() || output_path.empty()) {
    LOG(ERROR) << "Usage: " << argv[0]
               << " --input=<input.perfetto> --output=<output.pb>";
    return 1;
  }

  std::ofstream out(output_path, std::ios::binary);
  if (!out.is_open()) {
    LOG(ERROR) << "Failed to open output file: " << output_path;
    return 1;
  }

  sherlock::MappedFile mapped;
  if (!sherlock::MapInputFile(input_path, &mapped)) {
    LOG(ERROR) << "Failed to open or memory-map input file: " << input_path;
    return 1;
  }

  absl::Status status =
      sherlock::ExtractGpuCounterDescriptor(mapped.data, mapped.size, out);

  sherlock::UnmapInputFile(&mapped);

  if (!status.ok()) {
    LOG(ERROR) << "ExtractGpuCounterDescriptor failed: " << status;
    return 1;
  }

  return 0;
}
