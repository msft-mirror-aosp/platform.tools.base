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
 * Connected integration test verifying that tests annotated with `@Ignore` or failing JUnit assumptions are reported as skipped/ignored in
 * the generated JUnit XML report, rather than as failures.
 */
@RunWith(Parameterized::class)
class IgnoredTestConnectedTest(private val runWithBuiltInPlatform: Boolean) {

  companion object {
    @JvmField @ClassRule val emulator: ExternalResource = getEmulator()

    @JvmStatic
    @Parameterized.Parameters(name = "builtInTestPlatform={0}")
    fun executionPlatforms(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

    private const val PKG = "com.example.ignoredtest"
    private const val RESULTS_DIR = "build/outputs/androidTest-results/connected"
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
        androidTestImplementation("junit:junit:4.13.2")
        androidTestImplementation("androidx.test:core:1.4.0-alpha06")
        androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
        androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
      }

      files {
        add(
          "src/androidTest/java/com/example/ignoredtest/SampleDeviceTest.kt",
          // language=kotlin
          """
          package com.example.ignoredtest

          import org.junit.Assert.assertTrue
          import org.junit.Assume.assumeTrue
          import org.junit.Ignore
          import org.junit.Test

          class SampleDeviceTest {

              @Test
              fun testPassing() {
                  assertTrue(true)
              }

              @Ignore("This test is ignored")
              @Test
              fun testIgnored() {
                  assertTrue(false)
              }

              @Test
              fun testAssumptionFailure() {
                  assumeTrue("Assumption failed intentionally", false)
              }
          }
          """
            .trimIndent(),
        )
      }
    }
  }

  @Test
  fun testIgnoredAndAssumptionFailureReportedCorrectlyInXml() {
    rule.build.executor.run(":app:connectedDebugAndroidTest")

    val resultsDir = rule.build.androidApplication(":app").resolve(RESULTS_DIR).toFile()
    assertThat(resultsDir.exists()).named("test results directory $resultsDir").isTrue()

    val resultFiles = resultsDir.walkTopDown().filter { it.name.startsWith("TEST-") && it.extension == "xml" }.toList()
    assertThat(resultFiles).named("TEST-*.xml files in $resultsDir").isNotEmpty()

    val xmlContent = resultFiles.first().readText()

    val passingXml = getTestCaseXml(xmlContent, "testPassing")
    assertThat(passingXml).named("testPassing in:\n$xmlContent").doesNotContain("<failure")
    assertThat(passingXml).named("testPassing in:\n$xmlContent").doesNotContain("<skipped")

    val ignoredXml = getTestCaseXml(xmlContent, "testIgnored")
    assertThat(ignoredXml).named("testIgnored in:\n$xmlContent").contains("<skipped")
    assertThat(ignoredXml).named("testIgnored in:\n$xmlContent").doesNotContain("<failure")

    val assumptionXml = getTestCaseXml(xmlContent, "testAssumptionFailure")
    assertThat(assumptionXml).named("testAssumptionFailure in:\n$xmlContent").contains("<skipped")
    assertThat(assumptionXml).named("testAssumptionFailure in:\n$xmlContent").doesNotContain("<failure")

    assertThat(xmlContent).named("XML content in:\n$xmlContent").contains("""failures="0"""")
    assertThat(xmlContent).named("XML content in:\n$xmlContent").contains("""skipped="2"""")
  }

  private fun getTestCaseXml(xmlContent: String, testName: String): String {
    val selfClosingRegex = Regex("""<testcase\b[^>]*name="$testName"[^>]*/>""")
    val selfClosingMatch = selfClosingRegex.find(xmlContent)
    if (selfClosingMatch != null) return selfClosingMatch.value

    val blockRegex = Regex("""<testcase\b[^>]*name="$testName"[^/>]*>(.*?)</testcase>""", RegexOption.DOT_MATCHES_ALL)
    val blockMatch = blockRegex.find(xmlContent)
    if (blockMatch != null) return blockMatch.value

    throw AssertionError("testcase with name '$testName' not found in:\n$xmlContent")
  }
}
