/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.project.builder

import java.nio.file.Path

/**
 * This class contains global state information required to write build files on disk.
 *
 * The information in here is either computed from the whole build (all sub-projects), and must be kept around in case some build files need
 * to be rewritten, or must come from outside the definition (e.g. cannot be hardcoded or unit test may fail).
 */
interface GlobalDefinitionState {

  /**
   * List of additional properties needed to run the test projects.
   *
   * This is in addition to properties provided via the DSL.
   */
  val additionalProperties: List<String>

  /** The repositories needed to run the build */
  val repositories: Collection<Path>

  /**
   * Map of custom plugins. the map is from the subproject path to the set of custom plugin class name that need to be applied.
   *
   * This is computed from the first write of the build, and cannot (at the moment) be recomputed
   */
  val customPluginMap: Map<String, Set<String>>
}

class GlobalDefinitionStateImpl(
  override val additionalProperties: List<String>,
  override val repositories: Collection<Path>,
  override val customPluginMap: Map<String, Set<String>>,
) : GlobalDefinitionState
