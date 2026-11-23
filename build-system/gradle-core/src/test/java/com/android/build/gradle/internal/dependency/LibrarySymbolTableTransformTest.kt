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

import com.android.SdkConstants
import com.android.build.gradle.internal.fixtures.FakeGradleProperty
import com.android.build.gradle.internal.fixtures.FakeGradleProvider
import com.android.build.gradle.internal.fixtures.FakeGradleRegularFile
import com.android.build.gradle.internal.fixtures.FakeTransformOutputs
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.UncheckedIOException
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LibrarySymbolTableTransformTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun validPackageWritesSymbolList() {
    val explodedAar = temporaryFolder.newFolder("valid-aar")
    File(explodedAar, SdkConstants.FN_ANDROID_MANIFEST_XML)
      .writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.mylibrary" />""")
    File(explodedAar, SdkConstants.FN_RESOURCE_TEXT).writeText("int string app_name 0x7f010001\n")

    val transformOutputs = FakeTransformOutputs(temporaryFolder)
    getTestTransform(explodedAar).transform(transformOutputs)

    assertThat(transformOutputs.outputFile.name).isEqualTo("com.example.mylibrary-r.txt")
    assertThat(transformOutputs.outputFile.readLines()).containsExactly("com.example.mylibrary", "string app_name").inOrder()
  }

  @Test
  fun missingManifestProducesNoOutput() {
    val explodedAar = temporaryFolder.newFolder("no-manifest-aar")
    val transformOutputs = FakeTransformOutputs(temporaryFolder)

    getTestTransform(explodedAar).transform(transformOutputs)

    assertThat(transformOutputs.outputFiles).isEmpty()
  }

  @Test
  fun pathTraversalPackageIsRejectedBeforeFileWrite() {
    val outsideDir = temporaryFolder.newFolder("outside")
    val targetPrefix = File(outsideDir, "escaped").absolutePath
    val invalidPackages =
      listOf(
        "../../outside/escaped",
        targetPrefix,
        "..\\..\\outside\\escaped",
        "com.example.lib\n/tmp/evil",
        "..",
      )

    for ((index, badPackage) in invalidPackages.withIndex()) {
      val explodedAar = temporaryFolder.newFolder("evil-aar-$index")
      val xmlEscaped = badPackage.replace("\n", "&#10;")
      File(explodedAar, SdkConstants.FN_ANDROID_MANIFEST_XML)
        .writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$xmlEscaped" />""")
      File(explodedAar, SdkConstants.FN_RESOURCE_TEXT).writeText("int string app_name 0x7f010001\n")

      val transformOutputs = FakeTransformOutputs(temporaryFolder)
      val exception =
        assertThrows(UncheckedIOException::class.java) {
          getTestTransform(explodedAar).transform(transformOutputs)
        }
      assertThat(exception.message).contains("Invalid package name")
      assertThat(transformOutputs.outputFiles).isEmpty()
      assertThat(File(outsideDir, "escaped-r.txt").exists()).isFalse()
    }
  }

  private fun getTestTransform(explodedAar: File): LibrarySymbolTableTransform {
    return object : LibrarySymbolTableTransform() {
      override fun getInputArtifact(): Provider<FileSystemLocation> = FakeGradleProvider(FakeGradleRegularFile(explodedAar))

      override fun getParameters(): GenericTransformParameters =
        object : GenericTransformParameters {
          override val projectName: Property<String> = FakeGradleProperty("")
        }
    }
  }
}
