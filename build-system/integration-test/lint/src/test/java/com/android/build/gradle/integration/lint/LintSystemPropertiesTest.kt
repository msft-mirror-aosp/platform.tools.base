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
class LintSystemPropertiesTest(private val aggregateReports: Boolean) {

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

    val systemPropertiesWithValues =
      listOf(
        "android.lint.log-jar-problems=true",
        "lint.nullness.ignore-deprecated=true",
        "lint.unused-resources.exclude-tests=true",
        "lint.unused-resources.include-tests=true",
      )

    for (systemPropertyWithValue in systemPropertiesWithValues) {
      // check that the lint tasks are not up-to-date if we set the system property
      executor.withArgument("-D$systemPropertyWithValue").run(tasks).apply {
        assertTask(lintAnalyzeTaskName, withInfo = "-D$systemPropertyWithValue").didWork()
        assertTask(lintReportTaskName, withInfo = "-D$systemPropertyWithValue").didWork()
        if (aggregateReports) {
          assertTask(aggregatedReportTaskName, withInfo = "-D$systemPropertyWithValue").didWork()
        }
      }

      // run build without any system properties before testing the next one
      executor.run(tasks)
    }

    val reportTaskSystemProperties = listOf("lint.autofix", "lint.baselines.continue", "lint.html.prefs", "user.home")

    for (systemProperty in reportTaskSystemProperties) {
      // check that the lint reporting task is not up-to-date if we set the system property
      // (the lint analysis task should be up-to-date)
      executor.withArgument("-D$systemProperty=foo").run(tasks).apply {
        assertTask(lintAnalyzeTaskName, withInfo = "-D$systemProperty=foo").wasUpToDate()
        assertTask(lintReportTaskName, withInfo = "-D$systemProperty=foo").didWork()
        if (aggregateReports) {
          assertTask(aggregatedReportTaskName, withInfo = "-D$systemProperty=foo").didWork()
        }
      }

      // run build without any system properties before testing the next one
      executor.run(tasks)
    }
  }
}
