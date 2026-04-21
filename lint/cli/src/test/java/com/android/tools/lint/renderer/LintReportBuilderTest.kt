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

package com.android.tools.lint.renderer

import com.android.tools.lint.LintCliClient
import com.android.tools.lint.client.api.IssueRegistry.Companion.AOSP_VENDOR
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Incident
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.Location
import com.android.tools.lint.detector.api.Position
import com.android.tools.lint.detector.api.Project
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.TextFormat
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever

class LintReportBuilderTest {
  @Test
  fun testBuildReport() {
    val client = createMockClient()
    val rootProjectDir = File("/path/to/project")
    val displayRevision = "1.0.0"
    val builder = LintReportBuilder(client, "Test Report", rootProjectDir, displayRevision) { file -> "http://example.com/${file.name}" }

    val issue1 = mock(Issue::class.java)
    `when`(issue1.id).thenReturn("TestIssue1")
    `when`(issue1.category).thenReturn(Category.CORRECTNESS)
    `when`(issue1.priority).thenReturn(5)
    `when`(issue1.moreInfo).thenReturn(emptyList())

    val incident1 = mock(Incident::class.java)
    `when`(incident1.issue).thenReturn(issue1)
    `when`(incident1.severity).thenReturn(Severity.ERROR)
    `when`(incident1.message).thenReturn("Test message 1")

    val location1 = mock(Location::class.java)
    val start1 = mock(Position::class.java)
    `when`(start1.line).thenReturn(10)
    `when`(start1.column).thenReturn(5)
    `when`(location1.start).thenReturn(start1)
    val file1 = File("/path/to/project/app/src/main/java/com/example/Test1.java")
    `when`(location1.file).thenReturn(file1)
    `when`(incident1.location).thenReturn(location1)
    `when`(incident1.file).thenReturn(file1)

    val report = builder.buildReport(listOf(incident1), emptyList(), emptyMap())

    with(report) {
      assertEquals("Test Report", name)
      assertEquals(1, numberOfIssues)
      assertEquals("1.0.0", lintVersion)
      assertNotNull(timeStamp)
      assertEquals(1, issues.size)
    }

    val lintIssue = report.issues[0]
    with(lintIssue) {
      assertEquals("TestIssue1", id)
      assertEquals("Test message 1", message)
      assertEquals("Error", severityDescription)
      assertEquals(5, priority)
      assertEquals("http://example.com/Test1.java", location?.url)
      assertEquals("app.src.main.java.com.example", packageName)
      assertEquals(11, location?.line)
      assertEquals(6, location?.column)
    }
  }

  @Test
  fun testSecondaryLocations() {
    val client = createMockClient()
    val rootProjectDir = File("/path/to/project")
    val builder = LintReportBuilder(client, "Test Report", rootProjectDir, "1.0") { null }

    val issue = mock(Issue::class.java)
    `when`(issue.id).thenReturn("TestIssue")
    `when`(issue.category).thenReturn(Category.CORRECTNESS)

    val incident = mock(Incident::class.java)
    `when`(incident.issue).thenReturn(issue)
    `when`(incident.severity).thenReturn(Severity.ERROR)

    val location1 = mock(Location::class.java)
    val location2 = mock(Location::class.java)
    `when`(location1.secondary).thenReturn(location2)
    `when`(location1.file).thenReturn(File("/path/to/project/file1.java"))
    `when`(location2.file).thenReturn(File("/path/to/project/file2.java"))

    `when`(incident.location).thenReturn(location1)
    `when`(incident.file).thenReturn(File("/path/to/project/file1.java"))

    val report = builder.buildReport(listOf(incident), emptyList(), emptyMap())
    val lintIssue = report.issues[0]

    with(lintIssue.secondaryLocations) {
      assertEquals(1, size)
      assertEquals("/path/to/project/file2.java", this[0].file)
    }
  }

