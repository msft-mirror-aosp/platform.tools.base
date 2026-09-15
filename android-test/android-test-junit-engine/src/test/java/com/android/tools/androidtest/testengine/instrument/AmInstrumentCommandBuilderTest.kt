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

package com.android.tools.androidtest.testengine.instrument

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Unit tests for [AmInstrumentCommandBuilder]. */
@RunWith(JUnit4::class)
class AmInstrumentCommandBuilderTest {

  @Test
  fun build_createsBasicCommand() {
    val command =
      AmInstrumentCommandBuilder()
        .setAdbPath("/path/to/adb")
        .setDeviceSerial("serial-123")
        .setInstrumentationRunner("com.example", "com.example.Runner")
        .build()

    assertThat(command)
      .containsExactly("/path/to/adb", "-s", "serial-123", "shell", "am", "instrument", "-r", "-w", "com.example/com.example.Runner")
      .inOrder()
  }

  @Test
  fun build_withInstrumentationArgs() {
    val command =
      AmInstrumentCommandBuilder()
        .setAdbPath("adb")
        .setDeviceSerial("serial")
        .setInstrumentationRunner("pkg", "runner")
        .addInstrumentationArg("key1", "value1")
        .addInstrumentationArgs(mapOf("key2" to "value2"))
        .build()

    assertThat(command)
      .containsExactly(
        "adb",
        "-s",
        "serial",
        "shell",
        "am",
        "instrument",
        "-r",
        "-w",
        "-e",
        "key1",
        "value1",
        "-e",
        "key2",
        "value2",
        "pkg/runner",
      )
      .inOrder()
  }

  @Test
  fun build_withAndroidxOrchestrator() {
    val command =
      AmInstrumentCommandBuilder()
        .setAdbPath("adb")
        .setDeviceSerial("serial")
        .setInstrumentationRunner("com.example", "Runner")
        .setExecutionMode("ANDROIDX_TEST_ORCHESTRATOR")
        .build()

    assertThat(command)
      .containsExactly(
        "adb",
        "-s",
        "serial",
        "shell",
        "CLASSPATH=$(pm path androidx.test.services)",
        "app_process",
        "/",
        "androidx.test.services.shellexecutor.ShellMain",
        "am",
        "instrument",
        "-r",
        "-w",
        "-e",
        "targetInstrumentation",
        "com.example/Runner",
        "androidx.test.orchestrator/androidx.test.orchestrator.AndroidTestOrchestrator",
      )
      .inOrder()
  }

  @Test
  fun build_withLegacyOrchestrator() {
    val command =
      AmInstrumentCommandBuilder()
        .setAdbPath("adb")
        .setDeviceSerial("serial")
        .setInstrumentationRunner("com.example", "Runner")
        .setExecutionMode("ANDROID_TEST_ORCHESTRATOR")
        .build()

    assertThat(command)
      .containsExactly(
        "adb",
        "-s",
        "serial",
        "shell",
        "CLASSPATH=$(pm path android.support.test.services)",
        "app_process",
        "/",
        "android.support.test.services.shellexecutor.ShellMain",
        "am",
        "instrument",
        "-r",
        "-w",
        "-e",
        "targetInstrumentation",
        "com.example/Runner",
        "android.support.test.orchestrator/android.support.test.orchestrator.AndroidTestOrchestrator",
      )
      .inOrder()
  }

  @Test
  fun build_respectsRawAndWaitFlags() {
    val command =
      AmInstrumentCommandBuilder()
        .setAdbPath("adb")
        .setDeviceSerial("serial")
        .setInstrumentationRunner("pkg", "runner")
        .setRaw(false)
        .setWait(false)
        .build()

    assertThat(command).containsExactly("adb", "-s", "serial", "shell", "am", "instrument", "pkg/runner").inOrder()
    assertThat(command).containsNoneOf("-r", "-w")
  }

  @Test(expected = IllegalStateException::class)
  fun build_throwsIfAdbPathMissing() {
    AmInstrumentCommandBuilder().setDeviceSerial("s").setInstrumentationRunner("p", "r").build()
  }

  @Test(expected = IllegalStateException::class)
  fun build_throwsIfDeviceSerialMissing() {
    AmInstrumentCommandBuilder().setAdbPath("a").setInstrumentationRunner("p", "r").build()
  }

  @Test(expected = IllegalStateException::class)
  fun build_throwsIfRunnerMissing() {
    AmInstrumentCommandBuilder().setAdbPath("a").setDeviceSerial("s").build()
  }

