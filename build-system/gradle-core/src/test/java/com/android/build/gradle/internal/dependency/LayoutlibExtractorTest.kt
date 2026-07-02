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

import com.android.build.gradle.internal.fixtures.FakeGenericTransformParameters
import com.android.build.gradle.internal.fixtures.FakeGradleProvider
import com.android.build.gradle.internal.fixtures.FakeGradleRegularFile
import com.android.build.gradle.internal.fixtures.FakeTransformOutputs
import com.android.testutils.TestInputsGenerator
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Provider
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LayoutlibExtractorTest {

  @get:Rule val tmp = TemporaryFolder()

  @Test
  fun testExtractsData() {
    val inputJar = tmp.newFile("layoutlib-runtime.jar")
    TestInputsGenerator.writeJarWithEmptyEntries(
      inputJar.toPath(),
      listOf("data/fonts/Font.ttf", "data/platform_data.txt", "data/framework_res.jar"),
    )

    val resourcesJar = tmp.newFile("layoutlib-resources.jar")
    val fileCollectionMock = mock(FileCollection::class.java)
    `when`(fileCollectionMock.files).thenReturn(setOf(resourcesJar))

    val transformOutputs = FakeTransformOutputs(tmp)

    val transform =
      object : LayoutlibExtractor() {
        override val layoutlibDistributionArtifact: Provider<FileSystemLocation> = FakeGradleProvider(FakeGradleRegularFile(inputJar))
        override val artifactDependencies: FileCollection = fileCollectionMock

        override fun getParameters(): GenericTransformParameters {
          return FakeGenericTransformParameters("project_name")
        }
      }

    transform.transform(transformOutputs)

    val extractedDir = transformOutputs.outputDirectory

    assertThat(File(extractedDir, "data/fonts/Font.ttf").exists()).isTrue()
    assertThat(File(extractedDir, "data/platform_data.txt").exists()).isTrue()
    assertThat(File(extractedDir, "data/framework_res.jar").exists()).isTrue()
  }
}
