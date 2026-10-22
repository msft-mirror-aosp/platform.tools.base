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

package com.android.build.gradle.integration.application

import com.android.build.api.artifact.SingleArtifact
import com.android.build.gradle.integration.common.fixture.project.AndroidApplicationProject
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.testutils.TestUtils
import com.android.testutils.truth.PathSubject.assertThat
import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for b/575579722:
 *
 * Validates the behavior of `<processes>` and `<memory-budget>` manifest elements across AGP and AAPT2:
 * 1. AGP's manifest merger preserves process memory budgets and does not drop or corrupt them.
 * 2. When an application defines per-process memory budgets under `<processes>` but omits an application-level default `<memory-budget>`
 *    directly under `<application>`, AAPT2's ManifestFixer automatically injects `<memory-budget android:maxMb="1000000" />` as an
 *    application ceiling in the final compiled AndroidManifest.xml when targeting SDK <= 37.
 * 3. When an application explicitly declares an application-level `<memory-budget>`, AAPT2's ManifestFixer respects it and does not
 *    overwrite it with the 1000000 MB ceiling.
 */
class AaptProcessMemoryBudgetTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication {
      android {
        namespace = "com.example.budget"
        defaultConfig {
          minSdk = 35
          targetSdk = 35
        }
      }
      files {
        update("src/main/AndroidManifest.xml")
          .replaceWith(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <service android:name=".MyService" />
                    <!-- No default application-level memory-budget specified here -->
                    <processes>
                        <process android:process=":rcs">
                            <memory-budget android:maxMb="50" />
                        </process>
                    </processes>
                </application>
            </manifest>
            """
              .trimIndent()
          )
      }
    }
  }

  /**
   * Verifies that AGP's Manifest Merger preserves child `<process>` and `<memory-budget>` elements when no top-level application memory
   * budget is specified.
   */
  @Test
  fun testProcessMemoryBudgetRetainedInManifestMerge() {
    val build = rule.build
    val app = build.androidApplication()
    build.executor.run(":app:processDebugMainManifest")

    val mergedManifest = app.resolve(SingleArtifact.MERGED_MANIFEST).resolve("debug/processDebugMainManifest/AndroidManifest.xml")

    assertThat(mergedManifest).exists()
    assertThat(mergedManifest).contains("<process android:process=\":rcs\"")
    assertThat(mergedManifest).contains("<memory-budget android:maxMb=\"50\"")
    assertThat(mergedManifest).doesNotContain("1000000")
  }

  /**
   * Verifies that AAPT2's ManifestFixer automatically injects the baseline ceiling budget directly under `<application>` in the compiled
   * binary AndroidManifest.xml inside the APK.
   */
  @Test
  fun testAaptInjectsApplicationMemoryBudgetCeiling() {
    val build = rule.build
    val app = build.androidApplication()
    val mockFrameworkApk = getOrCreateMockFrameworkOverlay(app)
    val mockFrameworkApkPath = mockFrameworkApk.absolutePath.replace("\\", "\\\\")
    app.reconfigure {
      android {
        androidResources {
          additionalParameters.clear()
          additionalParameters += listOf("-I", mockFrameworkApkPath)
        }
      }
    }

    build.executor.run("assembleDebug")

    app.assertApk(ApkSelector.DEBUG) {
      manifestAsNodes().node("manifest").node("application").apply {
        // Verify that AAPT2 ManifestFixer injected the default ceiling budget
        node("memory-budget")
          .containsAttributeAndValue(
            "http://schemas.android.com/apk/res/android:maxMb",
            "1000000",
          )

        // Verify that the processes node exists
        containsNode("processes")
      }

      // Verify the original per-process budget under <processes> is preserved
      manifest().containsMatch("""E: process\s+A: [^\n]*:process=":\Qrcs\E"\s+E: memory-budget\s+A: [^\n]*:maxMb=50""")
    }
  }

  /**
   * Verifies that when an application explicitly declares its own application-level `<memory-budget android:maxMb="...">`, AAPT2's
   * ManifestFixer respects it and does NOT overwrite it with the 1000000 MB default ceiling.
   */
  @Test
  fun testAaptDoesNotOverwriteExistingApplicationMemoryBudget() {
    val build = rule.build
    val app = build.androidApplication()
    val mockFrameworkApk = getOrCreateMockFrameworkOverlay(app)
    val mockFrameworkApkPath = mockFrameworkApk.absolutePath.replace("\\", "\\\\")
    app.reconfigure {
      android {
        androidResources {
          additionalParameters.clear()
          additionalParameters += listOf("-I", mockFrameworkApkPath)
        }
      }
      files {
        update("src/main/AndroidManifest.xml")
          .replaceWith(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application>
                    <service android:name=".MyService" />
                    <!-- Explicit application-level memory-budget -->
                    <memory-budget android:maxMb="256" />
                    <processes>
                        <process android:process=":rcs">
                            <memory-budget android:maxMb="50" />
                        </process>
                    </processes>
                </application>
            </manifest>
            """
              .trimIndent()
          )
      }
    }

    build.executor.run("assembleDebug")

    app.assertApk(ApkSelector.DEBUG) {
      manifestAsNodes().node("manifest").node("application").apply {
        // Verify that AAPT2 preserved the explicit application-level budget
        node("memory-budget")
          .containsAttributeAndValue(
            "http://schemas.android.com/apk/res/android:maxMb",
            "256",
          )

        // Verify that the processes node exists
        containsNode("processes")
      }

      // Ensure the default 1000000 ceiling was NOT injected
      manifest().doesNotContain("1000000")

      // Verify the original per-process budget under <processes> is preserved
      manifest().containsMatch("""E: process\s+A: [^\n]*:process=":\Qrcs\E"\s+E: memory-budget\s+A: [^\n]*:maxMb=50""")
    }
  }

  /**
   * Provisions a lightweight framework-res overlay passed to AAPT2 via `-I` to supply the public `android:maxMb` framework attribute in the
   * absence of an SDK 37 QPR2 prebuilt.
   */
  private fun getOrCreateMockFrameworkOverlay(app: AndroidApplicationProject): File {
    val mockFrameworkDir = app.resolve("mock_framework").toFile()
    val mockFrameworkApk = mockFrameworkDir.resolve("framework-res.apk")
    if (mockFrameworkApk.exists()) {
      return mockFrameworkApk
    }
    mockFrameworkDir.mkdirs()
    val aapt2Path = TestUtils.getAapt2().toString()
    val valuesDir = mockFrameworkDir.resolve("res/values").also { it.mkdirs() }
    val publicXml =
      valuesDir.resolve("public.xml").apply {
        writeText(
          """
          <resources>
              <attr name="maxMb" format="integer" />
              <public type="attr" name="maxMb" id="0x010106ad" />
          </resources>
          """
            .trimIndent()
        )
      }
    val compiledZip = mockFrameworkDir.resolve("compiled.zip")
    val compileProc =
      ProcessBuilder(aapt2Path, "compile", publicXml.absolutePath, "-o", compiledZip.absolutePath).redirectErrorStream(true).start()
    try {
      val compileOutput = compileProc.inputStream.bufferedReader().use { it.readText() }
      val compileFinished = compileProc.waitFor(1, TimeUnit.MINUTES)
      check(compileFinished) { "AAPT2 compile timed out" }
      val compileExit = compileProc.exitValue()
      check(compileExit == 0) { "AAPT2 compile failed ($compileExit): $compileOutput" }
    } finally {
      compileProc.destroyForcibly()
    }

    val manifestAndroid =
      mockFrameworkDir.resolve("AndroidManifest_android.xml").apply {
        writeText("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"android\" />")
      }
    val linkProc =
      ProcessBuilder(
          aapt2Path,
          "link",
          compiledZip.absolutePath,
          "--manifest",
          manifestAndroid.absolutePath,
          "-x",
          "--auto-add-overlay",
          "-o",
          mockFrameworkApk.absolutePath,
        )
        .redirectErrorStream(true)
        .start()
    try {
      val linkOutput = linkProc.inputStream.bufferedReader().use { it.readText() }
      val linkFinished = linkProc.waitFor(1, TimeUnit.MINUTES)
      check(linkFinished) { "AAPT2 link timed out" }
      val linkExit = linkProc.exitValue()
      check(linkExit == 0) { "AAPT2 link failed ($linkExit): $linkOutput" }
    } finally {
      linkProc.destroyForcibly()
    }

    return mockFrameworkApk
  }
}
