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

package com.android.build.gradle.integration.connected.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test

/** Connected tests with built-in Kotlin disabled. */
class UtpBuiltInKotlinDisabledTest {

  companion object {
    @ClassRule @JvmField val EMULATOR = getEmulator()
    private const val TEST_REPORT = "app/build/reports/androidTests/connected/debug/com.example.android.kotlin.html"
  }

  @get:Rule
  val rule =
    GradleRule.configure().from {
      androidApplication {
        // Intentionally applying the legacy Kotlin plugin to verify compatibility
        // when built-in Kotlin is disabled.
        applyPlugin(PluginType.KOTLIN_ANDROID)
        android {
          namespace = "com.example.android.kotlin"
          defaultConfig {
            minSdk = 21
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          dependencies {
            androidTestImplementation("androidx.test:core:1.4.0-alpha06")
            androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
            androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
          }
        }
        // Explicitly setting jvmToolchain to ensure consistent JVM target compatibility
        // between Java and Kotlin tasks when using the legacy Kotlin plugin.
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
      gradleProperties {
        // Intentionally opting out of AGP 9.0 default behavior to test legacy Kotlin support.
        add(BooleanOption.BUILT_IN_KOTLIN, false)
        add(BooleanOption.USE_NEW_DSL, false)
      }
    }

  @Test
  fun androidTestWithBuiltInKotlinDisabled() {
    val executor = rule.build.executor

    executor.run(":app:connectedAndroidTest")

    assertThat(rule.build.directory.resolve(TEST_REPORT)).exists()
  }
}
