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

#ifndef COVERAGE_LOG_H_
#define COVERAGE_LOG_H_

namespace coverage {

class Log {
 public:
  static void I(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
  static void E(const char* fmt, ...) __attribute__((format(printf, 1, 2)));

 private:
  static constexpr const char* const kTag = "studio.coverage";
};

}  // namespace coverage

#endif  // COVERAGE_LOG_H_
