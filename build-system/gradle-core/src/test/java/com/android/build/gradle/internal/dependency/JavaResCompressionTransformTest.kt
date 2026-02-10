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

package com.android.build.gradle.internal.dependency

import com.android.build.gradle.internal.fixtures.FakeGradleProvider
import com.android.build.gradle.internal.fixtures.FakeGradleRegularFile
import com.android.testutils.TestInputsGenerator
import com.android.testutils.assertThrows
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.Project
import org.gradle.api.artifacts.transform.TransformOutputs
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JavaResCompressionTransformTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var testInputJavaResDir: File

  private lateinit var testUncompressedJar: File

  private lateinit var project: Project

  private lateinit var outputDir: File

  @Before
  fun setup() {
    val javaResFileContents = mapOf("entry1" to "// Java Res entry 1")
    testInputJavaResDir = temporaryFolder.newFolder("java_res_dir").apply { resolve("java_res_file_1.txt").writeText("// Java Res File") }
    testUncompressedJar =
      temporaryFolder.newFolder().resolve("java_res.jar").apply {
        ZipOutputStream(outputStream()).use { zos ->
          for ((entryName, entryContent) in javaResFileContents) {
            val entry = ZipEntry(entryName)
            entry.method = ZipEntry.STORED
            val entryContentBytes = entryContent.toByteArray()
            entry.size = entryContentBytes.size.toLong()
            val crc = java.util.zip.CRC32()
            crc.update(entryContentBytes)
            entry.crc = crc.value
            zos.putNextEntry(entry)
            zos.write(entryContentBytes)
            zos.closeEntry()
          }
        }
      }
    outputDir = temporaryFolder.newFolder()
    project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
  }

  @Test
  fun checkCompressJavaResJarEntry() {
    val transform = getTestableCompressJavaResTransform(testUncompressedJar)
    val transformOutputs = FakeTransformOutputs(outputDir)
    transform.transform(transformOutputs)
    assertThat(transformOutputs.outputFiles).hasSize(1)
    val compressedJavaResJar = transformOutputs.outputFiles.single()
    ZipFile(compressedJavaResJar).use {
      val entry = it.getEntry("entry1")
      assertThat(entry).isNotNull()
      assertThat(entry.method).isEqualTo(ZipEntry.DEFLATED)
    }
  }

  @Test
  fun checkCompressJavaResJar() {
    ZipFile(testUncompressedJar).use {
      val entry = it.getEntry("entry1")
      assertThat(entry.method).isEqualTo(ZipEntry.STORED)
    }

    val transform = getTestableCompressJavaResTransform(testUncompressedJar)
    val transformOutputs = FakeTransformOutputs(outputDir)
    transform.transform(transformOutputs)
    assertThat(transformOutputs.outputFiles).hasSize(1)
    val compressedJavaResJar = transformOutputs.outputFiles.single()

    ZipFile(compressedJavaResJar).use {
      val entry = it.getEntry("entry1")
      assertThat(entry.method).isEqualTo(ZipEntry.DEFLATED)
    }
  }

  @Test
  fun checkCompressJavaResDirResultsInFailure() {
    val transform = getTestableCompressJavaResTransform(testInputJavaResDir)
    val transformOutputs = FakeTransformOutputs(outputDir)
    assertThrows<Exception> { transform.transform(transformOutputs) }
  }

  @Test
  fun checkCompressJavaResFromAar() {
    val aarDir = temporaryFolder.newFolder("aar_dir")
    val jarsDir = aarDir.resolve("jars")
    jarsDir.mkdir()
    jarsDir.resolve("classes.jar").apply {
      TestInputsGenerator.writeJarWithTextEntries(
        this.toPath(),
        mutableMapOf("entry1" to "content from classes.jar", "entry2" to "conflict content from classes.jar"),
      )
    }

    val libsDir = jarsDir.resolve("libs")
    libsDir.mkdir()
    libsDir.resolve("lib1.jar").apply {
      TestInputsGenerator.writeJarWithTextEntries(
        this.toPath(),
        mutableMapOf("entry2" to "content from lib1.jar", "conflict" to "content from lib1.jar"),
      )
    }
    val transform = getTestableCompressJavaResForExploadedAarTransform(aarDir)
    val transformOutputs = FakeTransformOutputs(outputDir)
    transform.transform(transformOutputs)
    val compressedJavaResJar = transformOutputs.outputFiles.single()

    ZipFile(compressedJavaResJar).use {
      assertThat(it.getEntry("entry1")).isNotNull()
      assertThat(it.getEntry("conflict")).isNotNull()
      val conflictEntry = it.getEntry("entry2")
      assertThat(conflictEntry).isNotNull()
      it.getInputStream(conflictEntry).use { input ->
        assertThat(input.bufferedReader().readText()).isEqualTo("conflict content from classes.jar")
      }
    }
  }

  private fun getTestableCompressJavaResTransform(input: File): JavaResCompressionTransform {
    return object : JavaResCompressionTransform() {
      override fun getParameters(): GenericTransformParameters = error("Parameters not used")

      override fun transform(outputs: TransformOutputs) {
        super.transform(outputs)
      }

      override val inputArtifact: Provider<FileSystemLocation>
        get() = FakeGradleProvider(FakeGradleRegularFile(input))
    }
  }

  private fun getTestableCompressJavaResForExploadedAarTransform(aarDir: File): JavaResCompressionFromExplodedAarTransform {
    return object : JavaResCompressionFromExplodedAarTransform() {
      override fun getParameters(): GenericTransformParameters = error("Parameters not used")

      override val inputArtifact: Provider<FileSystemLocation>
        get() = FakeGradleProvider(FakeGradleRegularFile(aarDir))
    }
  }
}
