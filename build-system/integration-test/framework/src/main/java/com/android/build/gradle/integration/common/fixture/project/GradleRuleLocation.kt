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

package com.android.build.gradle.integration.common.fixture.project

import com.android.build.gradle.integration.common.fixture.gradle_project.ProjectLocation
import com.android.build.gradle.integration.common.fixture.gradle_project.TestLocation
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import java.nio.file.Path

/**
 * Location information for tests using [GradleRule]
 *
 * This is used internally by the fixture to handle location, and not meant to be used directly by test implementations.
 */
internal class GradleRuleLocation
private constructor(
  /** The location created specifically for a given test to write its own files. */
  val testFiles: Path,
  /** Other test folders that can be read for information. This is not meant to be writeable. */
  val testSupportLocations: TestLocation,
) {
  constructor(projectLocation: ProjectLocation) : this(projectLocation.projectDir.toPath(), projectLocation.testLocation)

  fun resolve(subFolder: String): GradleRuleLocation = GradleRuleLocation(testFiles.resolve(subFolder), testSupportLocations)

  fun toProjectLocation(buildDefinition: GradleBuildDefinition): ProjectLocation {
    return ProjectLocation(testFiles.resolve((buildDefinition as GradleBuildDefinitionImpl).rootFolderName).toFile(), testSupportLocations)
  }
}
