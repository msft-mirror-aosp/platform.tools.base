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

import com.android.build.api.artifact.Artifact
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.reversible.FileChangeController
import java.nio.file.Path

/** Base Class for all reversible projects */
internal abstract class ReversibleGradleProject<ProjectT : GradleProject<ProjectDefinitionT>, ProjectDefinitionT : GradleProjectDefinition>(
  protected val parentProject: ProjectT,
  fileChangeController: FileChangeController,
) : GradleProject<ProjectDefinitionT> {

  @Suppress("UNCHECKED_CAST")
  override val files: GradleProjectFiles =
    fileChangeController.newGradleProjectFiles((parentProject as GradleProjectImpl<ProjectDefinitionT>).location)

  override fun resolve(path: String): Path = parentProject.resolve(path)

  override fun resolve(artifact: Artifact<*>): Path = parentProject.resolve(artifact)

  override val buildDir: Path
    get() = parentProject.buildDir

  override fun reconfigure(action: ProjectDefinitionT.() -> Unit) {
    throw RuntimeException("Cannot reconfigure inside withReversibleModifications")
  }
}
