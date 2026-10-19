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

package com.android.build.gradle.internal.res

import com.android.SdkConstants
import com.android.build.gradle.internal.dependency.GenericTransformParameters
import com.android.build.gradle.internal.fixtures.FakeGradleProperty
import com.android.build.gradle.internal.fixtures.FakeGradleProvider
import com.android.build.gradle.internal.fixtures.FakeGradleRegularFile
import com.android.build.gradle.internal.fixtures.FakeTransformOutputs
import com.android.build.gradle.options.StringOption
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unit tests for [Aapt2FromMaven]. */
class Aapt2FromMavenTest {

  @Rule @JvmField val tmp = TemporaryFolder()

  @Test
  fun testClassifierResolutionLinuxArm64() {
    val classifier =
      Aapt2FromMaven.getClassifier(
        stringOption = { null },
        currentPlatform = SdkConstants.PLATFORM_LINUX,
        osArch = "aarch64",
      )
    assertThat(classifier).isEqualTo("linux_arm64")

    val classifierArm64 =
      Aapt2FromMaven.getClassifier(
        stringOption = { null },
        currentPlatform = SdkConstants.PLATFORM_LINUX,
        osArch = "arm64",
      )
    assertThat(classifierArm64).isEqualTo("linux_arm64")
  }

  @Test
  fun testClassifierResolutionLinuxX86_64() {
    val classifier =
      Aapt2FromMaven.getClassifier(
        stringOption = { null },
        currentPlatform = SdkConstants.PLATFORM_LINUX,
        osArch = "x86_64",
      )
    assertThat(classifier).isEqualTo("linux")
  }

  @Test
  fun testClassifierOverrideLinuxArm64() {
    val classifier =
      Aapt2FromMaven.getClassifier(
        stringOption = { option ->
          if (option == StringOption.AAPT2_FROM_MAVEN_PLATFORM_OVERRIDE) "linux_arm64" else null
        },
        currentPlatform = SdkConstants.PLATFORM_WINDOWS,
      )
    assertThat(classifier).isEqualTo("linux_arm64")
  }

  @Test
  fun testClassifierOverrideInvalid() {
    val exception =
      assertThrows(IllegalStateException::class.java) {
        Aapt2FromMaven.getClassifier(
          stringOption = { option ->
            if (option == StringOption.AAPT2_FROM_MAVEN_PLATFORM_OVERRIDE) "unsupported_platform" else null
          }
        )
      }
    assertThat(exception).hasMessageThat().contains("Unknown platform 'unsupported_platform'")
  }

  /** Verifies that directory traversal entries (Zip-Slip) are skipped during extraction. */
  @Test
  fun testExtractorFiltersZipSlip() {
    val zipFile =
      createZip(
        "aapt2" to "aapt2 binary",
        "../evil.txt" to "escaped",
        "..\\evil2.txt" to "escaped2",
        "sub/../../evil3.txt" to "escaped3",
        "sub/..\\..\\evil4.txt" to "escaped4",
      )

    val transformOutputs = FakeTransformOutputs(tmp)
    createTransform(zipFile).transform(transformOutputs)

    val outputDirectory = transformOutputs.outputDirectory
    assertThat(
        outputDirectory
          .walk()
          .filter { !it.isDirectory }
          .map { FileUtils.toSystemIndependentPath(it.relativeTo(outputDirectory).path) }
          .toList()
      )
      .containsExactly("aapt2")

    assertThat(outputDirectory.resolve("aapt2").readText()).isEqualTo("aapt2 binary")
  }

  private fun createZip(vararg entries: Pair<String, String>): File {
    val zipFile = tmp.newFile()
    ZipOutputStream(FileOutputStream(zipFile)).use {
      for (entry in entries) {
        it.putNextEntry(ZipEntry(entry.first))
        it.write(entry.second.toByteArray())
        it.closeEntry()
      }
    }
    return zipFile
  }

  private fun createTransform(primaryInput: File): Aapt2FromMaven.Companion.Aapt2Extractor {
    return object : Aapt2FromMaven.Companion.Aapt2Extractor() {
      override val inputArtifact: Provider<FileSystemLocation> = FakeGradleProvider(FakeGradleRegularFile(primaryInput))

      override fun getParameters(): GenericTransformParameters {
        return object : GenericTransformParameters {
          override val projectName: Property<String> = FakeGradleProperty("")
        }
      }
    }
  }
}
