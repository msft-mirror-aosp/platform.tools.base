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
package com.android.builder.files

import com.google.common.io.ByteStreams
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ZipTestTool {
  @Throws(Exception::class)
  fun createZipFile(zip: String, vararg args: String?): File {
    val names = ArrayList<String>()
    val contents = ArrayList<String?>()
    var i = 0
    while (i < args.size) {
      names.add(args[i]!!)
      contents.add(args[i + 1])
      i += 2
    }
    val bytes: ByteArray = createZipFile(names, contents)
    val file = File(zip)
    Files.write(file.toPath(), bytes)
    return file
  }

  @Throws(Exception::class)
  private fun createZipFile(names: ArrayList<String>, contents: ArrayList<String?>): ByteArray {
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zipOut ->
      var i = 0
      while (i < names.size) {
        val name = names[i]
        val ix = name.indexOf('!')
        if (ix != -1) {
          val prefix = name.substring(0, ix + 1)
          val innerNames = ArrayList<String>()
          val innerContents = ArrayList<String?>()
          i--
          while (i + 1 < names.size && names[i + 1].startsWith(prefix)) {
            i++
            innerNames.add(names[i].substring(ix + 1))
            innerContents.add(contents[i])
          }
          zipOut.putNextEntry(ZipEntry(name.substring(0, ix)))
          val bytes = createZipFile(innerNames, innerContents)
          ByteStreams.copy(ByteArrayInputStream(bytes), zipOut)
        } else {
          zipOut.putNextEntry(ZipEntry(name))
          val bytes = contents.get(i)!!.toByteArray(StandardCharsets.UTF_8)
          ByteStreams.copy(ByteArrayInputStream(bytes), zipOut)
        }
        i++
      }
    }
    return out.toByteArray()
  }
}
