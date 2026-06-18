/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.google.common.truth.Truth.assertThat
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import org.junit.Test

class TestReportDataModelTest {

  private val gson: Gson = GsonBuilder().create()

  @Test
  fun `test TestCase serialization`() {
    val stackTraceGroup = StackTraceGroup(id = "st-1", stackTrace = "stacktrace", occurrences = mapOf("unitTest" to listOf("release")))

    val testSuiteResult =
      TestSuiteTestResult(
        testSuiteName = "unitTest",
        variantResults =
          mapOf("debug" to VariantTestResult(status = "pass"), "release" to VariantTestResult(status = "fail", stackTraceId = "st-1")),
      )

    val testCase =
      TestCase(
        name = "testSomething",
        testSuiteSummaries = emptyList(),
        testSuiteResults = listOf(testSuiteResult),
        commonStackTraces = listOf(stackTraceGroup),
      )

    val jsonString = gson.toJson(testCase)

    assertThat(jsonString).contains("\"name\":\"testSomething\"")
    assertThat(jsonString).contains("\"testSuiteName\":\"unitTest\"")
    assertThat(jsonString).contains("\"status\":\"pass\"")
    assertThat(jsonString).contains("\"status\":\"fail\"")
    assertThat(jsonString).contains("\"stackTraceId\":\"st-1\"")
    assertThat(jsonString).contains("\"stackTrace\":\"stacktrace\"")
  }

  @Test
  fun `test RootReport serialization`() {
    val testSuiteResult =
      TestSuiteTestResult(testSuiteName = "unitTest", variantResults = mapOf("debug" to VariantTestResult(status = "pass")))
    val testCase =
      TestCase(
        name = "test1",
        testSuiteSummaries = emptyList(),
        testSuiteResults = listOf(testSuiteResult),
        commonStackTraces = emptyList(),
      )
    val classType = ClassType(name = "MyTest", testSuiteSummaries = emptyList(), testCases = listOf(testCase))
    val pkg = Package(name = "com.example", testSuiteSummaries = emptyList(), classes = listOf(classType))
    val module = Module(name = ":app", testSuiteSummaries = emptyList(), packages = listOf(pkg))

    val rootReport =
      RootReport(
        projectName = "project",
        timestamp = "Mar 4, 2026, 6:09PM",
        numberOfModules = 1,
        numberOfPackages = 1,
        numberOfClasses = 1,
        variants = listOf("debug", "release"),
        testSuites = listOf("unitTest"),
        modules = listOf(module),
      )

    val jsonString = gson.toJson(rootReport)

    val expectedJson =
      """
      {"projectName":"project","timestamp":"Mar 4, 2026, 6:09PM","numberOfModules":1,"numberOfPackages":1,"numberOfClasses":1,"variants":["debug","release"],"testSuites":["unitTest"],"modules":[{"name":":app","testSuiteSummaries":[],"packages":[{"name":"com.example","testSuiteSummaries":[],"classes":[{"name":"MyTest","testSuiteSummaries":[],"testCases":[{"name":"test1","testSuiteSummaries":[],"testSuiteResults":[{"testSuiteName":"unitTest","variantResults":{"debug":{"status":"pass"}}}],"commonStackTraces":[]}]}]}]}]}
      """
        .trimIndent()
        .replace(Regex("\\s"), "")

    assertThat(jsonString.replace(Regex("\\s"), "")).isEqualTo(expectedJson)
  }
}
