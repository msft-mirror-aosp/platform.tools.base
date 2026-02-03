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

package com.android.build.gradle.integration.lint

import com.android.Version
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for b/197146610.
 *
 * Checks that "Lint checks for lint checks" (specifically LintImplIdFormat) run correctly in a standalone lint project.
 */
class LintDetectorDetectorStandaloneTest {

  @get:Rule
  val rule =
    GradleRule.fromProject("lintStandaloneCustomRules") {
      javaLibrary(":lint") {
        applyPlugin(PluginType.LINT) {
          textReport = true
          textOutput = projectDotFile("lint-results.txt")
          abortOnError = false
        }
        dependencies { compileOnly("com.android.tools.lint:lint-api:${Version.ANDROID_TOOLS_BASE_VERSION}") }
      }
    }

  @Test
  fun testLintDetectorDetector() {
    val build = rule.build
    val lintProject = build.javaLibrary(":lint")

    lintProject.files
      .update("src/main/java/com/example/google/lint/MyDetector.java")
      .searchAndReplace("\"UnitTestLintCheck2\"", "\"UnitTest Lint Check 2\"")

    // Run lint on the lint subproject itself.
    build.executor.run(":lint:lint")

    val lintReport = lintProject.resolve("lint-results.txt")
    assertThat(lintReport).exists()

    // Check for the expected warning from LintDetectorDetector (LintImplIdFormat)
    assertThat(lintReport).contains("Error: Lint issue IDs should not contain spaces, such as MyIssueId [LintImplIdFormat]")
  }
}
