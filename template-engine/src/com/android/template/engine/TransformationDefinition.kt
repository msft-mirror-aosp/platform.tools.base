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
package com.android.template.engine

/** The definition of a transformation as a data class. See [Transformation] for the associated behavior class. */
sealed class TransformationDefinition(open val sourceLocation: SourceLocation, open val name: String)

/** Template transformation for replacing a string inside a template file */
internal data class StringReplaceDefinition(
  override val sourceLocation: SourceLocation,
  val selector: FileSelector,
  val from: String,
  val to: String,
) : TransformationDefinition(sourceLocation, "string-replace") {
  init {
    require(from.isNotEmpty()) { "'from' string cannot be empty" }
  }
}

/** Template transformation for renaming a template file */
internal data class RenameFileDefinition(
  override val sourceLocation: SourceLocation,
  val selector: FileSelector,
  val sourcePath: String,
  val targetPath: String,
) : TransformationDefinition(sourceLocation, "rename-file") {
  init {
    require(sourcePath.isNotEmpty()) { "'sourcePath' string cannot be empty" }
  }
}

/** Specification of which template file(s) a transformation applies to */
internal sealed class FileSelector {
  data class Glob(val pattern: String) : FileSelector()
}
