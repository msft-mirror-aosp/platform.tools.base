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

package com.android.build.gradle.internal.test.report

import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.logging.Logging
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TestReportAggregationUtilsTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun processTestReportAggregation_keepsResultsOfOtherTargets() {
    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    val xmlResultsDirectory = directoryProperty(xmlResultsDir)

    // Device group test tasks process the results of each of their devices into the same directory.
    for (device in listOf("Pixel3", "Pixel4")) {
      val sourceDir = temporaryFolder.newFolder(device)
      sourceDir
        .resolve("TEST-com.example.SimpleTest.xml")
        .writeText(
          """<testsuite name="com.example.SimpleTest" tests="1"><testcase name="test" classname="com.example.SimpleTest"/></testsuite>"""
        )
      processTestReportAggregation(
        sourceDir,
        xmlResultsDirectory,
        ":app",
        "debug",
        "AndroidTest",
        device,
        Logging.getLogger(TestReportAggregationUtilsTest::class.java),
      )
    }

    val results = xmlResultsDir.listFiles()!!.map { it.readText() }
    assertThat(results).hasSize(2)
    assertThat(results.filter { it.contains("\"Pixel3\"") }).hasSize(1)
    assertThat(results.filter { it.contains("\"Pixel4\"") }).hasSize(1)
  }

  @Test
  fun clearTestReportAggregationResults_removesResultsOfPreviousRuns() {
    val xmlResultsDir = temporaryFolder.newFolder("xmlResults")
    // Result of a test class that was deleted after the previous run.
    xmlResultsDir.resolve("TEST-com.example.DeletedTest_0123.xml").writeText("<testsuite/>")

    clearTestReportAggregationResults(directoryProperty(xmlResultsDir))

    assertThat(xmlResultsDir).isDirectory()
    assertThat(xmlResultsDir.listFiles()!!.toList()).isEmpty()
  }

  private fun directoryProperty(dir: File): DirectoryProperty =
    ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build().objects.directoryProperty().fileValue(dir)
}
