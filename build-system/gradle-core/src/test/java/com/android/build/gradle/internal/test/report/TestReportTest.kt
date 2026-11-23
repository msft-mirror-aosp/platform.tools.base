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

package com.android.build.gradle.internal.test.report

import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.io.Files
import com.google.common.truth.Truth.assertThat as assertObjectThat
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class TestReportTest {
  @get:Rule val tempDirRule = TemporaryFolder()

  private lateinit var resultsOutDir: File
  private lateinit var reportOutDir: File

  @Before
  fun setupDirectory() {
    resultsOutDir = tempDirRule.newFolder()
    reportOutDir = tempDirRule.newFolder()
  }

  private fun createTestReportXmlFile() {
    val reportXml = File(resultsOutDir, "TEST-valid.xml")
    Files.asCharSink(reportXml, Charsets.UTF_8)
      .write(
        """
        <?xml version='1.0' encoding='UTF-8' ?>
        <testsuite name="com.example.myapplication.ExampleInstrumentedTest" tests="8" failures="1" errors="0" skipped="2" time="3.272" timestamp="2021-08-10T21:09:43" hostname="localhost">
          <properties>
            <property name="device" value="Pixel_4_XL_API_30(AVD) - 11" />
            <property name="flavor" value="" />
            <property name="project" value="app" />
          </properties>
          <testcase name="thisTestCaseShouldBeIgnored" classname="com.example.myapplication.ExampleInstrumentedTest" time="0.035">
            <skipped />
          </testcase>
          <testcase name="useAppContext1" classname="com.example.myapplication.ExampleInstrumentedTest" time="0.004" />
          <testcase name="useAppContext2" classname="com.example.myapplication.ExampleInstrumentedTest" time="0.002" />
          <testcase name="useAppContext3" classname="com.example.myapplication.ExampleInstrumentedTest" time="0.003" />
          <testcase name="useAppContext4" classname="com.example.myapplication.ExampleInstrumentedTest" time="0.001" />
          <testcase name="assumptionFailure" classname="com.example.myapplication.ExampleInstrumentedTest" time="0.005">
            <skipped />
          </testcase>
          <testcase name="failedTest" classname="com.example.myapplication.ExampleInstrumentedTest" time="0.0">
            <failure>java.lang.AssertionError: This test fails
        at org.junit.Assert.fail(Assert.java:88)
        at com.example.myapplication.ExampleInstrumentedTest.failedTest(ExampleInstrumentedTest.kt:64)</failure>
          </testcase>
          <testcase name="runActivity" classname="com.example.myapplication.ExampleInstrumentedTest" time="2.34" />
        </testsuite>
        """
          .trimIndent()
      )
  }

  private fun createEmptyTestReportXmlFile() {
    val reportXml = File(resultsOutDir, "TEST-empty.xml")
    Files.asCharSink(reportXml, Charsets.UTF_8)
      .write(
        """
        <?xml version='1.0' encoding='UTF-8' ?>
        <testsuite tests="0" failures="0" errors="0" skipped="0" time="0.518" timestamp="2022-01-12T22:11:43" hostname="localhost">
          <properties>
            <property name="device" value="pixel3_1" />
            <property name="flavor" value="" />
            <property name="project" value=":app" />
          </properties>
        </testsuite>
        """
          .trimIndent()
      )
  }

  private fun createTestReportXmlFileWithToolFailures() {
    val reportXml = File(resultsOutDir, "TEST-empty.xml")
    Files.asCharSink(reportXml, Charsets.UTF_8)
      .write(
        """
        <?xml version='1.0' encoding='UTF-8' ?>
        <testsuite tests="0" failures="0" errors="0" skipped="0" time="0.518" timestamp="2022-01-12T22:11:43" hostname="localhost">
          <properties>
            <property name="device" value="pixel3_1" />
            <property name="flavor" value="" />
            <property name="project" value=":app" />
          </properties>
          <system-err>PLATFORM ERROR</system-err>
        </testsuite>
        """
          .trimIndent()
      )
  }

  private fun createAllSkippedTestReportXmlFile() {
    val reportXml = File(resultsOutDir, "TEST-valid.xml")
    Files.asCharSink(reportXml, Charsets.UTF_8)
      .write(
        """
        <?xml version='1.0' encoding='UTF-8' ?>
        <testsuite name="com.example.myapplication.ExampleInstrumentedTest" tests="2" failures="0" errors="0" skipped="2" time="0.04" timestamp="2021-08-10T21:09:43" hostname="localhost">
          <properties>
            <property name="device" value="Pixel_4_XL_API_30(AVD) - 11" />
            <property name="flavor" value="" />
            <property name="project" value="app" />
          </properties>
          <testcase name="thisTestCaseShouldBeIgnored" classname="com.example.myapplication.ExampleInstrumentedTest" time="0.035">
            <skipped />
          </testcase>
          <testcase name="assumptionFailure" classname="com.example.myapplication.ExampleInstrumentedTest" time="0.005">
            <skipped />
          </testcase>
        </testsuite>
        """
          .trimIndent()
      )
  }

  @Test
  fun generateReport() {
    createTestReportXmlFile()

    TestReport(ReportType.SINGLE_FLAVOR, resultsOutDir, reportOutDir).generateReport()

    val indexHtml = File(reportOutDir, "index.html")
    assertThat(indexHtml).exists()
    assertThat(indexHtml).contains("""<div class="percent">83%</div>""")
    assertThat(indexHtml).contains("""<p>skipped</p>""")
    assertThat(indexHtml).contains("""<p>successful</p>""")

    val moduleHtml = File(reportOutDir, "com.example.myapplication.html")
    assertThat(moduleHtml).exists()
    assertThat(moduleHtml).contains("""<p>skipped</p>""")

    val classHtml = File(reportOutDir, "com.example.myapplication.ExampleInstrumentedTest.html")
    assertThat(classHtml).exists()
    assertThat(classHtml).contains("""<td class="success">passed (0.004s)</td>""")
    assertThat(classHtml).contains("""<td class="failures">failed (0s)</td>""")
    assertThat(classHtml).contains("""<td class="skipped">ignored (-)</td>""")
    assertThat(classHtml).contains("""<p>skipped</p>""")
  }

  @Test
  fun shouldNotGenerateEmptyPackageReportForUnnamedTestSuite() {
    createEmptyTestReportXmlFile()

    TestReport(ReportType.SINGLE_FLAVOR, resultsOutDir, reportOutDir).generateReport()

    val indexHtml = File(reportOutDir, "index.html")
    assertThat(indexHtml).exists()

    val packageHtml = File(reportOutDir, ".html")
    assertThat(packageHtml).doesNotExist()
  }

  @Test
  fun generateReportWithToolFailuresTab() {
    createTestReportXmlFileWithToolFailures()

    TestReport(ReportType.SINGLE_FLAVOR, resultsOutDir, reportOutDir).generateReport()

    val indexHtml = File(reportOutDir, "index.html")
    assertThat(indexHtml).exists()
    assertThat(indexHtml).contains("<h2>Tool failures</h2>")
    assertThat(indexHtml).contains("PLATFORM ERROR")
  }

  @Test
  fun generateReportWithAllSkippedTests() {
    createAllSkippedTestReportXmlFile()

    TestReport(ReportType.SINGLE_FLAVOR, resultsOutDir, reportOutDir).generateReport()

    val indexHtml = File(reportOutDir, "index.html")
    assertThat(indexHtml).exists()
    assertThat(indexHtml).contains("<div class=\"percent\">-</div>")
    assertThat(indexHtml).contains("<p>N/A</p>")
  }

  @Test
  fun generateReportWithNoPropertiesAndToolFailures() {
    val reportXml = File(resultsOutDir, "TEST-no-properties.xml")
    Files.asCharSink(reportXml, Charsets.UTF_8)
      .write(
        """
        <?xml version='1.0' encoding='UTF-8' ?>
        <testsuite tests="0" failures="0" errors="0" skipped="0" time="0.518" timestamp="2022-01-12T22:11:43" hostname="localhost">
          <system-err>PLATFORM ERROR</system-err>
        </testsuite>
        """
          .trimIndent()
      )

    TestReport(ReportType.SINGLE_FLAVOR, resultsOutDir, reportOutDir).generateReport()

    val indexHtml = File(reportOutDir, "index.html")
    assertThat(indexHtml).exists()
    assertThat(indexHtml).contains("PLATFORM ERROR")
  }

  @Test
  fun generateReportSanitizesPathTraversalClassNames() {
    val nestedParentDir = File(reportOutDir, "nested").apply { mkdirs() }
    val nestedReportDir = File(nestedParentDir, "report").apply { mkdirs() }
    val reportXml = File(resultsOutDir, "TEST-traversal.xml")
    Files.asCharSink(reportXml, Charsets.UTF_8)
      .write(
        """
        <?xml version='1.0' encoding='UTF-8' ?>
        <testsuite name="../../escaped.EvilTest" tests="6" failures="0" errors="0" skipped="0" time="0.1" timestamp="2026-05-04T00:00:00" hostname="localhost">
          <properties>
            <property name="device" value="pixel3_1" />
            <property name="flavor" value="" />
            <property name="project" value=":app" />
          </properties>
          <testcase name="unixTraversal" classname="../../escaped.EvilTest" time="0.01" />
          <testcase name="unixTraversalNoDot" classname="../../no_dot_escaped" time="0.01" />
          <testcase name="windowsTraversal" classname="..\..\win_escaped.EvilTest" time="0.01" />
          <testcase name="absolutePath" classname="/abs/path.EvilTest" time="0.01" />
          <testcase name="windowsDrivePath" classname="C:\Windows\evil.Test" time="0.01" />
          <testcase name="dotOnly" classname=".." time="0.01" />
        </testsuite>
        """
          .trimIndent()
      )

    TestReport(ReportType.SINGLE_FLAVOR, resultsOutDir, nestedReportDir).generateReport()

    // Ensure nothing was created outside nestedReportDir (no .html, css/, or js/ siblings).
    assertObjectThat(reportOutDir.list()?.toList()).containsExactly("nested")
    assertObjectThat(nestedParentDir.list()?.toList()).containsExactly("report")

    val indexHtml = File(nestedReportDir, "index.html")
    assertThat(indexHtml).exists()
    assertThat(File(nestedReportDir, ".._.._escaped.html")).exists()
    assertThat(File(nestedReportDir, ".._.._escaped.EvilTest.html")).exists()
    assertThat(File(nestedReportDir, ".._.._no_dot_escaped.html")).exists()
    assertThat(File(nestedReportDir, ".._.._win_escaped.EvilTest.html")).exists()
    assertThat(File(nestedReportDir, "_abs_path.EvilTest.html")).exists()
    assertThat(File(nestedReportDir, "C-_Windows_evil.Test.html")).exists()
    assertThat(File(nestedReportDir, "_.html")).exists()
    assertThat(File(nestedReportDir, "__.html")).exists()
    assertThat(indexHtml).contains("""href=".._.._escaped.EvilTest.html"""")
    assertThat(indexHtml).contains("""href=".._.._win_escaped.EvilTest.html"""")
    assertThat(indexHtml).contains("""href="_abs_path.EvilTest.html"""")
    assertThat(indexHtml).contains("""href="C-_Windows_evil.Test.html"""")
    assertThat(indexHtml).contains("""href="__.html"""")
  }

  @Test
  fun generateScreenshotTestReportSanitizesPathTraversalClassNames() {
    val nestedParentDir = File(reportOutDir, "nested").apply { mkdirs() }
    val nestedReportDir = File(nestedParentDir, "report").apply { mkdirs() }
    val reportXml = File(resultsOutDir, "TEST-screenshot-traversal.xml")
    Files.asCharSink(reportXml, Charsets.UTF_8)
      .write(
        """
        <?xml version='1.0' encoding='UTF-8' ?>
        <testsuite name="../../escaped.EvilScreenshotTest" tests="1" failures="0" errors="0" skipped="0" time="0.1" timestamp="2026-05-04T00:00:00" hostname="localhost">
          <properties>
            <property name="device" value="Previews" />
          </properties>
          <testcase name="t" classname="../../escaped.EvilScreenshotTest" time="0.01">
            <images>
              <golden message="Reference image" />
              <actual message="Actual image" />
              <diff message="Images match" />
            </images>
          </testcase>
        </testsuite>
        """
          .trimIndent()
      )

    TestReport(ReportType.SINGLE_FLAVOR, resultsOutDir, nestedReportDir).generateScreenshotTestReport(false)

    assertObjectThat(reportOutDir.list()?.toList()).containsExactly("nested")
    assertObjectThat(nestedParentDir.list()?.toList()).containsExactly("report")
    assertThat(File(nestedReportDir, "index.html")).exists()
    assertThat(File(nestedReportDir, ".._.._escaped.html")).exists()
    assertThat(File(nestedReportDir, ".._.._escaped.EvilScreenshotTest.html")).exists()
  }

  @Test
  fun generateReportDeduplicatesCollisionsAndReservesIndex() {
    val reportXml = File(resultsOutDir, "TEST-collisions.xml")
    Files.asCharSink(reportXml, Charsets.UTF_8)
      .write(
        """
        <?xml version='1.0' encoding='UTF-8' ?>
        <testsuite name="a/b.Test" tests="6" failures="0" errors="0" skipped="0" time="0.1" timestamp="2026-05-04T00:00:00" hostname="localhost">
          <properties>
            <property name="device" value="pixel3_1" />
            <property name="flavor" value="" />
            <property name="project" value=":app" />
          </properties>
          <testcase name="slash" classname="a/b.Test" time="0.01" />
          <testcase name="backslash" classname="a\b.Test" time="0.01" />
          <testcase name="indexClass" classname="index" time="0.01" />
          <testcase name="indexPackage" classname="INDEX.MyTest" time="0.01" />
          <testcase name="reservedDevice" classname="con.MyTest" time="0.01" />
          <testcase name="urlUnsafe" classname="pkg#1%20.Test#frag%25" time="0.01" />
        </testsuite>
        """
          .trimIndent()
      )

    TestReport(ReportType.SINGLE_FLAVOR, resultsOutDir, reportOutDir).generateReport()

    val indexHtml = File(reportOutDir, "index.html")
    assertThat(indexHtml).exists()
    assertThat(indexHtml).contains("<title>Test results - Test Summary</title>")

    // Default package ("") sorts before "INDEX", so class "index" -> index_2 and package "INDEX" -> INDEX_3.
    assertThat(File(reportOutDir, "index_2.html")).exists()
    assertThat(File(reportOutDir, "INDEX_3.html")).exists()
    assertThat(File(reportOutDir, "INDEX.MyTest.html")).exists()

    // "a/b" and "a\b" sanitize to the same base name -> deduplicated with _2 suffix.
    assertThat(File(reportOutDir, "a_b.html")).exists()
    assertThat(File(reportOutDir, "a_b.Test.html")).exists()
    val collidedPkgHtml = File(reportOutDir, "a_b_2.html")
    val collidedClassHtml = File(reportOutDir, "a_b.Test_2.html")
    assertThat(collidedPkgHtml).exists()
    assertThat(collidedClassHtml).exists()

    // Windows reserved DOS device names and URL-reserved '#' / '%' are sanitized.
    assertThat(File(reportOutDir, "_con.html")).exists()
    assertThat(File(reportOutDir, "_con.MyTest.html")).exists()
    assertThat(File(reportOutDir, "pkg_1_20.html")).exists()
    assertThat(File(reportOutDir, "pkg_1_20.Test_frag_25.html")).exists()

    // Verify href links across overview, package, and class pages match deduplicated filenames.
    assertThat(indexHtml).contains("""href="index_2.html"""")
    assertThat(indexHtml).contains("""href="INDEX_3.html"""")
    assertThat(indexHtml).contains("""href="a_b.html"""")
    assertThat(indexHtml).contains("""href="a_b_2.html"""")
    assertThat(indexHtml).contains("""href="a_b.Test.html"""")
    assertThat(indexHtml).contains("""href="a_b.Test_2.html"""")
    assertThat(indexHtml).contains("""href="_con.html"""")
    assertThat(indexHtml).contains("""href="_con.MyTest.html"""")
    assertThat(indexHtml).contains("""href="pkg_1_20.Test_frag_25.html"""")
    assertThat(collidedPkgHtml).contains("""href="a_b.Test_2.html"""")
    assertThat(collidedClassHtml).contains("""href="a_b_2.html"""")
  }

  @Test
  fun compositeTestResultsGetFilenameSanitizesUnsafeNames() {
    fun filenameFor(rawName: String?): String {
      val results =
        object : CompositeTestResults(null) {
          override fun getTitle(): String = rawName ?: ""

          override fun getName(): String? = rawName
        }
      return results.getFilename(ReportType.SINGLE_FLAVOR)
    }

    assertObjectThat(filenameFor("com.example.MyTest")).isEqualTo("com.example.MyTest")
    assertObjectThat(filenameFor("com.example.MyTest\$Inner")).isEqualTo("com.example.MyTest\$Inner")
    assertObjectThat(filenameFor("com.example.My Test:case")).isEqualTo("com.example.My-Test-case")
    assertObjectThat(filenameFor("../../escaped/Test")).isEqualTo(".._.._escaped_Test")
    assertObjectThat(filenameFor("""..\..\win\Test""")).isEqualTo(".._.._win_Test")
    assertObjectThat(filenameFor(".")).isEqualTo("_")
    assertObjectThat(filenameFor("..")).isEqualTo("__")
    assertObjectThat(filenameFor("...")).isEqualTo("___")
    assertObjectThat(filenameFor("")).isEqualTo("_")
    assertObjectThat(filenameFor(null)).isEqualTo("_")
    assertObjectThat(filenameFor("a\u0000b\u007fc\u0080d\u009fe")).isEqualTo("a_b_c_d_e")
    assertObjectThat(filenameFor("pkg#frag%20Test")).isEqualTo("pkg_frag_20Test")
    assertObjectThat(filenameFor("con")).isEqualTo("_con")
    assertObjectThat(filenameFor("CON.MyTest")).isEqualTo("_CON.MyTest")
    assertObjectThat(filenameFor("nul")).isEqualTo("_nul")
    assertObjectThat(filenameFor("PRN")).isEqualTo("_PRN")
    assertObjectThat(filenameFor("aux.Test")).isEqualTo("_aux.Test")
    assertObjectThat(filenameFor("COM1")).isEqualTo("_COM1")
    assertObjectThat(filenameFor("com9.Test")).isEqualTo("_com9.Test")
    assertObjectThat(filenameFor("LPT1")).isEqualTo("_LPT1")
    assertObjectThat(filenameFor("lpt9.Test")).isEqualTo("_lpt9.Test")
    assertObjectThat(filenameFor("com10.MyTest")).isEqualTo("com10.MyTest")
    assertObjectThat(filenameFor("console.MyTest")).isEqualTo("console.MyTest")

    // Verify UTF-8 byte length stays within 255 - ".html".length (250 bytes),
    // including after Windows-reserved prefixing, post-truncation all-dots guard, and multi-byte UTF-8.
    val longAscii = "a".repeat(300)
    val sanitizedLongAscii = filenameFor(longAscii)
    assertObjectThat(sanitizedLongAscii.toByteArray(Charsets.UTF_8).size).isEqualTo(250)

    val longReserved = "CON." + "a".repeat(246)
    val sanitizedLongReserved = filenameFor(longReserved)
    assertObjectThat(sanitizedLongReserved).startsWith("_CON.")
    assertObjectThat(sanitizedLongReserved.toByteArray(Charsets.UTF_8).size).isEqualTo(250)

    val dotsTruncatedToAllDots = ".".repeat(250) + "a"
    assertObjectThat(filenameFor(dotsTruncatedToAllDots)).isEqualTo("_".repeat(250))

    val longCjk = "\u3042".repeat(100) // 3 bytes each -> 83 chars = 249 bytes
    val sanitizedLongCjk = filenameFor(longCjk)
    assertObjectThat(sanitizedLongCjk.length).isEqualTo(83)
    assertObjectThat(sanitizedLongCjk.toByteArray(Charsets.UTF_8).size).isEqualTo(249)

    val longEmoji = "\uD83D\uDE00".repeat(80) // 4 bytes / 2 UTF-16 chars each -> 62 emoji = 248 bytes
    val sanitizedLongEmoji = filenameFor(longEmoji)
    assertObjectThat(sanitizedLongEmoji.length).isEqualTo(124)
    assertObjectThat(sanitizedLongEmoji.toByteArray(Charsets.UTF_8).size).isEqualTo(248)
  }

  @Test
  fun generateReportBoundsLongAndCollidingFilenamesToNameMax() {
    val longPrefix = "a".repeat(260)
    val longReservedPkg = "CON." + "b".repeat(246)
    val longMultibyte = "\u3042".repeat(100)
    val reportXml = File(resultsOutDir, "TEST-long-names.xml")
    Files.asCharSink(reportXml, Charsets.UTF_8)
      .write(
        """
        <?xml version='1.0' encoding='UTF-8' ?>
        <testsuite name="$longPrefix.TestOne" tests="4" failures="0" errors="0" skipped="0" time="0.1" timestamp="2026-05-04T00:00:00" hostname="localhost">
          <properties>
            <property name="device" value="pixel3_1" />
            <property name="flavor" value="" />
            <property name="project" value=":app" />
          </properties>
          <testcase name="longOne" classname="$longPrefix.TestOne" time="0.01" />
          <testcase name="longTwo" classname="$longPrefix.TestTwo" time="0.01" />
          <testcase name="longReserved" classname="$longReservedPkg.MyTest" time="0.01" />
          <testcase name="longMultibyte" classname="$longMultibyte.MyTest" time="0.01" />
        </testsuite>
        """
          .trimIndent()
      )

    TestReport(ReportType.SINGLE_FLAVOR, resultsOutDir, reportOutDir).generateReport()

    val htmlFiles = reportOutDir.listFiles()?.filter { it.isFile && it.name.endsWith(".html") }.orEmpty()
    // index.html + 3 packages + 4 classes = 8 distinct HTML files, all <= 255 UTF-8 bytes.
    assertObjectThat(htmlFiles).hasSize(8)
    for (file in htmlFiles) {
      assertObjectThat(file.name.toByteArray(Charsets.UTF_8).size).isAtMost(255)
    }
    assertThat(File(reportOutDir, "index.html")).exists()
  }
}
