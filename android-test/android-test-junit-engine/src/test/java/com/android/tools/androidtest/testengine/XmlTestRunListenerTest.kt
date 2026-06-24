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

package com.android.tools.androidtest.testengine

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unit tests for [XmlTestRunListener]. */
class XmlTestRunListenerTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var reportDir: File
  private lateinit var listener: XmlTestRunListener

  @Before
  fun setUp() {
    reportDir = tempFolder.newFolder("reports")
    listener =
      object : XmlTestRunListener() {
        override fun getPropertiesAttributes(): Map<String, String> {
          return mapOf("device" to "device-123")
        }
      }
    listener.setReportDir(reportDir)
  }

  @Test
  fun `generate empty report`() {
    val out = ByteArrayOutputStream()
    listener.testRunStarted("testRun", 0)
    listener.writeXml(out, "2026-06-24T12:00:00", 1500L)

    val xml = out.toString(Charsets.UTF_8.name())
    assertThat(xml).contains("<?xml version='1.0' encoding='UTF-8' ?>")
    assertThat(xml)
      .contains(
        "<testsuites tests=\"0\" failures=\"0\" errors=\"0\" skipped=\"0\" time=\"1.500\" timestamp=\"2026-06-24T12:00:00\" hostname=\"localhost\" />"
      )
  }

  @Test
  fun `generate report with passed and failed tests`() {
    val out = ByteArrayOutputStream()
    listener.testRunStarted("testRun", 2)

    val test1 = DdmlibTestIdentifier("com.example.FooTest", "testPass")
    listener.testStarted(test1)
    listener.testEnded(test1, emptyMap())

    val test2 = DdmlibTestIdentifier("com.example.FooTest", "testFail")
    listener.testStarted(test2)
    listener.testFailed(test2, "AssertionError: expected true")
    listener.testEnded(test2, emptyMap())

    listener.writeXml(out, "2026-06-24T12:00:00", 3000L)

    val xml = out.toString(Charsets.UTF_8.name())
    assertThat(xml)
      .contains(
        "<testsuites tests=\"2\" failures=\"1\" errors=\"0\" skipped=\"0\" time=\"3.000\" timestamp=\"2026-06-24T12:00:00\" hostname=\"localhost\">"
      )
    assertThat(xml).contains("<testsuite name=\"com.example.FooTest\" tests=\"2\" failures=\"1\" errors=\"0\" skipped=\"0\"")
    assertThat(xml).contains("<property name=\"device\" value=\"device-123\" />")
    assertThat(xml).contains("<testcase name=\"testPass\" classname=\"com.example.FooTest\"")
    assertThat(xml).contains("<testcase name=\"testFail\" classname=\"com.example.FooTest\"")
    assertThat(xml).contains("<failure>AssertionError: expected true</failure>")
  }
}
