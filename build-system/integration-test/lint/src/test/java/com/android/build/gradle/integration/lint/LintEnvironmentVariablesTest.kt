/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LintEnvironmentVariablesTest(private val aggregateReports: Boolean) {

  companion object {
    @Parameterized.Parameters(name = "aggregateReports={0}") @JvmStatic fun parameters() = listOf(true, false)
  }

  @get:Rule
  val project: GradleTestProject = GradleTestProject.builder().fromTestApp(HelloWorldApp.forPlugin("com.android.application")).create()

  @Test
  fun checkLintNotUpToDate() {
    val lintAnalyzeTaskName = ":lintAnalyzeDebug"
    val lintReportTaskName = if (aggregateReports) ":createLocalLintReportDebug" else ":lintReportDebug"
    val aggregatedReportTaskName = ":createAggregatedLintReportDebug"
    val tasks = mutableListOf(":lintDebug")
    if (aggregateReports) {
      tasks.add(":lintAggregatedDebug")
    }

    val executor = project.executor().with(BooleanOption.LINT_REPORT_AGGREGATION, aggregateReports)

    executor.run(tasks).apply {
      assertTask(lintAnalyzeTaskName).didWork()
      assertTask(lintReportTaskName).didWork()
      if (aggregateReports) {
        assertTask(aggregatedReportTaskName).didWork()
      }
    }

    // check that the lint tasks are up-to-date if nothing changes
    executor.run(tasks).apply {
      assertTask(lintAnalyzeTaskName).wasUpToDate()
      assertTask(lintReportTaskName).wasUpToDate()
      if (aggregateReports) {
        assertTask(aggregatedReportTaskName).wasUpToDate()
      }
    }

    val environmentVariables =
      listOf(
        "ANDROID_LINT_INCLUDE_LDPI",
        "ANDROID_LINT_MAX_DEPTH",
        "ANDROID_LINT_MAX_VIEW_COUNT",
        "ANDROID_LINT_NULLNESS_IGNORE_DEPRECATED",
      )

    for (environmentVariable in environmentVariables) {
      // check that the lint tasks are not up-to-date if we set the environment variable
      executor.withEnvironmentVariables(mapOf(environmentVariable to "foo")).run(tasks).apply {
        assertTask(lintAnalyzeTaskName, withInfo = "$environmentVariable=foo").didWork()
        assertTask(lintReportTaskName, withInfo = "$environmentVariable=foo").didWork()
        if (aggregateReports) {
          assertTask(aggregatedReportTaskName, withInfo = "$environmentVariable=foo").didWork()
        }
      }

      // run build without any environment variables before testing the next one
      executor.run(tasks)
    }

    val reportTaskEnvironmentVariables = listOf("LINT_HTML_PREFS", "LINT_XML_ROOT")

    for (environmentVariable in reportTaskEnvironmentVariables) {
      // check that the lint reporting task is not up-to-date if we set the environment
      // variable (the lint analysis task should be up-to-date)
      executor.withEnvironmentVariables(mapOf(environmentVariable to "foo")).run(tasks).apply {
        assertTask(lintReportTaskName).didWork()
        if (aggregateReports) {
          assertTask(aggregatedReportTaskName).didWork()
        }
        assertTask(lintAnalyzeTaskName).wasUpToDate()
      }
    }
  }
}
