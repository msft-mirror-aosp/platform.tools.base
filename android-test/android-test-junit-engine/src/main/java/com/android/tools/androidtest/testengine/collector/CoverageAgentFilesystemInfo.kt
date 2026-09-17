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

package com.android.tools.androidtest.testengine.collector

/**
 * A holder for the resolved sandbox paths of the on-the-fly coverage agent.
 *
 * These paths are resolved after APK installation but before test execution.
 */
class CoverageAgentFilesystemInfo {
  /** The absolute path to the .so binary in the app sandbox. */
  var agentBinaryPathOnDevice: String? = null

  /** The absolute path to the directory where artifacts are stored. */
  var dataDirectoryOnDevice: String? = null
}
