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
import com.android.build.gradle.integration.common.fixture.app.KotlinHelloWorldApp
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LintConfigurationOverrideTest(private val lintReportAggregation: Boolean) {

  companion object {
    @Parameterized.Parameters(name = "lintReportAggregation_{0}") @JvmStatic fun params() = listOf(true, false)
  }

  @get:Rule
  val project: GradleTestProject =
    GradleTestProject.builder().fromTestApp(KotlinHelloWorldApp.forPlugin("com.android.application")).create()

  @get:Rule val temporaryFolder: TemporaryFolder = TemporaryFolder()

  private val lintTaskNames: List<String>
    get() =
      if (lintReportAggregation) {
        listOf(":lintDebug", ":lintAggregatedDebug")
      } else {
        listOf(":lintDebug")
      }

  private val lintReportTaskNames: List<String>
    get() =
      if (lintReportAggregation) {
        listOf(":createLocalLintReportDebug", ":createAggregatedLintReportDebug")
      } else {
        listOf(":lintReportDebug")
      }

  private val lintAnalyzeTaskName = ":lintAnalyzeDebug"

  private val executor
    get() = project.executor().with(BooleanOption.LINT_REPORT_AGGREGATION, lintReportAggregation)

  // Test that specifying a lint configuration override via a system property affects lint task
  // UP-TO-DATE checking as expected.
  @Test
  fun testLintConfigurationOverrideFromSystemProperty() {
    val lintXml1 = temporaryFolder.newFolder().resolve("lint.xml").also { it.writeText("foo") }
    val lintXml2 = temporaryFolder.newFolder().resolve("lint.xml").also { it.writeText("foo") }
    val nonexistentFile = temporaryFolder.newFolder().resolve("nonexistent").also { assertThat(it).doesNotExist() }

    // Use a nonexistent lint configuration file initially as a check that the build doesn't
    // fail in this case.
    executor.withArgument("-Dlint.configuration.override=${nonexistentFile.absolutePath}").run(lintTaskNames).apply {
      lintReportTaskNames.forEach { assertTask(it).didWork() }
      assertTask(lintAnalyzeTaskName).didWork()
    }
    // lint tasks should run again if we specify a lint.configuration.override system property.
    executor.withArgument("-Dlint.configuration.override=${lintXml1.absolutePath}").run(lintTaskNames).apply {
      lintReportTaskNames.forEach { assertTask(it).didWork() }
      assertTask(lintAnalyzeTaskName).didWork()
    }
    // lint tasks should be up-to-date if we set a different lint configuration file with the
    // same contents
    executor.withArgument("-Dlint.configuration.override=${lintXml2.absolutePath}").run(lintTaskNames).apply {
      lintReportTaskNames.forEach { assertTask(it).wasUpToDate() }
      assertTask(lintAnalyzeTaskName).wasUpToDate()
    }
    // lint tasks should run again if we modify the contents of the lint configuration file.
    lintXml2.appendText("bar")
    executor.withArgument("-Dlint.configuration.override=${lintXml2.absolutePath}").run(lintTaskNames).apply {
      lintReportTaskNames.forEach { assertTask(it).didWork() }
      assertTask(lintAnalyzeTaskName).didWork()
    }
  }

  // Test that specifying a lint configuration override via an environment variable affects lint
  // task UP-TO-DATE checking as expected.
  @Test
  fun testLintConfigurationOverrideFromEnvironmentVariable() {
    val lintXml1 = temporaryFolder.newFolder().resolve("lint.xml").also { it.writeText("foo") }
    val lintXml2 = temporaryFolder.newFolder().resolve("lint.xml").also { it.writeText("foo") }
    val nonexistentFile = temporaryFolder.newFolder().resolve("nonexistent").also { assertThat(it).doesNotExist() }

    // Use a nonexistent lint configuration file initially as a check that the build doesn't
    // fail in this case.
    executor.withEnvironmentVariables(mapOf("LINT_OVERRIDE_CONFIGURATION" to nonexistentFile.absolutePath)).run(lintTaskNames).apply {
      lintReportTaskNames.forEach { assertTask(it).didWork() }
      assertTask(lintAnalyzeTaskName).didWork()
    }
    // lint tasks should run again if we specify a LINT_OVERRIDE_CONFIGURATION environment
    // variable.
    executor.withEnvironmentVariables(mapOf("LINT_OVERRIDE_CONFIGURATION" to lintXml1.absolutePath)).run(lintTaskNames).apply {
      lintReportTaskNames.forEach { assertTask(it).didWork() }
      assertTask(lintAnalyzeTaskName).didWork()
    }
    // lint tasks should be up-to-date if we set a different lint configuration file with the
    // same contents
    executor.withEnvironmentVariables(mapOf("LINT_OVERRIDE_CONFIGURATION" to lintXml2.absolutePath)).run(lintTaskNames).apply {
      lintReportTaskNames.forEach { assertTask(it).wasUpToDate() }
      assertTask(lintAnalyzeTaskName).wasUpToDate()
    }
    // lint tasks should run again if we modify the contents of the lint configuration file.
    lintXml2.appendText("bar")
    executor.withEnvironmentVariables(mapOf("LINT_OVERRIDE_CONFIGURATION" to lintXml2.absolutePath)).run(lintTaskNames).apply {
      lintReportTaskNames.forEach { assertTask(it).didWork() }
      assertTask(lintAnalyzeTaskName).didWork()
    }
  }
}
