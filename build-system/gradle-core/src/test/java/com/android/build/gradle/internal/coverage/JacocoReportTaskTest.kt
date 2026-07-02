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

package com.android.build.gradle.internal.coverage

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class JacocoReportTaskTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  lateinit var project: Project
  lateinit var task: TestJacocoReportTask

  abstract class TestJacocoReportTask : JacocoReportTask() {
    fun callDoTaskAction() {
      this.doTaskAction()
    }
  }

  @Before
  fun setUp() {
    project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
    task = project.tasks.create("jacocoReportTask", TestJacocoReportTask::class.java)

    task.reportAggregation.set(true)
    task.onTheFlyCoverageEnabled.set(false)
    task.modulePath.set(":app")
    task.testedVariantName.set("debug")
    task.testSuiteName.set("unit")
    task.rootProjectName.set("project")
    task.rootProjectDir.set(temporaryFolder.newFolder("root"))
  }

  @Test(expected = IOException::class)
  fun testTaskFailsIfNoCoverageFiles() {
    val root = temporaryFolder.newFolder("no_coverage")
    File(root, "metadata.txt").apply { createNewFile() }

    task.coverageFiles.from(root)
    task.jacocoClasspath.from(temporaryFolder.newFile("jacoco.jar"))
    task.classFileCollection.from(temporaryFolder.newFolder("classes"))
    task.reportName.set("test")

    // This will trigger the filtering logic and throw IOException because jacocoCoverageFiles is empty.
    task.callDoTaskAction()
  }

  @Test
  fun testTaskDoesNotFailIfCoverageFilesArePresent() {
    val root = temporaryFolder.newFolder("with_coverage")
    File(root, "test.ec").apply { createNewFile() }
    File(root, "metadata.txt").apply { createNewFile() }

    task.coverageFiles.from(root)
    task.jacocoClasspath.from(temporaryFolder.newFile("jacoco.jar"))
    task.classFileCollection.from(temporaryFolder.newFolder("classes"))
    task.reportName.set("test")

    // It will still fail later because of workerExecutor, but we want to see it pass the filtering stage.
    try {
      task.callDoTaskAction()
    } catch (e: Exception) {
      // It might fail on workerExecutor, that's fine. We want to ensure it's not the "no coverage data was found" error.
      assertThat(e.message)
        .isNotEqualTo(
          "Test coverage report requested, but no tests were run. Task 'jacocoReportTask' failed because no coverage data was found."
        )
    }
  }

  @Test
  fun testTaskSubmitsWorkerForOnTheFlyCoverage() {
    val root = temporaryFolder.newFolder("on_the_fly")
    File(root, "coverage_metadata.pb").apply { createNewFile() }
    File(root, "coverage_hits.pb").apply { createNewFile() }

    task.coverageFiles.from(root)
    task.jacocoClasspath.from(temporaryFolder.newFile("jacoco.jar"))
    task.classFileCollection.from(temporaryFolder.newFolder("classes"))
    task.reportName.set("test")
    task.onTheFlyCoverageEnabled.set(true)

    try {
      task.callDoTaskAction()
    } catch (e: Exception) {
      assertThat(e.message)
        .isNotEqualTo(
          "Test coverage report requested, but no tests were run. Task 'jacocoReportTask' failed because no coverage data was found."
        )
    }
  }

  @Test
  fun testWorkerThrowsIfPbFilesMissingInOnTheFlyMode() {
    val params = mock(JacocoReportTask.JacocoWorkParameters::class.java)
    val coverageFiles = project.files(temporaryFolder.newFolder("empty"))
    val reportDir = project.objects.directoryProperty().fileValue(temporaryFolder.newFolder("report"))

    `when`(params.coverageFiles).thenReturn(coverageFiles)
    `when`(params.reportDir).thenReturn(reportDir)
    `when`(params.reportAggregation).thenReturn(project.objects.property(Boolean::class.java).value(true))
    `when`(params.onTheFlyCoverageEnabled).thenReturn(project.objects.property(Boolean::class.java).value(true))
    `when`(params.taskName).thenReturn(project.objects.property(String::class.java).value("testTask"))
    `when`(params.reportName).thenReturn(project.objects.property(String::class.java).value("testReport"))
    `when`(params.testPackageId).thenReturn(project.objects.property(String::class.java).value("com.example"))
    `when`(params.exclusions).thenReturn(project.objects.setProperty(String::class.java).value(emptySet()))

    val worker =
      object : JacocoReportTask.JacocoReportWorkerAction() {
        override fun getParameters(): JacocoReportTask.JacocoWorkParameters = params
      }

    try {
      worker.execute()
    } catch (e: Exception) {
      assertThat(e.cause?.message).contains("On-the-fly coverage is enabled but required .pb files were not found.")
    }
  }

  @Test
  fun testWorkerThrowsIfNoLegacyFilesFound() {
    val params = mock(JacocoReportTask.JacocoWorkParameters::class.java)
    val coverageFiles = project.files(temporaryFolder.newFolder("empty_legacy"))
    val reportDir = project.objects.directoryProperty().fileValue(temporaryFolder.newFolder("report"))

    `when`(params.coverageFiles).thenReturn(coverageFiles)
    `when`(params.reportDir).thenReturn(reportDir)
    `when`(params.reportAggregation).thenReturn(project.objects.property(Boolean::class.java).value(false))
    `when`(params.onTheFlyCoverageEnabled).thenReturn(project.objects.property(Boolean::class.java).value(false))
    `when`(params.taskName).thenReturn(project.objects.property(String::class.java).value("testTask"))

    val worker =
      object : JacocoReportTask.JacocoReportWorkerAction() {
        override fun getParameters(): JacocoReportTask.JacocoWorkParameters = params
      }

    try {
      worker.execute()
    } catch (e: Exception) {
      assertThat(e.cause?.message).contains("Task 'testTask' failed because no coverage data was found.")
    }
  }
}
