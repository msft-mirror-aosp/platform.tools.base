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
import com.android.build.gradle.internal.services.Aapt2DaemonBuildService
import com.android.build.gradle.internal.services.Aapt2Input
import com.android.build.gradle.options.SyncOptions
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.InvalidUserDataException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class AarResourcesCompilerTransformTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun validPackageCreatesExpectedOutputDirectory() {
    val explodedAar = temporaryFolder.newFolder("valid-aar")
    File(explodedAar, SdkConstants.FN_ANDROID_MANIFEST_XML)
      .writeText("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.mylibrary" />""")

    val aapt2Dir = temporaryFolder.newFolder("aapt2-bin")
    File(aapt2Dir, SdkConstants.FN_AAPT2).createNewFile()

    val daemonService =
      object : Aapt2DaemonBuildService() {
        override fun getParameters(): Parameters =
          object : Parameters() {
            override val errorFormatMode: Property<SyncOptions.ErrorFormatMode> =
              FakeGradleProperty(SyncOptions.ErrorFormatMode.HUMAN_READABLE)
          }
      }
    val binaryCollection = mock(ConfigurableFileCollection::class.java)
    `when`(binaryCollection.singleFile).thenReturn(aapt2Dir)
    val aapt2Input = mock(Aapt2Input::class.java)
    `when`(aapt2Input.aapt2DaemonBuildService).thenReturn(FakeGradleProperty(daemonService))
    `when`(aapt2Input.version).thenReturn(FakeGradleProperty("test-aapt2-version"))
    `when`(aapt2Input.binaryDirectory).thenReturn(binaryCollection)

    val transformOutputs = FakeTransformOutputs(temporaryFolder)
    try {
      getTestTransform(explodedAar, aapt2Input).transform(transformOutputs)
    } finally {
      daemonService.close()
    }

    assertThat(transformOutputs.outputDirectory.name).isEqualTo("com.example.mylibrary")
    assertThat(transformOutputs.outputDirectory.isDirectory).isTrue()
  }

  @Test
  fun invalidPackagesAreRejectedBeforeDirectoryCreationOrAapt2() {
    val outsideDir = temporaryFolder.newFolder("outside")
    val absoluteTarget = File(outsideDir, "escaped").absolutePath
    val invalidManifests =
      listOf(
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="a&#10;-A&#10;/tmp/evil&#10;z" />""",
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="x&#10;--trace-folder&#10;/tmp/evil&#10;y" />""",
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="../../outside/escaped" />""",
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="..\..\outside\escaped" />""",
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package=".." />""",
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$absoluteTarget" />""",
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="" />""",
        """<manifest xmlns:android="http://schemas.android.com/apk/res/android" />""",
      )

    for ((index, manifestXml) in invalidManifests.withIndex()) {
      val explodedAar = temporaryFolder.newFolder("evil-aar-$index")
      File(explodedAar, SdkConstants.FN_ANDROID_MANIFEST_XML).writeText(manifestXml)

      val aapt2Input = mock(Aapt2Input::class.java)
      val transformOutputs = FakeTransformOutputs(temporaryFolder)
      val ex =
        assertThrows(InvalidUserDataException::class.java) {
          getTestTransform(explodedAar, aapt2Input).transform(transformOutputs)
        }
      assertThat(ex.message).contains("Invalid package name")
      assertThat(transformOutputs.outputFiles).isEmpty()
      assertThat(File(outsideDir, "escaped").exists()).isFalse()
      verifyNoInteractions(aapt2Input)
    }
  }

  private fun getTestTransform(
    explodedAar: File,
    aapt2Input: Aapt2Input,
  ): AarResourcesCompilerTransform {
    return object : AarResourcesCompilerTransform() {
      override val primaryInput: Provider<FileSystemLocation> = FakeGradleProvider(FakeGradleRegularFile(explodedAar))

      override fun getParameters(): Parameters =
        object : Parameters {
          override val aapt2: Aapt2Input = aapt2Input
          override val gradleUserHomePath: Property<String> = FakeGradleProperty(temporaryFolder.root.absolutePath)
          override val projectName: Property<String> = FakeGradleProperty("")
        }
    }
  }
}
