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

package com.android.build.gradle.integration.testing.screenshot

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.Rule
import org.junit.Test

/** Tests for screenshot test suites declared through `testOptions.screenshotTests`. */
class ScreenshotTestSuiteTest {

  @get:Rule
  val rule =
    GradleRule.configure().withProfileOutput().from {
      androidApplication { setupScreenshotTestSuiteProject() }
      androidLibrary { setupScreenshotTestSuiteProject() }
      // These two libraries have no module specific jar on their buildscript class path, so they share the same class loader.
      // See b/340362066 for more details.
      repeat(2) {
        androidLibrary(":lib2_$it") {
          setupScreenshotTestSuiteProject(addEmptyJarToClassPath = false)
        }
      }

      gradleProperties {
        add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
        add(BooleanOption.TEST_SUITE_SUPPORT, true)
      }
    }

  @Test
  fun runPreviewScreenshotTest() {
    val build = rule.build
    val appProject = build.androidApplication()
    val verifier = createVerifier(appProject)

    val indexHtmlReport = getTestSuiteHtmlReportIndexFile(appProject)
    val classHtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.ExampleTest")
    val class2HtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.TopLevelPreviewTestKt")
    val testNames =
      listOf(
        "simpleComposableTest",
        "simpleComposableTest2",
        "previewNameCannotBeUsedAsFileNameTest",
      )

    verifier.verifyPreviewScreenshotTest(
      appProject = appProject,
      updateTask = { build.updateReferenceImageWithTestSuite() },
      validateTask = { build.validateScreenshotTestWithTestSuite() },
      validateTaskExpectingFailure = {
        build.validateScreenshotTestWithTestSuite(expectFailure = true)
      },
      assertPassingHtmlReports = {
        assertThat(indexHtmlReport).exists()
        assertThat(classHtmlReport).exists()
        val classHtmlReportText = classHtmlReport.readText()
        testNames.forEach { assertThat(classHtmlReportText).contains(it) }
        class2HtmlReport.readText().let {
          assertThat(it).contains("simpleComposableTest_3")
        }
      },
      assertFailingHtmlReports = {
        assertThat(indexHtmlReport).exists()
        assertThat(classHtmlReport).exists()
        val classHtmlReportText = classHtmlReport.readText()
        testNames.forEach { assertThat(classHtmlReportText).contains(it) }
        class2HtmlReport.readText().let {
          assertThat(it).contains("simpleComposableTest_3")
        }
      },
    )
  }

  @Test
  fun runPreviewScreenshotTestWithFilter() {
    val build = rule.build {
      androidApplication { pluginCallbacks += FilterSetupWithTestSuiteCallback::class.java }
    }
    val appProject = build.androidApplication()
    val verifier = createVerifier(appProject)

    val indexHtmlReport = getTestSuiteHtmlReportIndexFile(appProject)
    val classHtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.ExampleTest")
    val class2HtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.TopLevelPreviewTestKt")

    verifier.verifyPreviewScreenshotTestWithFilter(
      appProject = appProject,
      updateTask = { build.updateReferenceImageWithTestSuite() },
      validateTask = { build.validateScreenshotTestWithTestSuite() },
      validateTaskExpectingFailure = {
        build.validateScreenshotTestWithTestSuite(expectFailure = true)
      },
      assertFilteredHtmlReports = {
        assertThat(indexHtmlReport).exists()
        assertThat(classHtmlReport).exists()
        assertClassHtmlReportContents(classHtmlReport, class2HtmlReport)
      },
    )
  }

  @Test
  fun runPreviewScreenshotTestWithNoMatchingFilter() {
    val build = rule.build {
      androidApplication {
        pluginCallbacks += FilterMatchingNothingWithTestSuiteCallback::class.java
      }
    }
    val appProject = build.androidApplication()
    val verifier = createVerifier(appProject)

    verifier.verifyPreviewScreenshotTestWithNoMatchingFilter(
      updateTask = { build.updateReferenceImageWithTestSuite() },
      validateTask = { build.validateScreenshotTestWithTestSuite() },
    )
  }

  @Test
  fun runPreviewScreenshotTestMissingReferences() {
    val build = rule.build
    val appProject = build.androidApplication()
    val verifier = createVerifier(appProject)

    verifier.verifyPreviewScreenshotTestMissingReferences(
      validateTaskExpectingFailure = {
        build.validateScreenshotTestWithTestSuite(expectFailure = true)
      }
    )
  }

