/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.tools.render

import com.android.tools.res.ids.ResourceIdManager
import com.android.tools.res.ids.apk.ApkResourceIdManager
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.logging.Logger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RendererClassResolutionTest {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun testMapCompiledIdsToRClass() {
    val apkIdManager = ApkResourceIdManager()

    val classLoader = DummyRId::class.java.classLoader
    DummyRId.custom_text = 0

    // This should run without throwing any exceptions
    mapCompiledIdsToRClass(
      className = DummyRId::class.java.name,
      pkg = "com.android.tools.render",
      classLoader = classLoader,
      apkIdManager = apkIdManager
    )

    // It remains 0 because the apkIdManager is empty
    assertTrue(DummyRId.custom_text == 0)
  }

  @Test
  fun testResolveClassesFromDirectory() {
    val dir = tempFolder.newFolder("dummy_r_classes")
    val packageDir = File(dir, "com/example").apply { mkdirs() }
    File(packageDir, "R.class").writeText("dummy bytecode")
    File(packageDir, "R\$id.class").writeText("dummy inner bytecode")

    val parsedBytecodes = mutableListOf<ByteArray>()
    val parser = object : ResourceIdManager.RClassParser {
      override fun parseBytecode(rClass: ByteArray, rClassProvider: (String) -> ByteArray) {
        parsedBytecodes.add(rClass)
      }
      override fun parseUsingReflection(rClass: Class<*>) {}
    }

    val rClassPackages = mutableSetOf<String>()
    val apkIdManager = ApkResourceIdManager()

    resolveClassesFromDirectory(
      file = dir,
      classLoader = this::class.java.classLoader,
      parser = parser,
      apkIdManager = apkIdManager,
      rClassPackages = rClassPackages,
      logger = Logger.getLogger("Test")
    )

    assertTrue(parsedBytecodes.isNotEmpty())
  }

  @Test
  fun testResolveClassesFromJar() {
    val jarFile = tempFolder.newFile("dummy.jar")
    ZipOutputStream(FileOutputStream(jarFile)).use { zos ->
      zos.putNextEntry(ZipEntry("com/example/R.class"))
      zos.write("dummy bytecode".toByteArray())
      zos.closeEntry()

      zos.putNextEntry(ZipEntry("com/example/R\$id.class"))
      zos.write("dummy inner bytecode".toByteArray())
      zos.closeEntry()
    }

    val parsedBytecodes = mutableListOf<ByteArray>()
    val parser = object : ResourceIdManager.RClassParser {
      override fun parseBytecode(rClass: ByteArray, rClassProvider: (String) -> ByteArray) {
        parsedBytecodes.add(rClass)
      }
      override fun parseUsingReflection(rClass: Class<*>) {}
    }

    val rClassPackages = mutableSetOf<String>()
    val apkIdManager = ApkResourceIdManager()

    resolveClassesFromJar(
      file = jarFile,
      path = jarFile.absolutePath,
      classLoader = this::class.java.classLoader,
      parser = parser,
      apkIdManager = apkIdManager,
      rClassPackages = rClassPackages,
      logger = Logger.getLogger("Test")
    )

    assertTrue(parsedBytecodes.isNotEmpty())
    assertTrue(rClassPackages.contains("com.example"))
  }
}

class DummyRId {
  companion object {
    @JvmField
    var custom_text: Int = 0
  }
}