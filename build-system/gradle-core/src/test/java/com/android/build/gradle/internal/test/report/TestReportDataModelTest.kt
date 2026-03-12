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

  private val gson: Gson =
    GsonBuilder()
      .registerTypeAdapter(TestCase::class.java, XMLReportAggregator.TestCaseAdapter())
      .registerTypeAdapter(TestSummary::class.java, XMLReportAggregator.TestSummaryAdapter())
      .create()

  private val emptySummary = TestSummary(0, 0, 0, 0, 0.0, emptyMap())

  @Test
  fun `test TestCase serialization`() {
    val testCase =
      TestCase(name = "testSomething", results = mapOf("debug" to TestResults("pass"), "release" to TestResults("fail", "stacktrace")))

    val jsonString = gson.toJson(testCase)

    assertThat(jsonString).contains("\"name\":\"testSomething\"")
    assertThat(jsonString).contains("\"debug\":\"pass\"")
    assertThat(jsonString).contains("\"release\":{\"status\":\"fail\",\"stackTrace\":\"stacktrace\"}")
  }

  @Test
  fun `test RootReport serialization`() {
    val testCase = TestCase(name = "test1", results = mapOf("debug" to TestResults("pass")))
    val classType = ClassType(name = "MyTest", testCases = listOf(testCase), summary = emptySummary)
    val pkg = Package(name = "com.example", classes = listOf(classType), summary = emptySummary)
    val testSuite = TestSuite(name = "suite1", packages = listOf(pkg), summary = emptySummary)
    val module = Module(name = ":app", testSuites = listOf(testSuite), summary = emptySummary)
    val rootReport =
      RootReport("project", "Mar 4, 2026, 6:09PM", variants = listOf("debug", "release"), modules = listOf(module), summary = emptySummary)

    val jsonString = gson.toJson(rootReport)

    val expectedJson =
      """
      {"projectName":"project","timestamp":"Mar 4, 2026, 6:09PM","variants":["debug","release"],"modules":[{"name":":app","testSuites":[{"name":"suite1","packages":[{"name":"com.example","classes":[{"name":"MyTest","testCases":[{"name":"test1","debug":"pass"}],"summary":{"total":0,"passed":0,"failed":0,"skipped":0,"passRate":0.0}}],"summary":{"total":0,"passed":0,"failed":0,"skipped":0,"passRate":0.0}}],"summary":{"total":0,"passed":0,"failed":0,"skipped":0,"passRate":0.0}}],"summary":{"total":0,"passed":0,"failed":0,"skipped":0,"passRate":0.0}}],"summary":{"total":0,"passed":0,"failed":0,"skipped":0,"passRate":0.0}}
      """
        .trimIndent()
        .replace(Regex("\\s"), "")

    assertThat(jsonString.replace(Regex("\\s"), "")).isEqualTo(expectedJson)
  }
}
