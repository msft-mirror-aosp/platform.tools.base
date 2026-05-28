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

/** A templation definition contains the [metadata] about the template (name, etc.) as well as all the [files] of the template. */
data class TemplateDefinition(val metadata: TemplateMetadata, val files: List<TemplateFile>) {
  val name: String
    get() = metadata.name

  val shortName: String
    get() = metadata.shortName
}

data class TemplateMetadata(
  val sourceLocation: SourceLocation,
  val name: String,
  val shortName: String,
  val tags: List<String>,
  val arguments: List<TemplateArgument>,
  val dependencies: List<TemplateDependency>,
  val transformations: List<TransformationDefinition>,
  val schemaVersion: SchemaVersion,
)

data class TemplateArgument(val sourceLocation: SourceLocation, val id: String, val defaultValue: String)

data class TemplateDependency(val sourceLocation: SourceLocation, val sdkPackage: String)

data class TemplateFile(
  val relativePath: String, // E.g., "app/build.gradle.kts"
  val content: ByteArray,
) {
  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TemplateFile) return false

    if (relativePath != other.relativePath) return false
    if (!content.contentEquals(other.content)) return false

    return true
  }

  override fun hashCode(): Int {
    var result = relativePath.hashCode()
    result = 31 * result + content.contentHashCode()
    return result
  }
}

/**
 * The schema version of a [TemplateDefinition], corresponding to a given set of features. This allows template engines to know if they can
 * process a given [TemplateDefinition] by comparing their own schema version to a template schema version.
 */
data class SchemaVersion(val major: Int, val minor: Int, val micro: Int? = null) : Comparable<SchemaVersion> {

  override fun compareTo(other: SchemaVersion): Int {
    val majorDiff = this.major.compareTo(other.major)
    if (majorDiff != 0) return majorDiff
    val minorDiff = this.minor.compareTo(other.minor)
    if (minorDiff != 0) return minorDiff
    return when {
      this.micro == null && other.micro == null -> 0
      this.micro == null -> -1
      other.micro == null -> 1
      else -> this.micro.compareTo(other.micro)
    }
  }

  override fun toString(): String {
    return if (micro != null) {
      "$major.$minor.$micro"
    } else {
      "$major.$minor"
    }
  }

  companion object {
    val implicitVersion: SchemaVersion = SchemaVersion(major = 0, minor = 1)

    fun fromString(version: String): SchemaVersion {
      val parts = version.split('.')
      val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
      val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
      val micro = parts.getOrNull(2)?.toIntOrNull()
      return SchemaVersion(major, minor, micro)
    }
  }
}

sealed class SourceLocation

internal data class FileLocation(val relativePath: String, val lineNumber: Int) : SourceLocation() {
  override fun toString(): String {
    return "$relativePath:${lineNumber+1}"
  }
}
