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
}
