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
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

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
}
