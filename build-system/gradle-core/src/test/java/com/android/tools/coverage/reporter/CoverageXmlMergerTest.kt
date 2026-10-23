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
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

class CoverageXmlMergerTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun aLineIsCoveredWhenAnyReportCoversIt() {
    // JaCoCo covers add(), the agent covers subtract(); line 4 (the class header) is missed by both.
    val jacoco =
      report(
        method("add", line = 5, instructions = Counter(covered = 4, missed = 0)) +
          method("subtract", line = 8, instructions = Counter(covered = 0, missed = 4)),
        lines = listOf(line(4, mi = 3, ci = 0), line(5, mi = 0, ci = 4), line(8, mi = 4, ci = 0)),
      )
    val onDevice =
      report(
        method("add", line = 5, instructions = Counter(covered = 0, missed = 2)) +
          method("subtract", line = 8, instructions = Counter(covered = 2, missed = 0)),
        lines = listOf(line(5, mi = 2, ci = 0), line(8, mi = 0, ci = 2)),
      )

    val merged = merge(jacoco, onDevice)

    assertThat(merged.lines()).containsExactly(4, "mi=3 ci=0", 5, "mi=0 ci=4", 8, "mi=0 ci=2")
    assertThat(merged.counter("sourcefile", "LINE")).isEqualTo("missed=1 covered=2")
    assertThat(merged.counter("method[@name='add']", "INSTRUCTION")).isEqualTo("missed=0 covered=4")
    assertThat(merged.counter("method[@name='subtract']", "INSTRUCTION")).isEqualTo("missed=0 covered=2")
  }

  @Test
  fun higherPartialCoverageRatioWinsOverLargerTotalCount() {
    val lowCoverageLargerTotal =
      report(
        method("add", line = 5, instructions = Counter(covered = 1, missed = 9)),
        lines = listOf(line(5, mi = 9, ci = 1), line(6, mi = 2, ci = 2)),
      )
    val highCoverageSmallerTotal =
      report(
        method("add", line = 5, instructions = Counter(covered = 7, missed = 1)),
        lines = listOf(line(5, mi = 1, ci = 7), line(6, mi = 1, ci = 1)),
      )

    for (merged in
      listOf(
        merge(lowCoverageLargerTotal, highCoverageSmallerTotal),
        merge(highCoverageSmallerTotal, lowCoverageLargerTotal),
      )) {
      // Line 5: 7/8 (87.5%) beats 1/10 (10%) even though 10 > 8.
      // Line 6: 2/4 (50%) and 1/2 (50%) have equal coverage ratio, so larger total (2/4) wins.
      assertThat(merged.lines()).containsExactly(5, "mi=1 ci=7", 6, "mi=2 ci=2")
      assertThat(merged.counter("method[@name='add']", "INSTRUCTION")).isEqualTo("missed=1 covered=7")
    }
  }

  @Test
  fun countersAreSummedUpFromTheMergedMethodsAndLines() {
    val jacoco = report(method("add", line = 5, instructions = Counter(covered = 4, missed = 0)), lines = listOf(line(5, mi = 0, ci = 4)))
    val onDevice =
      report(method("subtract", line = 8, instructions = Counter(covered = 0, missed = 2)), lines = listOf(line(8, mi = 2, ci = 0)))

    val merged = merge(jacoco, onDevice)

    assertThat(merged.counter("class", "INSTRUCTION")).isEqualTo("missed=2 covered=4")
    assertThat(merged.counter("class", "METHOD")).isEqualTo("missed=1 covered=1")
    assertThat(merged.counter("package", "INSTRUCTION")).isEqualTo("missed=2 covered=4")
    assertThat(merged.counter("package", "LINE")).isEqualTo("missed=1 covered=1")
    assertThat(merged.counter("report", "INSTRUCTION")).isEqualTo("missed=2 covered=4")
    assertThat(merged.counter("report", "CLASS")).isEqualTo("missed=0 covered=1")
  }

  @Test
  fun classesOfEveryReportAreKept() {
    val app =
      report(method("add", line = 5, instructions = Counter(covered = 1, missed = 0)), className = "com/example/App", sourceFile = "App.kt")
    val lib =
      report(method("sub", line = 3, instructions = Counter(covered = 0, missed = 1)), className = "com/example/Lib", sourceFile = "Lib.kt")

    val merged = merge(app, lib)

    assertThat(merged.classNames()).containsExactly("com/example/App", "com/example/Lib")
  }

  @Test
  fun readsReportsThatDeclareTheJacocoDtd() {
    val jacoco =
      report(method("add", line = 5, instructions = Counter(covered = 1, missed = 0)), lines = listOf(line(5, mi = 0, ci = 1))).apply {
        writeText(readText().replace("<report ", "<!DOCTYPE report PUBLIC \"-//JACOCO//DTD Report 1.1//EN\" \"report.dtd\"><report "))
      }

    val merged = merge(jacoco)

    assertThat(merged.lines()).containsExactly(5, "mi=0 ci=1")
  }

  private fun merge(vararg reports: File): Element {
    val output = tempFolder.newFile()
    CoverageXmlMerger().merge(reports.toList(), "Aggregated", output)
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    return factory.newDocumentBuilder().parse(output).documentElement
  }

  private fun method(name: String, line: Int, instructions: Counter): List<String> =
    listOf(
      """
      <method name="$name" desc="()I" line="$line">
        <counter type="INSTRUCTION" missed="${instructions.missed}" covered="${instructions.covered}"/>
        <counter type="LINE" missed="${if (instructions.covered > 0) 0 else 1}" covered="${if (instructions.covered > 0) 1 else 0}"/>
      </method>
      """
    )

  private fun line(nr: Int, mi: Int, ci: Int) = """<line nr="$nr" mi="$mi" ci="$ci" mb="0" cb="0"/>"""

  private fun report(
    methods: List<String>,
    lines: List<String> = emptyList(),
    className: String = "com/example/Calculator",
    sourceFile: String = "Calculator.kt",
  ): File =
    tempFolder.newFile().apply {
      writeText(
        """
        <report name="test">
          <package name="${className.substringBeforeLast('/')}">
            <class name="$className" sourcefilename="$sourceFile">${methods.joinToString("")}</class>
            <sourcefile name="$sourceFile">${lines.joinToString("")}</sourcefile>
          </package>
        </report>
        """
          .trimIndent()
      )
    }

  private fun Element.children(tagName: String): List<Element> =
    (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }.filter { it.tagName == tagName }

  private fun Element.descendants(tagName: String): List<Element> =
    (0 until getElementsByTagName(tagName).length).map { getElementsByTagName(tagName).item(it) as Element }

  private fun Element.lines(): Map<Int, String> =
    descendants("line").associate { it.getAttribute("nr").toInt() to "mi=${it.getAttribute("mi")} ci=${it.getAttribute("ci")}" }

  private fun Element.classNames(): List<String> = descendants("class").map { it.getAttribute("name") }

  /** Returns the counter of [type] of the element selected by [path]: `report`, `package`, `class`, `sourcefile` or `method[@name='x']`. */
  private fun Element.counter(path: String, type: String): String {
    val element =
      when {
        path == "report" -> this
        path.startsWith("method[@name='") ->
          descendants("method").single { it.getAttribute("name") == path.substringAfter("='").substringBefore("'") }
        else -> descendants(path).single()
      }
    val counter = element.children("counter").single { it.getAttribute("type") == type }
    return "missed=${counter.getAttribute("missed")} covered=${counter.getAttribute("covered")}"
  }
}