  @Test
  fun runPreviewScreenshotTestInAllProjects() {
    val build = rule.build

    val updateResult = build.updateReferenceImageForAllProjectsWithTestSuite()

    ALL_MODULES.forEach { module ->
      updateResult.assertTask(getTestSuiteUpdateTaskName(projectName = module)).didWork()
      val referenceDir = getTestSuiteReferenceDir(build.subProject(":$module"), "pkg.name.ExampleTest")
      assertThat(referenceDir.listDirectoryEntries().map { it.name }).containsExactlyElementsIn(EXPECTED_EXAMPLE_TEST_REFERENCE_IMAGES)
    }

    val validateResult = build.validateScreenshotTestForAllProjectsWithTestSuite()

    ALL_MODULES.forEach { module -> validateResult.assertTask(getTestSuiteValidateTaskName(projectName = module)).didWork() }

    val layoutlibDataDirs = ALL_MODULES.map { getLayoutlibDataDir(build.subProject(":$it")) }
    assertThat(layoutlibDataDirs.toSet()).hasSize(1)
    layoutlibDataDirs.forEach { assertThat(it.resolve(FRAMEWORK_RES_JAR)).isFile() }
  }

  @Test
  fun runPreviewScreenshotTestOnReleaseVariant() {
    val build = rule.build
    val appProject = build.androidApplication()

    build.updateReferenceImageWithTestSuite(variantName = RELEASE)
    val referenceDir = getTestSuiteReferenceDir(appProject, "pkg.name.ExampleTest", variantName = RELEASE)
    assertThat(referenceDir.listDirectoryEntries().map { it.name }).containsExactlyElementsIn(EXPECTED_EXAMPLE_TEST_REFERENCE_IMAGES)

    val validateResult = build.validateScreenshotTestWithTestSuite(variantName = RELEASE)
    validateResult.assertTask(getTestSuiteValidateTaskName(variantName = RELEASE)).didWork()

    val releaseLayoutlibDataDir = getLayoutlibDataDir(appProject, variantName = RELEASE)
    assertThat(releaseLayoutlibDataDir.resolve(FRAMEWORK_RES_JAR)).isFile()
  }

  @Test
  fun validationCanBeRepeatedWithoutRedoingTheLayoutlibExtraction() {
    val build = rule.build
    val appProject = build.androidApplication()
    val validateTaskName = getTestSuiteValidateTaskName()

    build.updateReferenceImageWithTestSuite()
    build.validateScreenshotTestWithTestSuite().assertTask(validateTaskName).didWork()

    val layoutlibDataDir = getLayoutlibDataDir(appProject)
    assertThat(layoutlibDataDir.resolve(FRAMEWORK_RES_JAR)).isFile()
    val extractedLayoutlib = snapshotDirectory(layoutlibDataDir)

    val secondRun = build.validateScreenshotTestWithTestSuite()

    secondRun.assertTask(validateTaskName).didWork()
    secondRun.assertConfigurationCacheHit()
    assertThat(getLayoutlibDataDir(appProject)).isEqualTo(layoutlibDataDir)
    assertThat(snapshotDirectory(layoutlibDataDir)).isEqualTo(extractedLayoutlib)
  }

  private fun createVerifier(appProject: com.android.build.gradle.integration.common.fixture.project.GradleProject<*>) =
    ScreenshotTestVerifier(
      referenceDirResolver = { getTestSuiteReferenceDir(appProject, it) },
      diffDirResolver = { getTestSuiteDiffDir(appProject, it) },
    )

  private fun assertClassHtmlReportContents(
    classHtmlReport: Path,
    class2HtmlReport: Path,
  ) {
    val classHtmlReportText = classHtmlReport.readText()
    assertThat(classHtmlReportText).contains("simpleComposableTest")
    assertThat(classHtmlReportText).contains("simpleComposableTest2")
    assertThat(classHtmlReportText).doesNotContain("multiPreviewTest")
    assertThat(class2HtmlReport.readText()).contains("simpleComposableTest_3")
  }

  companion object {
    private val ALL_MODULES = listOf("app", "lib", "lib2_0", "lib2_1")
    private const val FRAMEWORK_RES_JAR = "data/framework_res.jar"
    private const val RELEASE = "release"
  }
}
