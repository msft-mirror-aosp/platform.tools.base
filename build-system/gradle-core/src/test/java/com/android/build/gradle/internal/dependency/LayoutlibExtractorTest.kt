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

import com.android.build.gradle.internal.fixtures.FakeConfigurableFileCollection
import com.android.build.gradle.internal.fixtures.FakeGradleProperty
import com.android.build.gradle.internal.fixtures.FakeGradleProvider
import com.android.build.gradle.internal.fixtures.FakeGradleRegularFile
import com.android.build.gradle.internal.fixtures.FakeTransformOutputs
import com.android.testutils.TestInputsGenerator
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LayoutlibExtractorTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun testExtractsData() {
    val inputJar = createLayoutlibRuntimeJar()
    val frameworkResJar = createFrameworkResourcesJar()
    val transformOutputs = FakeTransformOutputs(tmp)

    createTransform(inputJar, listOf(frameworkResJar)).transform(transformOutputs)

    val extractedDir = transformOutputs.outputDirectory
    assertThat(File(extractedDir, "data/fonts/Font.ttf").exists()).isTrue()
    assertThat(File(extractedDir, "data/platform_data.txt").exists()).isTrue()
  }

  @Test
  fun testFrameworkResourcesAreAddedToTheOutput() {
    val inputJar = createLayoutlibRuntimeJar()
    val frameworkResJar = createFrameworkResourcesJar()
    val transformOutputs = FakeTransformOutputs(tmp)

    createTransform(inputJar, listOf(frameworkResJar)).transform(transformOutputs)

    val extractedResJar = File(transformOutputs.outputDirectory, "data/framework_res.jar")
    assertThat(extractedResJar.exists()).isTrue()
    assertThat(extractedResJar.readBytes()).isEqualTo(frameworkResJar.readBytes())
  }

  @Test
  fun testFailsWithoutFrameworkResources() {
    val inputJar = createLayoutlibRuntimeJar()
    val transformOutputs = FakeTransformOutputs(tmp)

    val failure = runCatching { createTransform(inputJar, frameworkResJars = emptyList()).transform(transformOutputs) }.exceptionOrNull()

    assertThat(failure).isNotNull()
    assertThat(failure!!).hasMessageThat().contains("layoutlib-resources")
  }

  @Test
  fun testFailsWithSeveralFrameworkResources() {
    val inputJar = createLayoutlibRuntimeJar()
    val frameworkResJars = listOf(createFrameworkResourcesJar(), createFrameworkResourcesJar("other-layoutlib-resources.jar"))
    val transformOutputs = FakeTransformOutputs(tmp)

    val failure = runCatching { createTransform(inputJar, frameworkResJars).transform(transformOutputs) }.exceptionOrNull()

    assertThat(failure).isNotNull()
    assertThat(failure!!).hasMessageThat().contains("layoutlib-resources")
    // The distribution is large, so the check has to happen before anything is extracted.
    assertThat(transformOutputs.outputFiles).isEmpty()
  }

  private fun createLayoutlibRuntimeJar(): File =
    tmp.newFile("layoutlib-runtime.jar").also {
      TestInputsGenerator.writeJarWithEmptyEntries(it.toPath(), listOf("data/fonts/Font.ttf", "data/platform_data.txt"))
    }

  private fun createFrameworkResourcesJar(name: String = "layoutlib-resources.jar"): File =
    tmp.newFile(name).also { TestInputsGenerator.writeJarWithEmptyEntries(it.toPath(), listOf("res/values.xml")) }

  private fun createTransform(inputJar: File, frameworkResJars: List<File>): LayoutlibExtractor =
    object : LayoutlibExtractor() {
      override val layoutlibDistributionArtifact: Provider<FileSystemLocation> = FakeGradleProvider(FakeGradleRegularFile(inputJar))

      override fun getParameters(): Parameters =
        object : Parameters() {
          override val projectName: Property<String> = FakeGradleProperty("project")

          override val frameworkResources: ConfigurableFileCollection = FakeConfigurableFileCollection(*frameworkResJars.toTypedArray())
        }
    }
}
