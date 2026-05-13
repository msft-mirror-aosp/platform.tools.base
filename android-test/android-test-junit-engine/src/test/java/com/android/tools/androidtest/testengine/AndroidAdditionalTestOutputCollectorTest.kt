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

import com.android.tools.androidtest.testengine.instrument.TestIdentifier
import com.android.tools.androidtest.testengine.instrument.TestResult
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.charset.StandardCharsets
import java.time.Instant
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.atLeastOnce
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

/** Unit tests for [AndroidAdditionalTestOutputCollector]. */
class AndroidAdditionalTestOutputCollectorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var adbController: AdbController
  private lateinit var hostOutputDir: File
  private val deviceSerial = "serial-123"
  private val deviceOutputDir = "/sdcard/additional_output"

  @Before
  fun setUp() {
    hostOutputDir = tempFolder.newFolder("host_output")
    adbController = mock()

    // Default for any other adb shell command is failure (exitCode 1, empty output)
    // This avoids NPE while preventing infinite recursion from success defaults.
    // NOTE: Order matters for doReturn when stubs overlap.
    doReturn(AdbController.CommandResult(1, "", "")).`when`(adbController).runAdbShellCommand(anyString(), anyList(), anyOrNull())

    // Default for pull is success
    doReturn(AdbController.CommandResult(0, "", "")).`when`(adbController).pull(anyString(), anyString(), anyString(), anyOrNull())
  }

  @Test
  fun prepare_createsDirectories() {
    val collector =
      AndroidAdditionalTestOutputCollector(
        adbController,
        deviceSerial,
        hostOutputDir,
        deviceOutputDir,
        instrumentationTargetPackageId = "instr.pkg",
        testedApplicationId = "pkg",
        useTestStorageService = true,
      )

    // Mock API level 33 for the test
    doReturn(AdbController.CommandResult(0, "33", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.build.version.sdk")), anyOrNull())

    // Mock successful command results for setup commands
    doReturn(AdbController.CommandResult(0, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("rm", "-rf", deviceOutputDir)), anyOrNull())
    doReturn(AdbController.CommandResult(0, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("mkdir", "-p", deviceOutputDir)), anyOrNull())

    // Mock test service installed
    doReturn(AdbController.CommandResult(0, "package:androidx.test.services", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("pm", "list", "packages", "androidx.test.services")), anyOrNull())

    // Mock successful command results for test storage service setup
    doReturn(AdbController.CommandResult(0, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(
        eq(deviceSerial),
        eq(listOf("rm", "-rf", AndroidAdditionalTestOutputCollector.TEST_STORAGE_SERVICE_OUTPUT_DIR)),
        anyOrNull(),
      )
    doReturn(AdbController.CommandResult(0, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(
        eq(deviceSerial),
        eq(listOf("mkdir", "-p", AndroidAdditionalTestOutputCollector.TEST_STORAGE_SERVICE_OUTPUT_DIR)),
        anyOrNull(),
      )

    doReturn(AdbController.CommandResult(0, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(
        eq(deviceSerial),
        eq(listOf("appops", "set", "androidx.test.services", "MANAGE_EXTERNAL_STORAGE", "allow")),
        anyOrNull(),
      )

    collector.prepare()

    verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("rm", "-rf", deviceOutputDir)), anyOrNull())
    verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("mkdir", "-p", deviceOutputDir)), anyOrNull())

    // Verify permissions for test services
    verify(adbController, atLeastOnce())
      .runAdbShellCommand(
        eq(deviceSerial),
        eq(listOf("appops", "set", "androidx.test.services", "MANAGE_EXTERNAL_STORAGE", "allow")),
        anyOrNull(),
      )

    assertThat(hostOutputDir.exists()).isTrue()
  }

  @Test
  fun collect_pullsFiles() {
    val collector =
      AndroidAdditionalTestOutputCollector(
        adbController,
        deviceSerial,
        hostOutputDir,
        deviceOutputDir,
        instrumentationTargetPackageId = "instr.pkg",
        testedApplicationId = "pkg",
        useTestStorageService = false,
      )

    // Mock isDirectory to return true for the deviceOutputDir
    doReturn(AdbController.CommandResult(0, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", deviceOutputDir)), anyOrNull())
    // Mock ls to return a file
    doReturn(AdbController.CommandResult(0, "file1.txt", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("ls", deviceOutputDir)), anyOrNull())
    // Mock isDirectory for the file to return false
    doReturn(AdbController.CommandResult(1, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", "$deviceOutputDir/file1.txt")), anyOrNull())

    // Mock API level check
    doReturn(AdbController.CommandResult(0, "33", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.build.version.sdk")), anyOrNull())

    collector.collect()

    verify(adbController)
      .pull(eq(deviceSerial), eq("$deviceOutputDir/file1.txt"), eq(File(hostOutputDir, "file1.txt").absolutePath), anyOrNull())
  }

  @Test
  fun collect_pullsTestStorageServiceFiles() {
    val collector =
      AndroidAdditionalTestOutputCollector(
        adbController,
        deviceSerial,
        hostOutputDir,
        additionalOutputDirectoryOnDevice = null,
        instrumentationTargetPackageId = "instr.pkg",
        testedApplicationId = "pkg",
        useTestStorageService = true,
      )

    val storageDir = AndroidAdditionalTestOutputCollector.TEST_STORAGE_SERVICE_OUTPUT_DIR
    // Mock isDirectory to return true for the storageDir
    doReturn(AdbController.CommandResult(0, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", storageDir)), anyOrNull())
    // Mock ls to return a file
    doReturn(AdbController.CommandResult(0, "file2.txt", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("ls", storageDir)), anyOrNull())
    // Mock isDirectory for the file to return false
    doReturn(AdbController.CommandResult(1, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", "$storageDir/file2.txt")), anyOrNull())

    // Mock API level check
    doReturn(AdbController.CommandResult(0, "33", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.build.version.sdk")), anyOrNull())

    collector.collect()

    verify(adbController)
      .pull(eq(deviceSerial), eq("$storageDir/file2.txt"), eq(File(hostOutputDir, "file2.txt").absolutePath), anyOrNull())
  }

  @Test
  fun addBenchmarkOutput_writesMessageAndPullsFiles() {
    val collector =
      AndroidAdditionalTestOutputCollector(
        adbController,
        deviceSerial,
        hostOutputDir,
        deviceOutputDir,
        instrumentationTargetPackageId = "instr.pkg",
        testedApplicationId = "pkg",
        useTestStorageService = false,
      )

    val benchmarkOutputDir = "/sdcard/benchmark"
    val testResult =
      TestResult(
        TestIdentifier("pkg", "Cls", "meth"),
        status = -1,
        startTime = Instant.now(),
        endTime = Instant.now(),
        statusBundle =
          mapOf(
            AndroidAdditionalTestOutputCollector.BENCHMARK_V3_TEST_METRICS_KEY to "benchmark: [file](uri://trace.pb)",
            AndroidAdditionalTestOutputCollector.BENCHMARK_V3_PATH_TEST_METRICS_KEY to benchmarkOutputDir,
          ),
      )

    // Mock isDirectory for benchmarkOutputDir
    doReturn(AdbController.CommandResult(0, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", benchmarkOutputDir)), anyOrNull())
    // Mock ls for benchmarkOutputDir
    doReturn(AdbController.CommandResult(0, "trace.pb\nother.txt", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("ls", benchmarkOutputDir)), anyOrNull())
    // Mock isDirectory for file (return 1 for failure, meaning it's not a directory)
    doReturn(AdbController.CommandResult(1, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", "$benchmarkOutputDir/trace.pb")), anyOrNull())
    doReturn(AdbController.CommandResult(1, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", "$benchmarkOutputDir/other.txt")), anyOrNull())

    // Mock API level check
    doReturn(AdbController.CommandResult(0, "33", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.build.version.sdk")), anyOrNull())

    collector.addBenchmarkOutput(testResult)

    val messageFile = File(hostOutputDir, "additionaltestoutput.benchmark.message_pkg.Cls.meth.txt")
    assertThat(messageFile.exists()).isTrue()
    assertThat(messageFile.readText(StandardCharsets.UTF_8)).isEqualTo("[file](uri://trace.pb)")

    verify(adbController)
      .pull(eq(deviceSerial), eq("$benchmarkOutputDir/trace.pb"), eq(File(hostOutputDir, "trace.pb").absolutePath), anyOrNull())
  }
}
