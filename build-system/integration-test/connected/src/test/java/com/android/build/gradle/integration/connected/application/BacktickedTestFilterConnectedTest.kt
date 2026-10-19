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
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Regression test for `class` filters containing whitespace in Kotlin backticked method names.
 *
 * A filter selecting a single method whose name contains whitespace has the form `pkg.Class#method with spaces`.
 * `AmInstrumentCommandBuilder` used to append `-e` values to the `adb shell` command line unquoted, so the device shell split the argument
 * on whitespace and the runner received only the prefix before the first space — matching nothing and running zero tests.
 *
 * DEX version 040 (`minSdk 30`) is required to support whitespace in method names.
 *
 * Run on both execution platforms: UTP currently wraps android-test-engine, so both build the `am instrument` command the same way and must
 * filter identically.
 */
@RunWith(Parameterized::class)
class BacktickedTestFilterConnectedTest(private val runWithBuiltInPlatform: Boolean) {

  companion object {
    @JvmField @ClassRule val emulator: ExternalResource = getEmulator()

    @JvmStatic
    @Parameterized.Parameters(name = "builtInTestPlatform={0}")
    fun executionPlatforms(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

    private const val PKG = "com.example.backticked"
    private const val TEST_CLASS = "$PKG.BacktickedNameTest"
    private const val RESULTS_DIR = "build/outputs/androidTest-results/connected/debug"
  }

  @get:Rule
  val rule = GradleRule.from {
    gradleProperties { add(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform) }

    androidApplication(":app") {
      android {
        namespace = PKG
        compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
        defaultConfig {
          applicationId = PKG
          minSdk = 30
          targetSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
      }

      dependencies {
        androidTestImplementation("junit:junit:4.13.2")
        androidTestImplementation("androidx.test:core:1.4.0-alpha06")
        androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
        androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
      }

      files {
        add(
          "src/androidTest/java/com/example/backticked/BacktickedNameTest.kt",
          // language=kotlin
          """
          package com.example.backticked

          import androidx.test.ext.junit.runners.AndroidJUnit4
          import org.junit.Assert.assertTrue
          import org.junit.Test
          import org.junit.runner.RunWith

          @RunWith(AndroidJUnit4::class)
          class BacktickedNameTest {
              @Test
              fun `Clear contacts on device`() {
                  assertTrue(true)
              }

              @Test
              fun otherTest() {
                  assertTrue(true)
              }
          }
          """
            .trimIndent(),
        )
      }
    }
  }

  @Test
  fun runSingleTestWithWhitespaceInMethodName() {
    rule.build.executor
      .withArgument("-Pandroid.testInstrumentationRunnerArguments.class=$TEST_CLASS#Clear contacts on device")
      .run(":app:connectedDebugAndroidTest")

    val executed = executedTestCases()

    assertThat(executed).containsExactly("Clear contacts on device")
  }

  /** Returns the names of the test cases recorded in the connected test result XML. */
  private fun executedTestCases(): List<String> {
    val resultsDir = rule.build.androidApplication(":app").resolve(RESULTS_DIR).toFile()
    assertThat(resultsDir.exists()).named("test results directory $resultsDir").isTrue()

    val resultFiles = resultsDir.walkTopDown().filter { it.name.startsWith("TEST-") && it.extension == "xml" }.toList()
    assertThat(resultFiles).named("TEST-*.xml files in $resultsDir").isNotEmpty()

    return resultFiles.flatMap { file -> TEST_CASE_NAME.findAll(file.readText()).map { it.groupValues[1] }.toList() }
  }
}

private val TEST_CASE_NAME = Regex("""<testcase name="([^"]*)"""")
