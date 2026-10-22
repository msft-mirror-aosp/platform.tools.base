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
 * A directory with the files the host pushes to the device, for tools that don't bundle these files in their classpath, e.g. the Android
 * CLI, which downloads them in a pack. `//tools/base/ui-inspector/core:device_artifacts_pkg_files` packages them in its layout:
 * ```
 * libarttooling.jar
 * lib_ui_inspector_payload.jar
 * view-inspector.jar
 * <abi>/libarttooling_agent.so, for each supported ABI, e.g. arm64-v8a
 * ```
 */
internal class DeviceArtifactsDirectory(directory: Path) {
  // Absolute, so that a missing file is reported as such instead of being looked up in the classpath resources.
  private val dir: Path = directory.toAbsolutePath()

  /** The ART Tooling native agent for the device ABI [abi]. */
  fun agentPath(abi: String): Path = dir.resolve(abi).resolve(AGENT_FILE_NAME)

  val libraryDexPath: Path = dir.resolve(LIBRARY_DEX_FILE_NAME)
  val payloadJarPath: Path = dir.resolve(PAYLOAD_JAR_FILE_NAME)
  val viewInspectorJarPath: Path = dir.resolve(VIEW_INSPECTOR_JAR_NAME)

  /** Throws if the directory does not exist, which would otherwise be reported as a missing file, e.g. as a missing agent for the ABI. */
  fun checkExists() {
    check(Files.isDirectory(dir)) { "UI Inspector files directory not found or not a directory: $dir" }
  }
}

/**
 * Resolves a shipped artifact to a readable local file: [path] itself when it exists on disk (local builds, Bazel runfiles, tests, a
 * [DeviceArtifactsDirectory]), or a temporary copy extracted from the classpath resource with the same relative path (the standalone CLI
 * jar, which packages the artifacts as resources).
 */
internal fun resolveLocalPathOrExtractFromClasspath(path: Path): Path {
  if (Files.exists(path)) {
    // Use direct filesystem path when running from local builds, Bazel runfiles, or tests.
    return path
  }
  // Classpath resources have relative paths: an absolute one names a file on disk, e.g. in a DeviceArtifactsDirectory.
  check(!path.isAbsolute) { "UI Inspector file not found: $path" }
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
