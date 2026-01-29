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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.testutils.apk.Apk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AppAndLibNoKotlinStdlibTest {

  @get:Rule
  val rule =
    GradleRule.configure().from {
      androidLibrary(":lib") {
        android {
          namespace = "com.android.tests.testprojecttest.lib"
          enableKotlin = false
        }
        files.add(
          "src/main/java/com/android/tests/testprojecttest/lib/LibActivity.java",
          """
          package com.android.tests.testprojecttest.lib;
          import android.app.Activity;
          public class LibActivity extends Activity {}
          """
            .trimIndent(),
        )
      }
      androidApplication(":app") {
        android {
          namespace = "com.android.tests.testprojecttest.app"
          enableKotlin = false
        }
        dependencies { implementation(project(":lib")) }
      }
    }

  @Test
  fun `ensure kotlin stdlib is not in the APK`() {
    rule.build.executor.run("app:assembleDebug")

    val appProject = rule.build.androidApplication(":app")
    val apkPath = appProject.getApkLocationForCopy(ApkSelector.DEBUG)

    Apk(apkPath.toFile()).use { apk ->
      apk.allDexes.forEach { dex ->
        dex.classes.keys.forEach { className ->
          if (className.startsWith("Lkotlin/")) {
            throw AssertionError("Found kotlin class: $className")
          }
        }
      }
    }
  }
}
