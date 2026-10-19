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

package com.android.build.gradle.internal.test.tasks

import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import java.io.File
import kotlin.test.assertFailsWith
import org.gradle.api.tasks.VerificationException
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TestReportTaskTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var task: TestableTestReportTask
  private lateinit var testResultsDir: File
  private lateinit var testReportDir: File

  abstract class TestableTestReportTask : TestReportTask() {
    fun runTaskAction() {
      doTaskAction()
    }
  }

  @Before
  fun setUp() {
    val project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
    task = project.tasks.register("testAllSuites", TestableTestReportTask::class.java).get()
    testResultsDir = temporaryFolder.newFolder("testResults")
    testReportDir = temporaryFolder.newFolder("testReport")
    task.rootProjectName.set("MyProject")
    task.rootProjectDir.set(project.rootDir)
    task.testResults.add(project.layout.projectDirectory.dir(testResultsDir.absolutePath))
    task.testReport.set(testReportDir)
  }

  @Test
  fun doTaskAction_failingTest_throwsVerificationExceptionAfterWritingReport() {
    writeTestResults(failingTest = true)

    val exception = assertFailsWith<VerificationException> { task.runTaskAction() }

    assertThat(exception.message).startsWith("There were failing tests. See the report at: ")
    assertThat(exception.message).contains("index.html")
    assertThat(testReportDir.resolve("index.html")).exists()
    assertThat(testReportDir.resolve("data.js")).exists()
  }

  @Test
  fun doTaskAction_passingTests_succeeds() {
    writeTestResults(failingTest = false)

    task.runTaskAction()

    assertThat(testReportDir.resolve("index.html")).exists()
    assertThat(testReportDir.resolve("data.js")).exists()
  }

  private fun writeTestResults(failingTest: Boolean) {
    val failure = if (failingTest) """<failure message="assertion failed">stack trace</failure>""" else ""
    testResultsDir
      .resolve("TEST-com.example.app.ExampleTest.xml")
      .writeText(
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <testsuite name="com.example.app.ExampleTest" tests="2" failures="${if (failingTest) 1 else 0}" errors="0" skipped="0" time="0.2">
            <properties>
                <property name="testedVariantName" value="debug"/>
                <property name="modulePath" value=":app"/>
                <property name="testSuiteName" value="UnitTest"/>
            </properties>
            <testcase name="testPass" classname="com.example.app.ExampleTest" time="0.1"/>
            <testcase name="testOther" classname="com.example.app.ExampleTest" time="0.1">$failure</testcase>
        </testsuite>
        """
          .trimIndent()
      )
  }
}
