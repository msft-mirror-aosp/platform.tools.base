/*
 * Copyright (C) 2024 The Android Open Source Project
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
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.apk.Apk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies that the Universal D8 Global Synthetics DEX is generated and packaged in Debug builds for minSdk >= 21.
 *
 * This ensures the Layout Inspector can resolve lambda source locations and legacy stubs are present.
 */
class UniversalGlobalSyntheticsTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication {
      HelloWorldAndroid.setupJava(files)
      android {
        defaultConfig.minSdk = 21
        compileOptions {
          sourceCompatibility = org.gradle.api.JavaVersion.VERSION_1_8
          targetCompatibility = org.gradle.api.JavaVersion.VERSION_1_8
        }
      }
    }
  }

  @Test
  fun testUniversalSyntheticsPresent_Java8_Default() {
    val build = rule.build
    build.executor.run("assembleDebug")

    val apkPath = build.androidApplication().getApkLocationForCopy(ApkSelector.DEBUG)

    Apk(apkPath.toFile()).use { apk ->
      val classes = apk.allDexes.flatMap { it.classes.keys }.toSet()

      assertTrue("LambdaMethod annotation missing!", classes.contains("Lcom/android/tools/r8/annotations/LambdaMethod;"))
      assertTrue("RecordTag stub missing!", classes.contains("Lcom/android/tools/r8/RecordTag;"))
      assertTrue("HardwarePropertiesManager stub missing!", classes.contains("Landroid/os/HardwarePropertiesManager;"))
    }
  }

  @Test
  fun testUniversalSyntheticsAbsent_Java8_FlagDisabled() {
    val build = rule.build
    build.executor.with(BooleanOption.ENABLE_GLOBAL_SYNTHETICS_FOR_ALL_DEBUG_BUILDS, false).run("clean", "assembleDebug")

    val apkPath = build.androidApplication().getApkLocationForCopy(ApkSelector.DEBUG)

    Apk(apkPath.toFile()).use { apk ->
      val classes = apk.allDexes.flatMap { it.classes.keys }.toSet()

      // For Java 8, disabling the flag should completely disable global synthetics
      assertFalse("LambdaMethod annotation should be missing!", classes.contains("Lcom/android/tools/r8/annotations/LambdaMethod;"))
      assertFalse("RecordTag stub should be missing!", classes.contains("Lcom/android/tools/r8/RecordTag;"))
      assertFalse("HardwarePropertiesManager stub should be missing!", classes.contains("Landroid/os/HardwarePropertiesManager;"))
    }
  }

  @Test
  fun testUniversalSyntheticsPresent_Java17_FlagDisabled() {
    val build = rule.build

    // Modify the app's build.gradle to use Java 17
    build.androidApplication().reconfigure {
      android {
        compileOptions {
          sourceCompatibility = org.gradle.api.JavaVersion.VERSION_17
          targetCompatibility = org.gradle.api.JavaVersion.VERSION_17
        }
      }
    }

    // For Java 17, global synthetics are forced ON to support Records, overriding the flag.
    // This explicitly reproduces and verifies the P0 bug fix (b/504996348).
    build.executor.with(BooleanOption.ENABLE_GLOBAL_SYNTHETICS_FOR_ALL_DEBUG_BUILDS, false).run("clean", "assembleDebug")

    val apkPath = build.androidApplication().getApkLocationForCopy(ApkSelector.DEBUG)

    Apk(apkPath.toFile()).use { apk ->
      val classes = apk.allDexes.flatMap { it.classes.keys }.toSet()

      assertTrue("LambdaMethod annotation missing!", classes.contains("Lcom/android/tools/r8/annotations/LambdaMethod;"))
      assertTrue("RecordTag stub missing!", classes.contains("Lcom/android/tools/r8/RecordTag;"))
      assertTrue("HardwarePropertiesManager stub missing!", classes.contains("Landroid/os/HardwarePropertiesManager;"))
    }
  }
}
