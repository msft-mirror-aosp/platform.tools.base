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

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipInputStream

/** The name of the entry of a library's AAR that holds the library's inspector. */
internal const val INSPECTOR_JAR_ENTRY_NAME = "inspector.jar"

/**
 * Extracts the "inspector.jar" of the AAR [aar] to [destination]. The jar is written to a temporary file next to [destination] and then
 * moved there with [fileMover], so that a concurrent reader never sees a partial file. A failed move is ignored when [destination] exists
 * afterwards: another thread or process extracted the same jar first.
 *
 * @return [destination], or null when the AAR has no "inspector.jar".
 */
internal fun installInspectorJar(aar: File, destination: File, fileMover: (File, File) -> Unit = ::moveFileReplacing): File? {
  val destinationDir = destination.absoluteFile.parentFile
  Files.createDirectories(destinationDir.toPath())
  val tempInspectorJar = extractInspectorJar(aar, destinationDir) ?: return null
  try {
    try {
      fileMover(tempInspectorJar, destination)
    } catch (e: Exception) {
      if (!destination.isFile) {
        // If the destination file was successfully written by another thread, we can safely ignore the exception if the file now exists.
        throw e
      }
    }
    return destination
  } finally {
    tempInspectorJar.delete()
  }
}

/** Moves [source] to [destination], replacing it, atomically when the file system supports it. */
internal fun moveFileReplacing(source: File, destination: File) {
  try {
    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
  } catch (e: AtomicMoveNotSupportedException) {
    Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
  }
}

/** Extracts the nested "inspector.jar" from the [aarFile] to a temporary file in [destinationDir]. */
private fun extractInspectorJar(aarFile: File, destinationDir: File): File? {
  val prefix = "${aarFile.nameWithoutExtension}-extracted-"
  val inspectorJar = File.createTempFile(prefix, ".jar", destinationDir)

  try {
    ZipInputStream(aarFile.inputStream()).use { zipInputStream ->
      var entry = zipInputStream.nextEntry
      while (entry != null) {
        if (entry.name == INSPECTOR_JAR_ENTRY_NAME) {
          inspectorJar.outputStream().use { output -> zipInputStream.copyTo(output) }
          return inspectorJar
        }
        entry = zipInputStream.nextEntry
      }
    }
    inspectorJar.delete()
    return null
  } catch (t: Throwable) {
    inspectorJar.delete()
    throw t
  }
}
