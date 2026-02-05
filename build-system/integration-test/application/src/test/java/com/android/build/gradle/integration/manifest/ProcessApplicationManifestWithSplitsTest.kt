/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.manifest

import com.android.build.VariantOutput
import com.android.build.gradle.api.ApkVariantOutput
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption
import com.android.testutils.truth.PathSubject.assertThat
import kotlin.test.assertTrue
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ProcessApplicationManifestWithSplitsTest(private val abi: String, private val expectedVersion: Int) {
  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android {
          defaultConfig {
            minSdk = 33
            versionCode = 1
          }
          splits {
            // Configures multiple APKs based on ABI.
            abi {
              // Enables building multiple APKs per ABI.
              isEnable = true

              // By default all ABIs are included, so use reset() and include to specify that you
              // only
              // want APKs for x86 and x86_64.

              // Resets the list of ABIs for Gradle to create APKs for to none.
              reset()

              // Specifies a list of ABIs for Gradle to create APKs for.
              include("x86_64", "x86", "arm64-v8a", "armeabi-v7a")

              // Specifies that you don't want to also generate a universal APK that includes all
              // ABIs.
              isUniversalApk = false
            }
          }
        }
        pluginCallbacks += MyAppCallback::class.java
      }
      gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
    }

  class MyAppCallback : LegacyApplicationCallback {
    override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
      val abiCodes = mapOf("armeabi-v7a" to 2, "arm64-v8a" to 3, "x86" to 8, "x86_64" to 9)

      extension.applicationVariants.all { variant ->
        variant.outputs.forEach { output ->
          // need to force this as the API does not return the right thing.
          output as ApkVariantOutput
          val baseAbiVersionCode = abiCodes[output.getFilter(VariantOutput.FilterType.ABI)]
          if (baseAbiVersionCode != null) {
            output.versionCodeOverride = baseAbiVersionCode * 1000 + variant.versionCode
          }
        }
      }
    }
  }

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}_{1}")
    fun parameters() =
      listOf(
        arrayOf("armeabi-v7a", 2001),
        arrayOf("arm64-v8a", 3001),
        arrayOf("arm64-v8a,armeabi-v7a", 3001),
        arrayOf("x86", 8001),
        arrayOf("x86_64", 9001),
      )
  }

  @Test
  fun testAppManifestContainsAbiSpecificVersionCode() {
    val build = rule.build
    val app = build.androidApplication()

    val result =
      build.executor.with(StringOption.IDE_BUILD_TARGET_ABI, abi).with(BooleanOption.ENABLE_LEGACY_API, true).run(":app:assembleDebug")
    assertTrue { result.failedTasks.isEmpty() }

    val manifestFile = app.intermediatesDir.resolve("merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml")
    assertThat(manifestFile).contains("android:versionCode=\"$expectedVersion\"")
  }
}
