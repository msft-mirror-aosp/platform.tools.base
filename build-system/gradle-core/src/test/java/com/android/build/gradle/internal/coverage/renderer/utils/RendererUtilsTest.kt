/* Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.internal.coverage.renderer.utils

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import org.gradle.api.file.DirectoryProperty
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RendererUtilsTest {

  @Rule @JvmField val tempFolder = TemporaryFolder()

  private lateinit var tempDir: File
  private lateinit var outputDir: DirectoryProperty

  @Before
  fun setUp() {
    tempDir = tempFolder.root
    val project = ProjectBuilder.builder().withProjectDir(tempDir).build()
    outputDir = project.objects.directoryProperty()
    outputDir.set(tempDir)
  }

  @Test
  fun `copyResource successfully copies a resource`() {
    val resourceName = "test-resource.txt"
    val expectedContent = "This is a test resource file."

    copyResource(resourceName, outputDir, RendererUtilsTest::class.java)

    val outputFile = File(tempDir, resourceName)
    assertThat(outputFile.exists()).isTrue()
    assertThat(outputFile.readText().trim()).isEqualTo(expectedContent)
  }

  @Test
  fun `copyResource throws IOException for non-existent resource`() {
    try {
      copyResource("non-existent-file.txt", outputDir, RendererUtilsTest::class.java)
      fail("Expected an IOException to be thrown, but nothing was thrown.")
    } catch (e: IOException) {
      assertThat(e.message).isEqualTo("Could not find resource 'non-existent-file.txt'.")
    }
  }

  @Test
  fun `copyResources copies multiple files`() {
    val resource1Name = "test-resource.txt"
    val resource2Name = "another-test-resource.txt"
    val resourceFiles = listOf(resource1Name, resource2Name)

    val expectedContent1 = "This is a test resource file."
    val expectedContent2 = "Another file."

    copyResources(outputDir, resourceFiles, RendererUtilsTest::class.java)

    val outputFile1 = File(tempDir, resource1Name)
    val outputFile2 = File(tempDir, resource2Name)

    assertThat(outputFile1.exists()).isTrue()
    assertThat(outputFile1.readText().trim()).isEqualTo(expectedContent1)

    assertThat(outputFile2.exists()).isTrue()
    assertThat(outputFile2.readText().trim()).isEqualTo(expectedContent2)
  }
}
