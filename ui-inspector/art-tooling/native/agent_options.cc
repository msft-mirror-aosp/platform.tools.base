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

#include "agent_options.h"

namespace art_tooling {

std::optional<AgentOptions> ParseAgentOptions(const char* options) {
  if (options == nullptr) {
    return std::nullopt;
  }

  std::string value(options);

  std::size_t first = value.find(';');
  if (first == std::string::npos) {
    return std::nullopt;
  }
  std::size_t second = value.find(';', first + 1);
  if (second == std::string::npos) {
    return std::nullopt;
  }
  std::size_t third = value.find(';', second + 1);
  if (third == std::string::npos) {
    return std::nullopt;
  }

  AgentOptions parsed;
  parsed.library_dex = value.substr(0, first);
  parsed.agent_dex = value.substr(first + 1, second - first - 1);
  parsed.agent_class = value.substr(second + 1, third - second - 1);
  parsed.agent_options = value.substr(third + 1);

  if (parsed.library_dex.empty() || parsed.agent_dex.empty() ||
      parsed.agent_class.empty()) {
    return std::nullopt;
  }

  return parsed;
}

}  // namespace art_tooling
