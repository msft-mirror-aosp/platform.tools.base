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

#ifndef AGENT_OPTIONS_H
#define AGENT_OPTIONS_H

#include <optional>
#include <string>

namespace art_tooling {

// The attach-time configuration passed to the native agent, parsed from the
// options string of format:
//
//   library_dex;agent_dex;agent_class;agent_options
//
// The first three fields are library concerns: the bootstrap library dex to add
// to the bootstrap classloader, the tool agent dex to load in a classloader
// descending from the app classloader, and the agent class to instantiate.
// Everything after the third delimiter is the opaque agent_options string
// handed to the agent unchanged; it may itself contain semicolons.
struct AgentOptions {
  std::string library_dex;
  std::string agent_dex;
  std::string agent_class;
  std::string agent_options;
};

// Parses the agent options string. Returns nullopt when the input is null, has
// fewer than three delimiters, or leaves any of the first three fields empty.
// The first three fields are taken verbatim (no trimming); an empty
// agent_options is valid.
std::optional<AgentOptions> ParseAgentOptions(const char* options);

}  // namespace art_tooling

#endif  // AGENT_OPTIONS_H
