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

import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import java.io.File
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/**
 * Integration test for [com.android.build.gradle.tasks.TestReportTask] and [com.android.build.gradle.tasks.TestResultsCollectionTask]
 * evaluating cross-module unit test reporting.
 */
class TestReportAggregationTest {

  companion object {
    @ClassRule @JvmField val emulator: ExternalResource = getEmulator()
  }

  @get:Rule
  val rule =
    GradleRule.fromProject("reportAggregation") {
      androidApplication(":app") {
        android {
          namespace = "com.example.app"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          installation { timeOutInMs = 30000 }
          defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
        }
        dependencies {
          implementation(project(":lib"))

          testImplementation("junit:junit:4.13.2")
          testImplementation("org.mockito:mockito-core:5.12.0")
          testImplementation("org.jdeferred:jdeferred-android-aar:1.2.3")
          testImplementation("commons-logging:commons-logging:1.1.1")

          androidTestImplementation("androidx.test:core:1.4.0-alpha06")
          androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
          androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
          androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
          androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
        }
        files.add(
          "src/testRelease/java/com/example/app/ReleaseTest.kt",
          """
          package com.example.app
          import org.junit.Test
          class ReleaseTest {
              @Test
              fun testRelease() {}
          }
          """
            .trimIndent(),
        )
      }
      androidLibrary(":lib") {
        android {
          namespace = "com.example.lib"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          installation { timeOutInMs = 30000 }
          defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
          dependencies {
            implementation(project(":lib2"))

            testImplementation("junit:junit:4.13.2")
            testImplementation("org.mockito:mockito-core:5.12.0")
            testImplementation("org.jdeferred:jdeferred-android-aar:1.2.3")
            testImplementation("commons-logging:commons-logging:1.1.1")

            androidTestImplementation("androidx.test:core:1.4.0-alpha06")
            androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
            androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
            androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
            androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
          }
        }
      }
      androidLibrary(":lib2") {
        android {
          namespace = "com.example.lib2"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          installation { timeOutInMs = 30000 }
          defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
          publishing { singleVariant("debug") }
          dependencies {
            testImplementation("junit:junit:4.13.2")
            testImplementation("org.mockito:mockito-core:5.12.0")
            testImplementation("org.jdeferred:jdeferred-android-aar:1.2.3")
            testImplementation("commons-logging:commons-logging:1.1.1")

            androidTestImplementation("androidx.test:core:1.4.0-alpha06")
            androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
            androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
            androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
            androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
          }
          files.add(
            "src/androidTest/java/com/example/lib2/FailingTest.kt",
            """
            package com.example.lib2
            import org.junit.Test
            import org.junit.Assert.fail
            class FailingTest {
                @Test
                fun testFailure() {
                    fail("This test is supposed to fail")
                }
            }
            """
              .trimIndent(),
          )
          files.add(
            "src/test/java/com/example/lib2/FailingTest.kt",
            """
            package com.example.lib2
            import org.junit.Test
            import org.junit.Assert.fail
            class FailingTest {
                @Test
                fun testFailure() {
                    fail("This test is supposed to fail")
                }
            }
            """
              .trimIndent(),
          )
        }
      }
      gradleProperties {
        // this is to test the multi-variant support for coverage reporting
        add(BooleanOption.ONLY_ENABLE_UNIT_TEST_BY_DEFAULT_FOR_THE_TESTED_BUILD_TYPE, false)
      }
    }

  @Test
  fun testCreateTestReportWithFailingTest() {
    // unit test is expected to fail if run separately
    rule.build.executor.expectFailure().run(":lib2:testDebugUnitTest")
    // connected test is expected to fail if run separately
    rule.build.executor.expectFailure().run(":lib2:connectedDebugAndroidTest")
    // check failing test case won't fail the build when running the test report task
    val result = rule.build.executor.run(":lib2:createTestReport")
    val libBuildDir = rule.build.androidLibrary(":lib2").buildDir.toFile()
    val outputDir = FileUtils.join(libBuildDir, "reports", "tests", "test-report")

    verifyHtmlReport(outputDir = outputDir, taskResult = result)
  }

  @Test
  fun testCreateTestReportIncludingAllVariants() {
    val result = rule.build.executor.run(":app:createTestReport")
    val appBuildDir = rule.build.androidApplication(":app").buildDir.toFile()
    val outputDir = FileUtils.join(appBuildDir, "reports", "tests", "test-report")

    assertThat(result.didWorkTasks.contains(":app:testDebugUnitTest")).isTrue()
    assertThat(result.didWorkTasks.contains(":app:testReleaseUnitTest")).isTrue()

    verifyHtmlReport(outputDir = outputDir, taskResult = result)
  }

  @Test
  fun testCreateAggregatedTestReport() {
    val result = rule.build.executor.run(":app:createAggregatedTestReport")
    val appBuildDir = rule.build.androidApplication(":app").buildDir.toFile()
    val outputDir = FileUtils.join(appBuildDir, "reports", "tests", "aggregated-test-report")

    assertThat(result.didWorkTasks.contains(":app:testDebugUnitTest")).isTrue()
    assertThat(result.didWorkTasks.contains(":app:connectedDebugAndroidTest")).isTrue()
    assertThat(result.didWorkTasks.contains(":lib:testDebugUnitTest")).isTrue()
    assertThat(result.didWorkTasks.contains(":lib:connectedDebugAndroidTest")).isTrue()
    assertThat(result.didWorkTasks.contains(":lib2:testDebugUnitTest")).isTrue()
    assertThat(result.didWorkTasks.contains(":lib2:connectedDebugAndroidTest")).isTrue()

    verifyHtmlReport(outputDir = outputDir, taskResult = result)
  }

  @Test
  fun testCreateTestReportLib() {
    val result = rule.build.executor.run(":lib:createTestReport")
    val libBuildDir = rule.build.androidLibrary(":lib").buildDir.toFile()
    assertThat(result.didWorkTasks.contains(":lib:testDebugUnitTest")).isTrue()
    assertThat(result.didWorkTasks.contains(":lib:connectedDebugAndroidTest")).isTrue()
    val outputDir = FileUtils.join(libBuildDir, "reports", "tests", "test-report")

    verifyHtmlReport(outputDir = outputDir, taskResult = result)
  }

  @Test
  fun testCreateAggregatedTestReportLib() {
    val result = rule.build.executor.run(":lib2:createAggregatedTestReport")
    val libBuildDir = rule.build.androidLibrary(":lib2").buildDir.toFile()
    val outputDir = FileUtils.join(libBuildDir, "reports", "tests", "aggregated-test-report")

    verifyHtmlReport(outputDir = outputDir, taskResult = result)
  }

  @Test
  fun testAggregatedTestReportingForNonPublishedLibModule() {
    val build = rule.build
    val aggregatedReportLibResult = build.executor.expectFailure().run(":lib:createAggregatedTestReport")
    aggregatedReportLibResult.assertFailureMessage().contains("task 'createAggregatedTestReport' not found in project ':lib'")
  }

  private fun verifyHtmlReport(outputDir: File, taskResult: GradleBuildResult) {
    assertThat(outputDir).exists()
    assertThat(outputDir).isDirectory()

    val indexFile = File(outputDir, "index.html")
    assertThat(indexFile).exists()
    assertThat(indexFile).isFile()
  }
}