  @Test
  fun build_withInstrumentInPcc() {
    val command =
      AmInstrumentCommandBuilder()
        .setAdbPath("adb")
        .setDeviceSerial("serial")
        .setInstrumentationRunner("pkg", "runner")
        .setInstrumentInPcc(true)
        .build()

    assertThat(command)
      .containsExactly("adb", "-s", "serial", "shell", "am", "instrument", "-r", "-w", "--instrument-in-pcc", "pkg/runner")
      .inOrder()
  }

  @Test
  fun build_shellEscapesRegexMetacharactersInArgValue() {
    // `adb shell` does not forward argv verbatim: it joins the arguments with spaces and the
    // device's `/system/bin/sh` then performs word splitting and quote removal. Unless the value is
    // quoted, the shell strips the backslashes from this `tests_regex` filter and the runner ends up
    // matching nothing.
    val testsRegex = """com.example.FooTestSuite.bar\[.*\]"""

    val command =
      AmInstrumentCommandBuilder()
        .setAdbPath("adb")
        .setDeviceSerial("serial")
        .setInstrumentationRunner("pkg", "runner")
        .addInstrumentationArg("tests_regex", testsRegex)
        .build()

    assertThat(deviceArgvFor(command))
      .containsExactly("am", "instrument", "-r", "-w", "-e", "tests_regex", testsRegex, "pkg/runner")
      .inOrder()
  }

  @Test
  fun build_shellEscapesSpacesInArgValue() {
    val value = "two words"

    val command =
      AmInstrumentCommandBuilder()
        .setAdbPath("adb")
        .setDeviceSerial("serial")
        .setInstrumentationRunner("pkg", "runner")
        .addInstrumentationArg("key", value)
        .build()

    assertThat(deviceArgvFor(command)).containsExactly("am", "instrument", "-r", "-w", "-e", "key", value, "pkg/runner").inOrder()
  }

  @Test
  fun build_shellEscapesCommandSubstitutionInArgValue() {
    val value = "\$(echo substituted)"

    val command =
      AmInstrumentCommandBuilder()
        .setAdbPath("adb")
        .setDeviceSerial("serial")
        .setInstrumentationRunner("pkg", "runner")
        .addInstrumentationArg("key", value)
        .build()

    assertThat(deviceArgvFor(command)).containsExactly("am", "instrument", "-r", "-w", "-e", "key", value, "pkg/runner").inOrder()
  }

  @Test
  fun build_plainArgValuesAreUnaffectedByEscaping() {
    // Must arrive unchanged whether or not the builder chooses to quote it.
    val command =
      AmInstrumentCommandBuilder()
        .setAdbPath("adb")
        .setDeviceSerial("serial")
        .setInstrumentationRunner("pkg", "runner")
        .addInstrumentationArg("class", "com.example.FooTest")
        .build()

    assertThat(deviceArgvFor(command))
      .containsExactly("am", "instrument", "-r", "-w", "-e", "class", "com.example.FooTest", "pkg/runner")
      .inOrder()
  }

  /** Returns the argv that `am instrument` receives on the device for the given built [command]. */
  private fun deviceArgvFor(command: List<String>): List<String> =
    simulateDeviceShell(command.subList(command.indexOf("shell") + 1, command.size))

  /**
   * Simulates the device side of `adb shell`: the arguments are joined with spaces and parsed by `/system/bin/sh`, which performs word
   * splitting and quote removal.
   *
   * Only quoting is modelled, since that is what the builder is responsible for; expansions such as `$(...)` are not evaluated. Valid for
   * the non-orchestrator command only: the orchestrator's `CLASSPATH=$(pm path ...)` prefix relies on shell evaluation and must stay
   * unquoted, which [build_withAndroidxOrchestrator] covers.
   */
  private fun simulateDeviceShell(shellArgs: List<String>): List<String> {
    val line = shellArgs.joinToString(" ")
    val words = mutableListOf<String>()
    val current = StringBuilder()
    var inWord = false
    var i = 0
    while (i < line.length) {
      when (val c = line[i]) {
        ' ' ->
          if (inWord) {
            words.add(current.toString())
            current.setLength(0)
            inWord = false
          }
        '\'' -> {
          inWord = true
          i++
          while (i < line.length && line[i] != '\'') {
            current.append(line[i])
            i++
          }
        }
        '"' -> {
          inWord = true
          i++
          while (i < line.length && line[i] != '"') {
            if (line[i] == '\\' && i + 1 < line.length && line[i + 1] in "\"\\$`") {
              i++
            }
            current.append(line[i])
            i++
          }
        }
        '\\' -> {
          inWord = true
          if (i + 1 < line.length) {
            i++
            current.append(line[i])
          }
        }
        else -> {
          inWord = true
          current.append(c)
        }
      }
      i++
    }
    if (inWord) {
      words.add(current.toString())
    }
    return words
  }
}
