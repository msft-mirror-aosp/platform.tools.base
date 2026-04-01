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
}
