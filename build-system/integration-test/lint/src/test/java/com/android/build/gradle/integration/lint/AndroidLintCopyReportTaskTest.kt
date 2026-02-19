/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.internal.lint.AndroidLintCopyReportTask
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Integration test for [AndroidLintCopyReportTask] */
class AndroidLintCopyReportTaskTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication {
      android {
        lint {
          textOutput = File("lint-results.txt")
        }
      }
    }
  }

  // Regression test for b/189877657
  @Test
  fun testRunningTaskDirectly() {
    val result = rule.build.executor.run("clean", ":app:copyDebugLintReports")
    ScannerSubject.assertThat(result.stdout).contains("BUILD SUCCESSFUL")
    ScannerSubject.assertThat(result.stdout).contains("Unable to copy the lint text report")
  }

  @Test
  fun testReportCopiedAfterLint() {
    rule.build.executor.run("clean", ":app:lintDebug")
    assertThat(rule.build.androidApplication().resolve("lint-results.txt")).exists()
  }
}
