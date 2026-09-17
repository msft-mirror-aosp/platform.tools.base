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

package com.android.build.gradle.integration.connected.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Connected tests for Private Compute Core instrumented test option when running on an emulator less than the minimum required support
 * API 37.
 */
@RunWith(Parameterized::class)
class PrivateComputeCoreConnectedUnsupportedApiTest(val runWithBuiltInPlatform: Boolean) {

  companion object {
    @ClassRule @JvmField val EMULATOR = getEmulator()
    private const val REPORT_PATH = "app/build/reports/androidTests/connected/debug/com.example.android.kotlin.html"

    @JvmStatic @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}") fun parameters(): Array<Any> = arrayOf(true, false)
  }

  @get:Rule
  val rule =
    GradleRule.configure().from {
      androidApplication {
        android {
          namespace = "com.example.android.kotlin"
          defaultConfig {
            minSdk = 21
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          testOptions { instrumentInPrivateComputeCore = true }
          dependencies {
            androidTestImplementation("androidx.test:core:1.4.0-alpha06")
            androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
            androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
          }
        }
        kotlin { jvmToolchain(17) }
        files {
          add(
            "src/androidTest/java/com/example/android/kotlin/ExampleInstrumentedTest.kt",
            // language=kotlin
            """
            package com.example.android.kotlin

            import androidx.test.ext.junit.runners.AndroidJUnit4
            import org.junit.Test
            import org.junit.runner.RunWith

            @RunWith(AndroidJUnit4::class)
            class ExampleInstrumentedTest {
                @Test
                fun useAppContext() {
                }
            }
            """
              .trimIndent(),
          )
        }
      }
      gradleProperties { add(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform) }
    }

  @Test
  fun pccEnabledOnUnsupportedDevice() {
    val executor = rule.build.executor

    val result = executor.run(":app:connectedAndroidTest")

    val reportFile =
      if (runWithBuiltInPlatform) {
        rule.build.directory.resolve("app/build/reports/androidTests/connected/debug/index.html")
      } else {
        rule.build.directory.resolve("app/build/reports/androidTests/connected/debug/com.example.android.kotlin.html")
      }
    assertThat(reportFile).exists()

    // Since the emulator has an API level < 37, we expect a warning to be printed.
    result.assertOutputContains("Private Compute Core instrumentation is enabled but device")
  }
}
