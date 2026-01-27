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

package com.android.build.gradle.integration.testing.unit

import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import java.io.File
import org.gradle.api.JavaVersion
import org.junit.Rule
import org.junit.Test

/**
 * Integration test for [com.android.build.gradle.tasks.TestReportTask] and [com.android.build.gradle.tasks.TestResultsCollectionTask]
 * evaluating cross-module unit test reporting.
 */
class UnitTestingReportTest {

  @get:Rule
  val rule =
    GradleRule.fromProject("reportAggregation", "reportAggregation") {
      androidApplication(":app") {
        android {
          namespace = "com.example.app"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          defaultConfig {
            minSdk { version = release(24) }
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          flavorDimensions.add("tier")
          productFlavors {
            create("free") { it.dimension = "tier" }
            create("paid") { it.dimension = "tier" }
          }
          buildTypes { named("debug") { it.enableUnitTestCoverage = true } }
          kotlin { jvmToolchain(17) }
          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
          }
        }
        dependencies {
          implementation(project(":lib"))

          testImplementation("junit:junit:4.13.2")
          testImplementation("org.mockito:mockito-core:5.12.0")
          testImplementation("org.jdeferred:jdeferred-android-aar:1.2.3")
          testImplementation("commons-logging:commons-logging:1.1.1")
        }
      }
      androidLibrary(":lib") {
        android {
          namespace = "com.example.lib"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          defaultConfig {
            minSdk { version = release(24) }
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          flavorDimensions.add("tier")
          productFlavors {
            create("free") { it.dimension = "tier" }
            create("paid") { it.dimension = "tier" }
          }
          buildTypes { named("debug") { it.enableUnitTestCoverage = true } }
          kotlin { jvmToolchain(17) }
          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
          }
          dependencies {
            implementation(project(":lib2"))

            testImplementation("junit:junit:4.13.2")
            testImplementation("org.mockito:mockito-core:5.12.0")
            testImplementation("org.jdeferred:jdeferred-android-aar:1.2.3")
            testImplementation("commons-logging:commons-logging:1.1.1")
          }
          publishing { singleVariant("freeDebug") }
        }
      }
      androidLibrary(":lib2") {
        android {
          namespace = "com.example.lib2"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          defaultConfig {
            minSdk { version = release(24) }
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          flavorDimensions.add("tier")
          productFlavors {
            create("free") { it.dimension = "tier" }
            create("paid") { it.dimension = "tier" }
          }
          buildTypes { named("debug") { it.enableUnitTestCoverage = true } }
          kotlin { jvmToolchain(17) }
          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
          }
          dependencies {
            testImplementation("junit:junit:4.13.2")
            testImplementation("org.mockito:mockito-core:5.12.0")
            testImplementation("org.jdeferred:jdeferred-android-aar:1.2.3")
            testImplementation("commons-logging:commons-logging:1.1.1")
          }
          publishing { singleVariant("freeDebug") }
        }
      }
    }

  @Test
  fun testCreateTestReport() {
    val result = rule.build.executor.run(":app:createTestReport")
    val appBuildDir = rule.build.androidApplication(":app").buildDir.toFile()
    val outputDir = FileUtils.join(appBuildDir, "reports", "tests", "test-report")

    verifyHtmlReport(outputDir = outputDir, taskResult = result)
  }

  @Test
  fun testCreateAggregatedTestReport() {
    val result = rule.build.executor.run(":app:createAggregatedTestReport")
    val appBuildDir = rule.build.androidApplication(":app").buildDir.toFile()
    val outputDir = FileUtils.join(appBuildDir, "reports", "tests", "aggregated-test-report")

    verifyHtmlReport(outputDir = outputDir, taskResult = result)
  }

  @Test
  fun testCreateTestReportLib() {
    val result = rule.build.executor.run(":lib:createTestReport")
    val libBuildDir = rule.build.androidLibrary(":lib").buildDir.toFile()
    val outputDir = FileUtils.join(libBuildDir, "reports", "tests", "test-report")

    verifyHtmlReport(outputDir = outputDir, taskResult = result)
  }

  @Test
  fun testCreateAggregatedTestReportLib() {
    val result = rule.build.executor.run(":lib:createAggregatedTestReport")
    val libBuildDir = rule.build.androidLibrary(":lib").buildDir.toFile()
    val outputDir = FileUtils.join(libBuildDir, "reports", "tests", "aggregated-test-report")

    verifyHtmlReport(outputDir = outputDir, taskResult = result)
  }

  @Test
  fun testCreateAggregatedTestReportLib2() {
    val result = rule.build.executor.run(":lib2:createAggregatedTestReport")
    val lib2BuildDir = rule.build.androidLibrary(":lib2").buildDir.toFile()
    val outputDir = FileUtils.join(lib2BuildDir, "reports", "tests", "aggregated-test-report")

    verifyHtmlReport(outputDir = outputDir, taskResult = result)
  }

  private fun verifyHtmlReport(outputDir: File, taskResult: GradleBuildResult) {
    assertThat(outputDir).exists()
    assertThat(outputDir).isDirectory()

    val indexFile = File(outputDir, "index.html")
    assertThat(indexFile).exists()
    assertThat(indexFile).isFile()
  }
}
