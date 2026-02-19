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

/** The State of a file, including its content if it exist. */
internal class FileState private constructor(val type: State, private val _originalContent: ByteArray? = null) {
  val originalContent: ByteArray
    get() {
      if (type == State.DOES_NOT_EXIST) {
        throw RuntimeException("Do not call 'FileState.originalContent on DOES_NOT_EXIST state")
      }
      return _originalContent!!
    }

  /**
   * Enum for the actual file state.
   *
   * see [FileState]
   */
  enum class State {
    EXIST,
    DOES_NOT_EXIST,
  }

  companion object {
    /** reusable [FileState] for any non existent file */
    val MISSING_FILE = FileState(State.DOES_NOT_EXIST)

    /**
     * Creates a [FileState] with [State.EXIST] and the given original file content.
     *
     * @param content the original file content
     */
    fun existingFile(content: ByteArray): FileState = FileState(State.EXIST, content)
  }
}
