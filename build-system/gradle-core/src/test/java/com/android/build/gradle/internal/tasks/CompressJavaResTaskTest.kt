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

package com.android.build.gradle.internal.tasks

import com.android.build.gradle.internal.fixtures.FakeNoOpAnalyticsService
import com.android.testutils.truth.PathSubject.assertThat
import com.android.zipflinger.ZipArchive
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unit tests for [CompressJavaResTask]. */
class CompressJavaResTaskTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var task: CompressJavaResTask
  private lateinit var outputFile: File
  private lateinit var inputDir: File

  @Before
  fun setUp() {
    val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
    task = project.tasks.register("compressJavaRes", CompressJavaResTask::class.java).get()
    task.analyticsService.set(FakeNoOpAnalyticsService())

    inputDir = temporaryFolder.newFolder("input")
    outputFile = temporaryFolder.newFile("java_res.jar")
  }

  @Test
  fun testInputDirIsCompressed() {
    inputDir.resolve("foo.txt").apply { writeText("foo") }
    inputDir.resolve("bar/baz.txt").apply {
      parentFile.mkdirs()
      writeText("baz")
    }

    task.javaResFiles.from(inputDir)
    task.outputFile.set(outputFile)

    task.taskAction()

    assertThat(outputFile).exists()
    ZipArchive.listEntries(outputFile.toPath()).let { entries ->
      assertThat(entries.keys).containsExactly("foo.txt", "bar/baz.txt")
      assertThat(entries["foo.txt"]?.isCompressed).isTrue()
      assertThat(entries["bar/baz.txt"]?.isCompressed).isTrue()
    }
  }

  @Test
  fun testInputUncompressedJarIsCompressed() {
    val uncompressedJar = temporaryFolder.newFile("uncompressed.jar")
    ZipOutputStream(uncompressedJar.outputStream()).use { zos ->
      val entry = ZipEntry("entry.txt")
      entry.method = ZipEntry.STORED
      val content = "content".toByteArray()
      entry.size = content.size.toLong()
      val crc = java.util.zip.CRC32()
      crc.update(content)
      entry.crc = crc.value
      zos.putNextEntry(entry)
      zos.write(content)
      zos.closeEntry()
    }

    task.javaResFiles.from(task.archiveOperations.zipTree(uncompressedJar))
    task.outputFile.set(outputFile)

    task.taskAction()

    assertThat(outputFile).exists()
    ZipArchive.listEntries(outputFile.toPath()).let { entries ->
      assertThat(entries.keys).containsExactly("entry.txt")
      assertThat(entries["entry.txt"]?.isCompressed).isTrue()
    }
  }

  @Test
  fun testMixedJarAndDirInputs() {
    inputDir.resolve("file1.txt").writeText("content1")

    val jarFile = temporaryFolder.newFile("input.jar")
    ZipOutputStream(jarFile.outputStream()).use { zos ->
      val entry = ZipEntry("file2.txt")
      zos.putNextEntry(entry)
      zos.write("content2".toByteArray())
      zos.closeEntry()
    }

    task.javaResFiles.from(inputDir)
    task.javaResFiles.from(task.archiveOperations.zipTree(jarFile))
    task.outputFile.set(outputFile)

    task.taskAction()

    assertThat(outputFile).exists()
    ZipArchive.listEntries(outputFile.toPath()).let { entries ->
      assertThat(entries.keys).containsExactly("file1.txt", "file2.txt")
      assertThat(entries["file1.txt"]?.isCompressed).isTrue()
      assertThat(entries["file2.txt"]?.isCompressed).isTrue()
    }
  }

  @Test
  fun testEmptyInput() {
    task.javaResFiles.from(inputDir)
    task.outputFile.set(outputFile)

    task.taskAction()

    assertThat(outputFile).exists()
    ZipArchive.listEntries(outputFile.toPath()).let { entries -> assertThat(entries).isEmpty() }
  }
}
