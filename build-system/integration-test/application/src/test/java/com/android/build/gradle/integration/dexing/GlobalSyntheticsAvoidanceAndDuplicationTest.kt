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

package com.android.build.gradle.integration.dexing

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.options.IntegerOption
import com.android.build.gradle.options.OptionalBooleanOption
import com.android.testutils.apk.Apk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Integration and regression tests for global synthetics task avoidance and class duplication avoidance. */
class GlobalSyntheticsAvoidanceAndDuplicationTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication(":app") {
        HelloWorldAndroid.setupJava(files)
        android {
          defaultConfig.minSdk = 21
          compileOptions {
            sourceCompatibility = org.gradle.api.JavaVersion.VERSION_1_8
            targetCompatibility = org.gradle.api.JavaVersion.VERSION_1_8
          }
          dynamicFeatures += listOf(":feature")
        }
        dependencies { implementation(project(":lib")) }
      }

      androidLibrary(":lib") {
        HelloWorldAndroid.setupJava(files)
        android { defaultConfig.minSdk = 21 }
        files.add(
          "src/main/java/com/example/lib/VibrationEffectUsage.java",
          """
          package com.example.lib;

          public class VibrationEffectUsage {
              public void run() {
                  try {
                      android.os.VibrationEffect effect = android.os.VibrationEffect.createOneShot(100, 255);
                  } catch (Throwable e) {}
              }
          }
          """
            .trimIndent(),
        )
      }

      androidFeature(":feature") {
        HelloWorldAndroid.setupJava(files)
        android { defaultConfig.minSdk = 21 }
        dependencies { implementation(project(":app")) }
        files.add(
          "src/main/java/com/example/feature/FeatureClass.java",
          """
          package com.example.feature;

          public class FeatureClass {
              public void run() {
                  System.out.println("Feature Module");
              }
          }
          """
            .trimIndent(),
        )
      }
    }

  @Test
  fun testDuplicationAvoidance() {
    val build = rule.build
    build.executor.with(OptionalBooleanOption.ENABLE_API_MODELING_AND_GLOBAL_SYNTHETICS, true).run(":app:assembleDebug")

    val apkPath = build.androidApplication(":app").getApkLocationForCopy(ApkSelector.DEBUG)

    Apk(apkPath.toFile()).use { apk ->
      // Verify that the global synthetic Landroid/os/VibrationEffect;
      // is present in exactly 1 dex file (globals.dex) and not duplicated in library/app dexes.
      val containingDexes = apk.allDexes.filter { it.classes.keys.contains("Landroid/os/VibrationEffect;") }
      assertTrue("VibrationEffect stub should be present in the APK", containingDexes.isNotEmpty())
      assertTrue("VibrationEffect stub should reside in exactly 1 dex file (no duplication)", containingDexes.size == 1)
    }
  }

  @Test
  fun testTaskAvoidanceWithDynamicFeature() {
    val build = rule.build
    // Compiling the app module should NOT trigger feature module compile/dex tasks in Universal DEX path
    val result = build.executor.run(":app:assembleDebug")

    // The result should not contain feature compilation or dex builder tasks
    assertFalse(
      "Feature Java compile task should not execute when compiling base app module",
      result.tasks.contains(":feature:compileDebugJavaWithJavac"),
    )
    assertFalse(
      "Feature dex builder task should not execute when compiling base app module",
      result.tasks.contains(":feature:dexBuilderDebug"),
    )
  }

  @Test
  fun testMismatchScenarioWithInjectedBuildApi() {
    val build = rule.build
    build.androidApplication(":app").reconfigure { android { defaultConfig.minSdk = 19 } }
    build.androidLibrary(":lib").reconfigure { android { defaultConfig.minSdk = 19 } }
    build.androidFeature(":feature").reconfigure { android { defaultConfig.minSdk = 19 } }

    build.executor
      .with(IntegerOption.IDE_TARGET_DEVICE_API, 30)
      .with(OptionalBooleanOption.ENABLE_API_MODELING_AND_GLOBAL_SYNTHETICS, true)
      .run(":app:assembleDebug")

    val apkPath = build.androidApplication(":app").getApkLocationForCopy(ApkSelector.DEBUG.fromIntermediates())
    Apk(apkPath.toFile()).use { apk ->
      val containingDexes = apk.allDexes.filter { it.classes.keys.contains("Landroid/os/VibrationEffect;") }
      assertTrue("VibrationEffect stub should be present in the APK", containingDexes.isNotEmpty())
    }
  }
}
