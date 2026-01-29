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

import com.android.build.gradle.integration.common.fixture.project.builder.DirectGradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.FileUpdateBuilder
import com.android.build.gradle.integration.common.fixture.project.reversible.FileState.State.DOES_NOT_EXIST
import com.android.build.gradle.integration.common.fixture.project.reversible.FileState.State.EXIST
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes

/**
 * Implementation of [com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles] that is reversible.
 *
 * It's meant to work with [FileChangeController], and should be instantiated via [FileChangeController.newGradleProjectFiles]
 */
internal open class ReversibleGradleProjectFiles(location: Path) : DirectGradleProjectFiles(location) {

  private val originalStates = mutableMapOf<String, FileState>()

  override fun add(relativePath: String, content: String) {
    recordCurrentState(relativePath)
    super.add(relativePath, content)
  }

  override fun add(relativePath: String, content: ByteArray) {
    recordCurrentState(relativePath)
    super.add(relativePath, content)
  }

  override fun update(relativePath: String): FileUpdateBuilder {
    recordCurrentState(relativePath)
    return super.update(relativePath)
  }

  override fun remove(relativePath: String) {
    recordCurrentState(relativePath)
    super.remove(relativePath)
  }

  fun revert() {
    originalStates.entries.forEach { entry ->
      val relativePath = entry.key
      when (entry.value.type) {
        EXIST -> {
          location.resolve(relativePath).writeBytes(entry.value.originalContent)
        }
        DOES_NOT_EXIST -> {
          // the file could have been added, and then removed, so we don't
          // throw if the file was already removed somehow.
          location.resolve(relativePath).deleteIfExists()
        }
      }
    }
  }

  private fun recordCurrentState(relativePath: String) {
    originalStates.computeIfAbsent(relativePath) { key ->
      val file = location.resolve(key)

      if (file.isRegularFile()) {
        FileState.existingFile(file.readBytes())
      } else {
        FileState.MISSING_FILE
      }
    }
  }
}
