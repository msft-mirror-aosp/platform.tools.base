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

import com.android.template.engine.TemplateMessageSink.Severity
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission

/** Interface to save template files to disk after transformation */
internal interface TemplateFileStorage {
  val destinationPath: Path

  /** Check if destination directory exists, throws an exception if not */
  fun checkDestinationDirectoryIsEmpty()

  fun createDestinationDirectory()

  /** Saves the given [templateFile] to the appropriate location. */
  fun saveFile(templateFile: TemplateFile)
}

internal class DefaultFileStorage(private val messageSink: TemplateMessageSink, private val destinationPathProvider: () -> Path) :
  TemplateFileStorage {

  override val destinationPath by lazy { destinationPathProvider() }

  override fun checkDestinationDirectoryIsEmpty() {
    if (Files.exists(destinationPath)) {
      if (!Files.isDirectory(destinationPath)) {
        throw IOException("Path '$destinationPath' exists but is not a directory")
      }
      val isNotEmpty = Files.list(destinationPath).use { it.findAny().isPresent }
      if (isNotEmpty) {
        throw IOException("Directory '$destinationPath' is not empty")
      }
    }
  }

  override fun createDestinationDirectory() {
    messageSink.message(Severity.Verbose) { "Creating destination directory $destinationPath" }
    Files.createDirectories(destinationPath)
  }

  override fun saveFile(templateFile: TemplateFile) {
    val targetFile = destinationPath.resolve(templateFile.relativePath)

    // Ensure parent directories exist
    Files.createDirectories(targetFile.parent)

    // In case it's just a directory
    if (!Files.isDirectory(targetFile)) {
      messageSink.message(Severity.Verbose) { "Saving destination file '$targetFile' (${templateFile.content.size} byte(s))" }
      Files.write(targetFile, templateFile.content)

      // Make gradlew executable
      if ((templateFile.relativePath == "gradlew") || templateFile.relativePath.endsWith("/gradlew")) {
        if (targetFile.fileSystem.supportedFileAttributeViews().contains("posix")) {
          messageSink.message(Severity.Verbose) { "Setting execution bit for file '$targetFile'" }
          val perms = Files.getPosixFilePermissions(targetFile).toMutableSet()
          perms.add(PosixFilePermission.OWNER_EXECUTE)
          Files.setPosixFilePermissions(targetFile, perms)
        }
      }
    }
  }
}

internal class DryRunFileStorage(private val messageSink: TemplateMessageSink, destinationPathProvider: () -> Path) : TemplateFileStorage {
  override val destinationPath by lazy { destinationPathProvider() }

  override fun checkDestinationDirectoryIsEmpty() {
    if (Files.exists(destinationPath)) {
      throw IOException("Directory (or file) '$destinationPath' already exists")
    }
  }

  override fun createDestinationDirectory() {
    messageSink.message(Severity.Verbose) { "DryRun: Would create destination directory '$destinationPath'" }
  }

  override fun saveFile(templateFile: TemplateFile) {
    val targetFile = destinationPath.resolve(templateFile.relativePath)

    messageSink.message(Severity.Verbose) { "DryRun: Would create file '$targetFile' (${templateFile.content.size} byte(s))" }
  }
}
