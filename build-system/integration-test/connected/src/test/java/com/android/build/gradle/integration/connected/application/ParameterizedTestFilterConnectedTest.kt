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
 * Regression test for `tests_regex` filters containing shell metacharacters.
 *
 * A filter selecting a single method of a `@RunWith(Parameterized::class)` class has to match the `[...]` suffix, e.g.
 * `pkg.Class.method\[.*\]`. `AmInstrumentCommandBuilder` used to append `-e` values to the `adb shell` command line unquoted, so the device
 * shell stripped the backslashes and the runner received `method[.*]` — a character class matching nothing. The build then succeeded having
 * run zero tests.
 *
 * Run on both execution platforms: UTP currently wraps android-test-engine, so both build the `am instrument` command the same way and must
 * filter identically.
 */
@RunWith(Parameterized::class)
class ParameterizedTestFilterConnectedTest(private val runWithBuiltInPlatform: Boolean) {

  companion object {
    @JvmField @ClassRule val emulator: ExternalResource = getEmulator()

    @JvmStatic
    @Parameterized.Parameters(name = "builtInTestPlatform={0}")
    fun executionPlatforms(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

    private const val PKG = "com.example.paramfilter"
    private const val TEST_CLASS = "$PKG.ParameterizedFilterTest"
    private const val RESULTS_DIR = "build/outputs/androidTest-results/connected/debug"

    /** The parameter names of the instrumented test, i.e. its `[...]` suffixes at runtime. */
    private val PARAM_NAMES = listOf("alpha", "beta", "gamma")
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
          minSdk = 24
          targetSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
      }

      dependencies {
        // androidx.test:runner is required explicitly: without AndroidJUnitRunner in the test APK
        // `am instrument` reports no tests rather than failing, silently invalidating this test.
        androidTestImplementation("junit:junit:4.13.2")
        androidTestImplementation("androidx.test:core:1.4.0-alpha06")
        androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
        androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
      }

      files {
        add(
          "src/androidTest/java/com/example/paramfilter/ParameterizedFilterTest.kt",
          // language=kotlin
          """
          package com.example.paramfilter

          import org.junit.Assert.assertNotNull
          import org.junit.Test
          import org.junit.runner.RunWith
          import org.junit.runners.Parameterized

          /** Non-numeric parameter names, so tests are reported as checkValue[alpha] etc. */
          @RunWith(Parameterized::class)
          class ParameterizedFilterTest(private val value: String) {

              companion object {
                  @JvmStatic
                  @Parameterized.Parameters(name = "{0}")
                  fun parameters(): List<String> = listOf("alpha", "beta", "gamma")
              }

              @Test
              fun checkValue() {
                  assertNotNull(value)
              }

              @Test
              fun otherTest() {
                  assertNotNull(value)
              }
          }
          """
            .trimIndent(),
        )
      }
    }
  }

  /** A `tests_regex` containing escaped brackets must reach the instrumentation runner intact. */
  @Test
  fun testsRegexWithEscapedBracketsRunsAllParameterizations() {
    rule.build.executor
      .withArgument("""-Pandroid.testInstrumentationRunnerArguments.tests_regex=$TEST_CLASS.checkValue\[.*\]""")
      .run(":app:connectedDebugAndroidTest")

    val executed = executedTestCases()

    assertThat(executed).containsExactlyElementsIn(PARAM_NAMES.map { "checkValue[$it]" })
  }

  /** A `tests_regex` with no shell metacharacters must also match every parameterization. */
  @Test
  fun testsRegexWithoutBracketsRunsAllParameterizations() {
    rule.build.executor
      .withArgument("-Pandroid.testInstrumentationRunnerArguments.tests_regex=$TEST_CLASS.checkValue")
      .run(":app:connectedDebugAndroidTest")

    val executed = executedTestCases()

    assertThat(executed).containsExactlyElementsIn(PARAM_NAMES.map { "checkValue[$it]" })
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
