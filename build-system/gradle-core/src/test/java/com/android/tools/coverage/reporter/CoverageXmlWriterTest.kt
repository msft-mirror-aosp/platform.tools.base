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

package com.android.tools.coverage.reporter

import com.google.common.truth.Truth.assertThat
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

class CoverageXmlWriterTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun testXmlStructure() {
    val report = ReportModel("debug")
    val pkg = report.packages.getOrPut("com/example") { PackageModel("com/example") }
    val cls = pkg.classes.getOrPut("com/example/MyClass") { ClassModel("com/example/MyClass", "MyClass.kt") }

    val method = MethodModel("doWork", "()V", 10)
    method.instructions.covered = 10
    method.methodsCounter.covered = 1
    cls.methods.add(method)

    // Aggregate upwards manually for this unit test
    cls.instructions.covered = 10
    cls.methodsCounter.covered = 1
    cls.classesCounter.covered = 1

    pkg.instructions.covered = 10
    pkg.methodsCounter.covered = 1
    pkg.classesCounter.covered = 1

    report.instructions.covered = 10
    report.methodsCounter.covered = 1
    report.classesCounter.covered = 1

    val outputFile = tempFolder.newFile("report.xml")
    CoverageXmlWriter().write(report, outputFile)

    val content = outputFile.readText()

    // Verify root and sessioninfo
    assertThat(content).contains("<report name=\"debug\">")
    assertThat(content).contains("<sessioninfo")

    // Verify method counters (no CLASS counter inside method)
    val methodSection = content.substringAfter("<method").substringBefore("</method>")
    assertThat(methodSection).contains("<counter type=\"INSTRUCTION\" missed=\"0\" covered=\"10\"/>")
    assertThat(methodSection).contains("<counter type=\"METHOD\" missed=\"0\" covered=\"1\"/>")
    assertThat(methodSection).doesNotContain("type=\"CLASS\"")

    // Verify class counters (includes CLASS counter)
    val classSection = content.substringAfter("<class").substringBefore("</class>")
    assertThat(classSection).contains("<counter type=\"CLASS\" missed=\"0\" covered=\"1\"/>")
  }

  @Test
  fun testXmlEscaping() {
    val report = ReportModel("debug")
    val pkg = report.packages.getOrPut("com/example") { PackageModel("com/example") }
    val cls = pkg.classes.getOrPut("com/example/MyClass") { ClassModel("com/example/MyClass", "MyClass.kt") }

    // Method with characters that need escaping
    val method = MethodModel("<init>", "()V", 1)
    cls.methods.add(method)

    val outputFile = tempFolder.newFile("escape.xml")
    CoverageXmlWriter().write(report, outputFile)

    val content = outputFile.readText()
    assertThat(content).contains("name=\"&lt;init&gt;\"")
  }

  @Test
  fun testZeroCountersAreOmitted() {
    val report = ReportModel("debug")
    val coveredPkg = report.packages.getOrPut("com/example") { PackageModel("com/example") }
    val coveredCls = coveredPkg.classes.getOrPut("com/example/Covered") { ClassModel("com/example/Covered", "Covered.kt") }
    val method =
      MethodModel("check", "(Z)V", 10).apply {
        instructions.covered = 8
        branches.missed = 2
        lines.covered = 2
        methodsCounter.covered = 1
      }
    coveredCls.methods.add(method)
    for (aggregate in listOf(coveredCls, coveredPkg, report)) {
      aggregate.instructions.covered = 8
      aggregate.branches.missed = 2
      aggregate.lines.covered = 2
      aggregate.methodsCounter.covered = 1
      aggregate.classesCounter.covered = 1
    }

    // Empty package, class and source file whose counters are all 0/0.
    val emptyPkg = report.packages.getOrPut("com/example/empty") { PackageModel("com/example/empty") }
    emptyPkg.classes["com/example/empty/Empty"] = ClassModel("com/example/empty/Empty", "Empty.kt")
    emptyPkg.sourceFiles["Empty.kt"] = SourceFileModel("Empty.kt")
    coveredPkg.classes["com/example/Companion"] = ClassModel("com/example/Companion", "Covered.kt")

    val outputFile = tempFolder.newFile("zero.xml")
    CoverageXmlWriter().write(report, outputFile)

    val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(outputFile).documentElement
    val allCounters = doc.descendants("counter")
    assertThat(allCounters).isNotEmpty()
    for (counter in allCounters) {
      val total = counter.getAttribute("missed").toInt() + counter.getAttribute("covered").toInt()
      assertThat(total).isGreaterThan(0)
    }

    val coveredClassCounters =
      doc
        .descendants("class")
        .single { it.getAttribute("name") == "com/example/Covered" }
        .childCounters()
        .associate { it.getAttribute("type") to "${it.getAttribute("missed")}/${it.getAttribute("covered")}" }
    assertThat(coveredClassCounters)
      .containsExactlyEntriesIn(mapOf("INSTRUCTION" to "0/8", "BRANCH" to "2/0", "LINE" to "0/2", "METHOD" to "0/1", "CLASS" to "0/1"))
      .inOrder()

    for (emptyElement in
      listOf(
        doc.descendants("class").single { it.getAttribute("name") == "com/example/Companion" },
        doc.descendants("class").single { it.getAttribute("name") == "com/example/empty/Empty" },
        doc.descendants("sourcefile").single { it.getAttribute("name") == "Empty.kt" },
        doc.descendants("package").single { it.getAttribute("name") == "com/example/empty" },
      )) {
      assertThat(emptyElement.childCounters()).isEmpty()
    }

    // CoverageXmlMerger still reads the elements whose 0/0 counters were omitted.
    val mergedFile = tempFolder.newFile("merged.xml")
    CoverageXmlMerger().merge(listOf(outputFile), "merged", mergedFile)
    val mergedDoc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(mergedFile).documentElement
    assertThat(mergedDoc.descendants("class").map { it.getAttribute("name") })
      .containsExactly("com/example/Covered", "com/example/Companion", "com/example/empty/Empty")
  }

  private fun Element.descendants(tagName: String): List<Element> =
    getElementsByTagName(tagName).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

  private fun Element.childCounters(): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == "counter" }
}
