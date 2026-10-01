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

package com.android.tools.ui.inspector.deploy

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.nameWithoutExtension

/** Cache of resources extracted to temporary disk files, ensuring each resource is only extracted once per JVM lifecycle. */
private val extractedResourcesCache = ConcurrentHashMap<String, Path>()

/**
 * Resolves a shipped artifact to a readable local file: [path] itself when it exists on disk (local builds, Bazel runfiles, tests), or a
 * temporary copy extracted from the classpath resource with the same relative path (the standalone CLI jar, which packages the artifacts as
 * resources).
 */
internal fun resolveLocalPathOrExtractFromClasspath(path: Path): Path {
  if (path.toFile().exists()) {
    // Use direct filesystem path when running from local builds, Bazel runfiles, or tests.
    return path
  }
  val resourcePath = "/" + path.invariantSeparatorsPathString
  return extractedResourcesCache.computeIfAbsent(resourcePath) { pathStr ->
    val stream =
      InjectionManager::class.java.getResourceAsStream(pathStr)
        ?: throw IllegalStateException("File not found on filesystem at $path nor in classpath resources at $pathStr")
    val prefix = "ui_inspector_${path.nameWithoutExtension}_"
    val suffix = if (path.extension.isNotEmpty()) ".${path.extension}" else ".tmp"
    val tempFile = Files.createTempFile(prefix, suffix)
    tempFile.toFile().deleteOnExit()
    stream.use { input -> Files.copy(input, tempFile, StandardCopyOption.REPLACE_EXISTING) }
    tempFile
  }
}