  @Test
  fun testNoLineColumn() {
    val client = createMockClient()
    val rootProjectDir = File("/path/to/project")
    val builder = LintReportBuilder(client, "Test Report", rootProjectDir, "1.0") { null }

    val issue = mock(Issue::class.java)
    `when`(issue.id).thenReturn("TestIssue")
    `when`(issue.category).thenReturn(Category.CORRECTNESS)

    val incident = mock(Incident::class.java)
    `when`(incident.issue).thenReturn(issue)
    `when`(incident.severity).thenReturn(Severity.ERROR)

    val location = mock(Location::class.java)
    `when`(location.start).thenReturn(null)
    `when`(location.file).thenReturn(File("/path/to/project/file.java"))

    `when`(incident.location).thenReturn(location)
    `when`(incident.file).thenReturn(File("/path/to/project/file.java"))

    val report = builder.buildReport(listOf(incident), emptyList(), emptyMap())
    val lintIssue = report.issues[0]

    with(lintIssue.location) {
      assertNull(this?.line)
      assertNull(this?.column)
    }
  }

  @Test
  fun testDifferentFilePaths() {
    val client = createMockClient()
    val rootProjectDir = File("/path/to/project")
    val builder = LintReportBuilder(client, "Test Report", rootProjectDir, "1.0") { null }

    val issue = mock(Issue::class.java)
    `when`(issue.id).thenReturn("TestIssue")
    `when`(issue.category).thenReturn(Category.CORRECTNESS)

    val incident = mock(Incident::class.java)
    `when`(incident.issue).thenReturn(issue)
    `when`(incident.severity).thenReturn(Severity.ERROR)

    val location = mock(Location::class.java)
    val fileInRoot = File("/path/to/project/build.gradle")
    `when`(location.file).thenReturn(fileInRoot)

    `when`(incident.location).thenReturn(location)
    `when`(incident.file).thenReturn(fileInRoot)

    val report = builder.buildReport(listOf(incident), emptyList(), emptyMap())
    val lintIssue = report.issues[0]

    with(lintIssue) {
      assertEquals("default", packageName)
      assertEquals("build", className)
    }
  }

  @Test
  fun testExtraAndMissingIssues() {
    val client = createMockClient()
    val rootProjectDir = File("/path/to/project")
    val builder = LintReportBuilder(client, "Test Report", rootProjectDir, "1.0") { null }

    val extraIssue = mock(Issue::class.java)
    `when`(extraIssue.id).thenReturn("ExtraIssue")
    `when`(extraIssue.category).thenReturn(Category.CORRECTNESS)

    val missingIssue = mock(Issue::class.java)
    `when`(missingIssue.id).thenReturn("MissingIssue")
    `when`(missingIssue.category).thenReturn(Category.CORRECTNESS)

    val report = builder.buildReport(emptyList(), listOf(extraIssue), mapOf(missingIssue to "Disabled for reason"))

    with(report.additionalChecks) {
      assertEquals(1, size)
      assertEquals("ExtraIssue", this[0].id)
      assertNull(this[0].reason)
    }

    with(report.disabledChecks) {
      assertEquals(1, size)
      assertEquals("MissingIssue", this[0].id)
      assertEquals("Disabled for reason", this[0].reason)
    }
  }

  @Test
  fun testVendorNames() {
    val client = createMockClient()
    val rootProjectDir = File("/path/to/project")
    val builder = LintReportBuilder(client, "Test Report", rootProjectDir, "1.0") { null }

    val issue = mock(Issue::class.java)
    `when`(issue.id).thenReturn("TestIssue")
    `when`(issue.category).thenReturn(Category.CORRECTNESS)

    val vendor = mock(Vendor::class.java)
    `when`(vendor.vendorName).thenReturn("Custom Vendor")
    `when`(issue.vendor).thenReturn(vendor)

    val incident = mock(Incident::class.java)
    `when`(incident.issue).thenReturn(issue)
    `when`(incident.severity).thenReturn(Severity.ERROR)

    val location = mock(Location::class.java)
    `when`(location.file).thenReturn(File("/path/to/project/file.java"))
    `when`(incident.location).thenReturn(location)
    `when`(incident.file).thenReturn(File("/path/to/project/file.java"))

    val report = builder.buildReport(listOf(incident), emptyList(), emptyMap())
    val lintIssue = report.issues[0]

    assertEquals("Custom Vendor", lintIssue.vendor)

    // Test AOSP Vendor is ignored/set to null
    `when`(issue.vendor).thenReturn(AOSP_VENDOR)
    val reportAosp = builder.buildReport(listOf(incident), emptyList(), emptyMap())
    assertNull(reportAosp.issues[0].vendor)
  }

