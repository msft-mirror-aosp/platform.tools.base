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

package com.android.tools.screenshot.differ

import com.android.tools.screenshot.ScreenshotImageInvalidException
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import javax.imageio.ImageIO

/**
 * Reads screenshot image files, turning decoding failures into actionable error messages.
 *
 * Reference screenshots are commonly stored in Git LFS. When Git LFS is not installed, or its objects have not been fetched, the reference
 * images in the working tree are small text pointer files instead of real images. Decoding them fails, and without extra context the
 * resulting "Cannot read reference image file" error is hard to act on. This reader detects that case and explains how to fix it.
 */
internal object ImageFileReader {

  /** Description used in error messages for the newly rendered preview image. */
  const val PREVIEW_IMAGE = "preview image file"

  /** Description used in error messages for the golden/reference image. */
  const val REFERENCE_IMAGE = "reference image file"

  /** Prefix of the first line of a Git LFS pointer file, see https://github.com/git-lfs/git-lfs/blob/main/docs/spec.md. */
  private const val GIT_LFS_POINTER_PREFIX = "version https://git-lfs.github.com/spec/"

  /** Git LFS pointer files are tiny text files; anything larger cannot be a pointer. */
  private const val MAX_GIT_LFS_POINTER_SIZE = 1024L

  private const val GIT_LFS_HELP =
    " The file is a Git LFS pointer, not an image. This usually means Git LFS is not installed or" +
      " the images have not been downloaded. Install Git LFS, then run" +
      " 'git lfs install' and 'git lfs pull' in the repository and run the task again."

  /**
   * Decodes [imageFile] into a [BufferedImage].
   *
   * [imageDescription] is used in the error message, see [PREVIEW_IMAGE] and [REFERENCE_IMAGE].
   *
   * @throws ScreenshotImageInvalidException if the file cannot be decoded as an image.
   */
  fun read(imageFile: File, imageDescription: String, projectRoot: File): BufferedImage {
    val image =
      try {
        ImageIO.read(imageFile)
      } catch (e: IOException) {
        null
      }
    if (image != null) {
      return image
    }
    val message = buildString {
      append("Cannot read $imageDescription (${imageFile.relativeTo(projectRoot).path}).")
      if (isGitLfsPointer(imageFile)) {
        append(GIT_LFS_HELP)
      }
    }
    throw ScreenshotImageInvalidException(message)
  }

  /** Returns true if [file] contains a Git LFS pointer instead of the actual file content. */
  private fun isGitLfsPointer(file: File): Boolean {
    if (!file.isFile || file.length() > MAX_GIT_LFS_POINTER_SIZE) {
      return false
    }
    return try {
      // The Git LFS spec requires the version line to come first, so the first line is enough.
      file.bufferedReader().use { it.readLine() }?.startsWith(GIT_LFS_POINTER_PREFIX) == true
    } catch (e: IOException) {
      false
    }
  }
}
