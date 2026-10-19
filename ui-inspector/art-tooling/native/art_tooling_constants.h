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

#ifndef ART_TOOLING_CONSTANTS_H
#define ART_TOOLING_CONSTANTS_H

namespace art_tooling {

// Log tag prefix shared by every native source in the library.
constexpr const char* kLogTag = "studio.arttooling";

// JNI name of the class that owns the native method declarations and the
// slicer-invoked onEntry/onExit dispatchers. Used by RegisterNatives and as the
// slicer instrumentation callback target.
constexpr const char* kArtToolingClassName =
    "com/android/tools/arttooling/ArtTooling";

// Type descriptor form of kArtToolingClassName, i.e. "L" + name + ";".
constexpr const char* kArtToolingClassDescriptor =
    "Lcom/android/tools/arttooling/ArtTooling;";

// JNI name of the bootstrap loader the native agent invokes to load and start
// the tool-supplied agent.
constexpr const char* kAgentLoaderClassName =
    "com/android/tools/arttooling/AgentLoader";

}  // namespace art_tooling

#endif  // ART_TOOLING_CONSTANTS_H
