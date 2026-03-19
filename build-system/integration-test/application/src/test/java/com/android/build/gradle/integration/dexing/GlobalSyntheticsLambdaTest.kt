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
import com.android.testutils.apk.Apk
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Verifies that the Universal D8 Global Synthetics DEX is generated and packaged in Debug builds for minSdk >= 21.
 *
 * This ensures the Layout Inspector can resolve lambda source locations and legacy stubs are present.
 */
class GlobalSyntheticsLambdaTest {

  @get:Rule
  val rule =
    GradleRule.from {
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
  fun testUniversalSyntheticsPresent() {
    val build = rule.build
    build.executor.run("assembleDebug")

    val apkPath = build.androidApplication().getApkLocationForCopy(ApkSelector.DEBUG)

    Apk(apkPath.toFile()).use { apk ->
      val classes = apk.allDexes.flatMap { it.classes.keys }.toSet()

      assertTrue("LambdaMethod annotation missing!", classes.contains("Lcom/android/tools/r8/annotations/LambdaMethod;"))
      assertTrue("Record stub missing!", classes.contains("Ljava/lang/Record;"))
      assertTrue("HardwarePropertiesManager stub missing!", classes.contains("Landroid/os/HardwarePropertiesManager;"))
    }
  }
}
