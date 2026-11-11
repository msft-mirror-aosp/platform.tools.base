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

package com.android.build.gradle.integration.cacheability

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * Integration test verifying that relocating a built project workspace (source code, build outputs, and .gradle execution history) to a new
 * directory preserves 100% up-to-dateness for all build, unit test, and lint analysis tasks (b/533547933).
 */
class RelocatedNoOpBuildTaskStatesTest {

  @get:Rule val rule1 = GradleRule.from(folderName = "project1", logicalName = "project") {}

  // Destination project fixture managing Tooling API connection for the relocated directory
  @get:Rule val rule2 = GradleRule.from(folderName = "relocatedProject", logicalName = "project") {}

  @Test
  fun testRelocatedWorkspaceIsUpToDate() {
    val projectDef: GradleBuildDefinition.() -> Unit = {
      androidApplication(":app") {
        android {
          defaultConfig { versionCode = 1 }
          testOptions { unitTests { isIncludeAndroidResources = true } }
          buildTypes { named("debug") { it.enableUnitTestCoverage = true } }
          buildFeatures { resValues = true }
        }
      }
    }

    val build1 = rule1.build(projectDef)
    val initialDir = build1.directory.toFile()

    val build2 = rule2.build(projectDef)
    val relocatedDir = build2.directory.toFile()

    // In Gradle 9.8+, configuration cache is invalidated when the root project directory changes,
    // so the second build configures a fresh task graph at relocatedDir and HandleStaleOutputsStep
    // checks .gradle/buildOutputCleanup/outputFiles.bin (which is keyed by absolute path and checks
    // whether any ancestor directory is a recorded OutputKind.OUTPUT). Pre-register relocatedDir/app/build
    // in outputFiles.bin during build1 so HandleStaleOutputsStep does not delete relocated outputs
    // before up-to-date checks run.
    val relocatedAppBuildDir = relocatedDir.resolve("app/build").apply { mkdirs() }
    build1.androidApplication(":app").files.update("build.gradle") {
      append(
        """
        tasks.register("recordRelocatedOutputDir") {
            outputs.dir("${relocatedAppBuildDir.absolutePath.replace("\\", "/")}")
            doLast {}
        }
        """
          .trimIndent()
      )
    }

    val tasks =
      arrayOf(
        ":app:assembleDebug",
        ":app:testDebugUnitTest",
        ":app:lintDebug",
        ":app:lintAnalyzeDebug",
        ":app:lintAnalyzeRelease",
        ":app:lintVitalRelease",
        ":app:packageDebugBundle",
        ":app:assembleRelease",
        ":app:packageReleaseBundle",
      )

    // 1. Initial build on project1
    build1.executor.run(tasks.toList() + ":app:recordRelocatedOutputDir")

    // 2. Copy full built workspace (source + build/ + .gradle/) to destination project
    FileUtils.copyDirectory(initialDir, relocatedDir)

    // 3. Execute tasks on relocated workspace via rule2 (NO clean step)
    val result = build2.executor.run(tasks.toList())

    // Tasks that are expected to re-execute when the configuration cache is invalidated on relocation:
    // - Terminal lint tasks configured with outputs.upToDateWhen { false }
    // - Non-cacheable (@DisableCachingByDefault) tasks that intentionally declare @Input properties
    //   containing absolute project/build directory paths or PathSensitivity.ABSOLUTE
    //   (ListingFileRedirectTask, MapSourceSetPathsTask, and AndroidLintInputs when lintMode != LintMode.ANALYSIS)
    val alwaysRunOrLocationDependentTasks =
      setOf(
        ":app:lintDebug",
        ":app:lintVitalRelease",
        ":app:createDebugApkListingFileRedirect",
        ":app:createReleaseApkListingFileRedirect",
        ":app:mapDebugSourceSetPaths",
        ":app:mapReleaseSourceSetPaths",
        ":app:generateDebugLintReportModel",
        ":app:generateReleaseLintVitalReportModel",
        ":app:generateDebugAndroidTestLintModel",
        ":app:generateDebugUnitTestLintModel",
        ":app:lintReportDebug",
        ":app:lintVitalReportRelease",
      )

    // Assert 100% up-to-dateness for all real execution tasks
    val unexpectedExecutedTasks = result.didWorkTasks - alwaysRunOrLocationDependentTasks
    assertThat(unexpectedExecutedTasks).named("Unexpected tasks re-executed after workspace relocation").isEmpty()

    // Verify lint partial results directory exists and check for host path leaks (b/533527424)
    val lintPartialResultsDir = relocatedDir.resolve("app/build/intermediates/lint_partial_results")
    assertThat(lintPartialResultsDir.exists()).named("Lint partial results directory should exist under build outputs").isTrue()

    val rootAbsolutePath = initialDir.absolutePath
    assertNoAbsolutePathLeaks(lintPartialResultsDir, listOf(rootAbsolutePath, rootAbsolutePath.replace('\\', '/')))
  }

  /** Asserts that no file under [dir] contains any forbidden absolute path strings in [forbiddenPaths]. */
  private fun assertNoAbsolutePathLeaks(dir: File, forbiddenPaths: List<String>) {
    dir
      .walkTopDown()
      .filter { it.isFile }
      .forEach { file ->
        val bytes = file.readBytes()
        val content = String(bytes, Charsets.ISO_8859_1)
        for (forbiddenPath in forbiddenPaths) {
          if (forbiddenPath.isNotBlank()) {
            assertWithMessage("Intermediate artifact file ${file.absolutePath} contains absolute path leak: $forbiddenPath")
              .that(content)
              .doesNotContain(forbiddenPath)
          }
        }
      }
  }
}
