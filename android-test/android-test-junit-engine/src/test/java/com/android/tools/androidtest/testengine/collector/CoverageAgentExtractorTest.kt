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

package com.android.tools.androidtest.testengine.collector

import com.android.tools.androidtest.testengine.adb.AdbController
import com.android.tools.androidtest.testengine.adb.AdbController.CommandResult
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Unit tests for the [CoverageAgentExtractor] class. */
class CoverageAgentExtractorTest {

  @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

  @Mock private lateinit var adbController: AdbController

  private val deviceSerial = "device-1234"

  @Test
  fun `extractAgentIfNeeded runs expected commands`() {
    val extractor = CoverageAgentExtractor(adbController, deviceSerial)

    val abi = "x86_64"
    val apkPath = "/data/app/pkg-1/base.apk"
    val dataDir = "/data/user/10/com.example.app"
    val testPackageId = "com.example.app.test"
    val targetPackageId = "com.example.app"

    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.product.cpu.abi")), anyOrNull()))
      .thenReturn(AdbController.CommandResult(0, abi, ""))
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("pm", "path", testPackageId)), anyOrNull()))
      .thenReturn(AdbController.CommandResult(0, "package:$apkPath", ""))
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("run-as", targetPackageId, "sh", "-c", "pwd")), anyOrNull()))
      .thenReturn(AdbController.CommandResult(0, dataDir, ""))
    whenever(
        adbController.runAdbShellCommand(
          eq(deviceSerial),
          eq(
            listOf(
              "run-as",
              targetPackageId,
              "sh",
              "-c",
              "\"mkdir -p code_cache 2>/dev/null; unzip -p '$apkPath' lib/$abi/coverage_agent.so > code_cache/coverage_agent.so\"",
            )
          ),
          anyOrNull(),
        )
      )
      .thenReturn(AdbController.CommandResult(0, "", ""))
    whenever(
        adbController.runAdbShellCommand(
          eq(deviceSerial),
          eq(listOf("run-as", targetPackageId, "ls", "-l", "code_cache/coverage_agent.so")),
          anyOrNull(),
        )
      )
      .thenReturn(AdbController.CommandResult(0, "-rw------- 1 ... coverage_agent.so", ""))

    val result = extractor.extractAgentIfNeeded(testPackageId, targetPackageId)
    assertThat(result).isEqualTo(Pair("$dataDir/code_cache/coverage_agent.so", "$dataDir/code_cache"))

    verify(adbController)
      .runAdbShellCommand(
        eq(deviceSerial),
        eq(
          listOf(
            "run-as",
            targetPackageId,
            "sh",
            "-c",
            "\"mkdir -p code_cache 2>/dev/null; unzip -p '$apkPath' lib/$abi/coverage_agent.so > code_cache/coverage_agent.so\"",
          )
        ),
        anyOrNull(),
      )
  }

  @Test
  fun `extractAgentIfNeeded falls back to pm dump`() {
    val extractor = CoverageAgentExtractor(adbController, deviceSerial)

    val abi = "arm64-v8a"
    val apkPath = "/data/app/test.apk"
    val dataDir = "/data/user/0/com.example.app"
    val testPackageId = "com.example.app.test"
    val targetPackageId = "com.example.app"

    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.product.cpu.abi")), anyOrNull()))
      .thenReturn(AdbController.CommandResult(0, abi, ""))
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("pm", "path", testPackageId)), anyOrNull()))
      .thenReturn(AdbController.CommandResult(0, "", ""))
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("pm", "dump", testPackageId)), anyOrNull()))
      .thenReturn(AdbController.CommandResult(0, "codePath=$apkPath", ""))
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("run-as", targetPackageId, "sh", "-c", "pwd")), anyOrNull()))
      .thenReturn(AdbController.CommandResult(0, dataDir, ""))
    whenever(
        adbController.runAdbShellCommand(
          eq(deviceSerial),
          eq(
            listOf(
              "run-as",
              targetPackageId,
              "sh",
              "-c",
              "\"mkdir -p code_cache 2>/dev/null; unzip -p '$apkPath' lib/$abi/coverage_agent.so > code_cache/coverage_agent.so\"",
            )
          ),
          anyOrNull(),
        )
      )
      .thenReturn(AdbController.CommandResult(0, "", ""))
    whenever(
        adbController.runAdbShellCommand(
          eq(deviceSerial),
          eq(listOf("run-as", targetPackageId, "ls", "-l", "code_cache/coverage_agent.so")),
          anyOrNull(),
        )
      )
      .thenReturn(AdbController.CommandResult(0, "-rw------- 1 ... coverage_agent.so", ""))

    val result = extractor.extractAgentIfNeeded(testPackageId, targetPackageId)
    assertThat(result).isEqualTo(Pair("$dataDir/code_cache/coverage_agent.so", "$dataDir/code_cache"))
  }
}
