/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.project.reversible

import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleProject
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import java.io.Closeable
import java.nio.file.Path

/**
 * The main controller for a series reversible file changes during a test.
 *
 * This is used by [GradleBuild.withReversibleModifications].
 */
internal class FileChangeController : Closeable {
  private val recorders = mutableListOf<ReversibleGradleProjectFiles>()

  /** Creates a new context for a [GradleProject] */
  fun newGradleProjectFiles(location: Path): GradleProjectFiles = ReversibleGradleProjectFiles(location).also { recorders.add(it) }

  fun newAndroidProjectFiles(parent: AndroidProjectFiles, location: Path): AndroidProjectFiles =
    ReversibleAndroidProjectFiles(parent, location).also { recorders.add(it) }

  override fun close() {
    recorders.forEach { it.revert() }
  }
}
