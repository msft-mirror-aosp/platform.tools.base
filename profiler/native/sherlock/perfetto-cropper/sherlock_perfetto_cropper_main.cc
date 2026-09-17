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

ABSL_FLAG(std::string, input, "", "Input Perfetto trace file path");
ABSL_FLAG(std::string, output, "", "Output cropped Perfetto trace file path");
ABSL_FLAG(int64_t, start_ns, 0, "Start timestamp in nanoseconds");
ABSL_FLAG(int64_t, end_ns, 0, "End timestamp in nanoseconds");

int main(int argc, char** argv) {
  absl::InitializeLog();
  absl::ParseCommandLine(argc, argv);

  std::string input_path = absl::GetFlag(FLAGS_input);
  std::string output_path = absl::GetFlag(FLAGS_output);
  int64_t start_ns = absl::GetFlag(FLAGS_start_ns);
  int64_t end_ns = absl::GetFlag(FLAGS_end_ns);

  if (input_path.empty() || output_path.empty()) {
    LOG(ERROR) << "Usage: " << argv[0]
               << " --input=<input.perfetto> --output=<output.perfetto> "
                  "--start_ns=<start> --end_ns=<end>";
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
      sherlock::SplitGpuFrameTimeline(mapped.data, mapped.size, out, start_ns, end_ns);

  sherlock::UnmapInputFile(&mapped);

  if (!status.ok()) {
    LOG(ERROR) << "SplitGpuFrameTimeline failed: " << status;
    return 1;
  }

  return 0;
}