  @Test
  fun testMaxCountLimit() {
    val client = createMockClient()
    val rootProjectDir = File("/path/to/project")
    val builder = LintReportBuilder(client, "Test Report", rootProjectDir, "1.0") { null }

    val issue = mock(Issue::class.java)
    `when`(issue.id).thenReturn("TestIssue")
    `when`(issue.category).thenReturn(Category.CORRECTNESS)

    val incidents =
      (1..100).map { i ->
        val incident = mock(Incident::class.java)
        `when`(incident.issue).thenReturn(issue)
        `when`(incident.severity).thenReturn(Severity.ERROR)
        `when`(incident.message).thenReturn("Test message $i")

        val location = mock(Location::class.java)
        val file = File("/path/to/project/file.java")
        `when`(location.file).thenReturn(file)
        `when`(incident.location).thenReturn(location)
        `when`(incident.file).thenReturn(file)
        incident
      }

    val report = builder.buildReport(incidents, emptyList(), emptyMap(), maxCount = 10)

    assertEquals(10, report.issues.size)
    assertEquals(10, report.numberOfIssues)
    assertEquals("Test message 1", report.issues[0].message)
    assertEquals("Test message 10", report.issues[9].message)
  }

  @Test
  fun testIssueUrls() {
    val client = createMockClient()
    val rootProjectDir = File("/path/to/project")
    val builder = LintReportBuilder(client, "Test Report", rootProjectDir, "1.0") { null }

    val issue = mock(Issue::class.java)
    `when`(issue.id).thenReturn("TestIssue")
    `when`(issue.category).thenReturn(Category.CORRECTNESS)
    `when`(issue.moreInfo).thenReturn(listOf("https://example.com/info1", "https://example.com/info2"))

    val incident = mock(Incident::class.java)
    `when`(incident.issue).thenReturn(issue)
    `when`(incident.severity).thenReturn(Severity.ERROR)

    val location = mock(Location::class.java)
    `when`(location.file).thenReturn(File("/path/to/project/file.java"))
    `when`(incident.location).thenReturn(location)
    `when`(incident.file).thenReturn(File("/path/to/project/file.java"))

    val report = builder.buildReport(listOf(incident), emptyList(), emptyMap())
    val lintIssue = report.issues[0]

    assertEquals(listOf("https://example.com/info1", "https://example.com/info2"), lintIssue.urls)
  }

  @Test
  fun testImages() {
    val client = createMockClient()
    val rootProjectDir = File("/path/to/project")
    val builder = LintReportBuilder(client, "Test Report", rootProjectDir, "1.0") { file -> "http://example.com/${file.name}" }

    val issue = mock(Issue::class.java)
    `when`(issue.id).thenReturn("TestIssue")
    `when`(issue.category).thenReturn(Category.CORRECTNESS)

    val incident = mock(Incident::class.java)
    `when`(incident.issue).thenReturn(issue)
    `when`(incident.severity).thenReturn(Severity.ERROR)

    val location1 = mock(Location::class.java)
    val location2 = mock(Location::class.java)
    val location3 = mock(Location::class.java)
    `when`(location1.secondary).thenReturn(location2)
    `when`(location2.secondary).thenReturn(location3)

    `when`(location1.file).thenReturn(File("/path/to/project/icon1.png"))
    `when`(location2.file).thenReturn(File("/path/to/project/icon2.jpg"))
    `when`(location3.file).thenReturn(File("/path/to/project/not-an-image.txt"))

    `when`(incident.location).thenReturn(location1)
    `when`(incident.file).thenReturn(File("/path/to/project/icon1.png"))

    val report = builder.buildReport(listOf(incident), emptyList(), emptyMap())
    val lintIssue = report.issues[0]

    assertEquals(listOf("http://example.com/icon1.png", "http://example.com/icon2.jpg"), lintIssue.images)
  }

  private fun createMockClient(): LintCliClient {
    val client = mock(LintCliClient::class.java)
    whenever(client.getDisplayPath(any<File>(), anyOrNull<Project>(), any<TextFormat>())).thenAnswer {
      (it.arguments[0] as File).path.replace(File.separatorChar, '/')
    }
    return client
  }
}
