/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.integration.packaging

import com.android.build.gradle.integration.common.fixture.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.runner.FilterableParameterized
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.sdklib.AndroidVersion.VersionCodes.O
import com.android.sdklib.AndroidVersion.VersionCodes.P
import java.util.zip.ZipEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * sourceManifestvalue and expectedMergedManifestValue refer to the value of android:useEmbeddedDex in the source and merged manifests,
 * respectively. If null, no such attribute is written or expected in the manifests.
 *
 * useLegacyPackaging refers to the value of PackagingOptions.dex.useLegacyPackaging specified via the DSL. If null, no such value is
 * specified.
 */
@RunWith(FilterableParameterized::class)
class UseEmbeddedDexPackagingTest(
  private val sourceManifestValue: Boolean?,
  private val minSdk: Int,
  private val useLegacyPackaging: Boolean?,
  private val expectedMergedManifestValue: Boolean?,
  private val expectedCompression: Int,
) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "useEmbeddedDex_{0}_minSdk_{1}_useLegacyPackaging_{2}")
    fun parameters() =
      listOf(
        arrayOf(true, O, true, true, ZipEntry.STORED),
        arrayOf(true, O, false, true, ZipEntry.STORED),
        arrayOf(true, O, null, true, ZipEntry.STORED),
        arrayOf(true, P, true, true, ZipEntry.STORED),
        arrayOf(true, P, false, true, ZipEntry.STORED),
        arrayOf(true, P, null, true, ZipEntry.STORED),
        arrayOf(false, O, true, false, ZipEntry.DEFLATED),
        arrayOf(false, O, false, false, ZipEntry.STORED),
        arrayOf(false, O, null, false, ZipEntry.DEFLATED),
        arrayOf(false, P, true, false, ZipEntry.DEFLATED),
        arrayOf(false, P, false, false, ZipEntry.STORED),
        arrayOf(false, P, null, false, ZipEntry.STORED),
        arrayOf(null, O, true, null, ZipEntry.DEFLATED),
        arrayOf(null, O, false, null, ZipEntry.STORED),
        arrayOf(null, O, null, null, ZipEntry.DEFLATED),
        arrayOf(null, P, true, null, ZipEntry.DEFLATED),
        arrayOf(null, P, false, null, ZipEntry.STORED),
        arrayOf(null, P, null, null, ZipEntry.STORED),
      )
  }

  private val useEmbeddedAttribute =
    when (sourceManifestValue) {
      null -> ""
      false -> "android:useEmbeddedDex=\"false\""
      true -> "android:useEmbeddedDex=\"true\""
    }

  private val useLegacyPackagingString =
    when (useLegacyPackaging) {
      true -> "android.packagingOptions.dex.useLegacyPackaging = true"
      false -> "android.packagingOptions.dex.useLegacyPackaging = false"
      null -> ""
    }

  @get:Rule
  val project =
    GradleTestProject.builder()
      .fromTestApp(
        MinimalSubProject.app("com.test")
          .withFile(
            "build.gradle",
            """
                        apply plugin: 'com.android.application'
                        android {
                            namespace = "com.test"
                            compileSdk = ${GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION}
                            defaultConfig {
                                minSdk = $minSdk
                            }
                        }
                        $useLegacyPackagingString
                    """
              .trimIndent(),
          )
          .withFile(
            "src/main/AndroidManifest.xml",
            """
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                            <application $useEmbeddedAttribute/>
                        </manifest>
                    """
              .trimIndent(),
          )
      )
      .create()

  @Test
  fun testDexIsPackagedCorrectly() {
    val resolvedUseLegacyPackaging = useLegacyPackaging ?: (minSdk < P)

    // The build should fail if `android:useEmbeddedDex` in the manifest
    // contradicts the resolved packaging choice.
    val expectFailure = sourceManifestValue != null && sourceManifestValue == resolvedUseLegacyPackaging
    val expectedSuggestion = "Please remove android:useEmbeddedDex from your AndroidManifest.xml"

    if (expectFailure) {
      val result = project.executor().expectFailure().run("assembleDebug")
      assertThat(result.stderr).contains(expectedSuggestion)
    } else {
      project.executor().run("assembleDebug").stdout.use {
        if (sourceManifestValue != null) {
          // If the manifest setting is redundant (matches the resolved value),
          // the build should pass but show a warning about the deprecated attribute.
          assertThat(it).contains(expectedSuggestion)
        } else {
          // If the deprecated manifest attribute isn't used, ensure no warning is shown.
          assertThat(it).doesNotContain(expectedSuggestion)
        }
      }

      project.assertApk(ApkSelector.DEBUG) {
        // Verify the merged manifest contains the correct `useEmbeddedDex` value, or none at all.
        manifestAsNodes().node("manifest").node("application").apply {
          if (expectedMergedManifestValue == null) {
            containsExactlyAttributesAndValues(
              "http://schemas.android.com/apk/res/android:debuggable=true",
              "http://schemas.android.com/apk/res/android:extractNativeLibs=false",
            )
          } else {
            containsExactlyAttributesAndValues(
              "http://schemas.android.com/apk/res/android:debuggable=true",
              "http://schemas.android.com/apk/res/android:extractNativeLibs=false",
              "http://schemas.android.com/apk/res/android:useEmbeddedDex=$expectedMergedManifestValue",
            )
          }
        }

        // Verify the DEX file has the expected compression method.
        zipEntry("classes.dex").hasCompressionMethod(expectedCompression)
      }
    }
  }
}
