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

package com.android.build.gradle.integration.common.output

import java.nio.file.Path
import java.util.Enumeration
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.io.path.extension

/**
 * Computes the sum of the compressed size for all the entries in a zip
 */
class ApkContentSize {
    companion object {
        fun computeContent(path: Path): Long {
            var contentsSize = 0L

            val extension = path.extension
            return if (extension == "apk" || extension == "aar" || extension == "zip" || extension == "aab") {
                ZipFile(path.toFile()).use { zipFile ->
                    val entries: Enumeration<*> = zipFile.entries()
                    while (entries.hasMoreElements()) {
                        val zipEntry = entries.nextElement() as ZipEntry
                        contentsSize += zipEntry.compressedSize
                    }
                }

                contentsSize
            } else {
                throw RuntimeException("computeContent() only works on zip archives: $path")
            }
        }
    }
}
