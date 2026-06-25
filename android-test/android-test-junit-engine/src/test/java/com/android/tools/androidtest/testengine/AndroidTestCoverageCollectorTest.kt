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
import java.io.File
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

/** Unit tests for [AndroidTestCoverageCollector]. */
class AndroidTestCoverageCollectorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var adbController: AdbController
  private lateinit var additionalOutputCollector: AndroidAdditionalTestOutputCollector
  private lateinit var coverageDirOnHost: File
  private val deviceSerial = "serial-123"

  @Before
  fun setUp() {
    coverageDirOnHost = tempFolder.newFolder("coverage_host")
    adbController = mock()
    additionalOutputCollector = mock()

    // Default for any adb shell command is failure
    doReturn(AdbController.CommandResult(1, "", "")).`when`(adbController).runAdbShellCommand(anyString(), anyList(), anyOrNull())
  }

  @Test
  fun prepare_cleansPreviousFileOnDevice() {
    val coverageFileOnDevice = "/data/data/pkg/coverage.ec"
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = coverageFileOnDevice,
        coverageDirOnDevice = null,
        useTestStorageService = false,
        additionalOutputCollector,
      )

    // Mock API level and test service check
    mockAdbResponse(listOf("pm", "list", "packages", "androidx.test.services"), "package:other")

    collector.prepare()

    val inOrder = inOrder(adbController)
    inOrder
      .verify(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("pm", "list", "packages", "androidx.test.services")), anyOrNull())
    inOrder.verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("rm", "-f", coverageFileOnDevice)), anyOrNull())
    assertThat(coverageDirOnHost.exists()).isTrue()
  }

  @Test
  fun prepare_cleansPreviousDirOnDevice() {
    val coverageDirOnDevice = "/data/data/pkg/coverage_data/"
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = null,
        coverageDirOnDevice = coverageDirOnDevice,
        useTestStorageService = false,
        additionalOutputCollector,
      )

    mockAdbResponse(listOf("pm", "list", "packages", "androidx.test.services"), "package:other")

    collector.prepare()

    val inOrder = inOrder(adbController)
    inOrder
      .verify(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("pm", "list", "packages", "androidx.test.services")), anyOrNull())
    inOrder.verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("rm", "-rf", coverageDirOnDevice)), anyOrNull())
    inOrder.verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("mkdir", "-p", coverageDirOnDevice)), anyOrNull())
  }

  @Test
  fun prepare_grantsPermissionsForTestStorageService_onApi30Plus() {
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = "coverage.ec",
        coverageDirOnDevice = null,
        useTestStorageService = true,
        additionalOutputCollector,
      )

    mockAdbResponse(listOf("pm", "list", "packages", "androidx.test.services"), "package:androidx.test.services")
    mockAdbResponse(listOf("getprop", "ro.build.version.sdk"), "30")

    collector.prepare()

    verify(adbController)
      .runAdbShellCommand(
        eq(deviceSerial),
        eq(listOf("appops", "set", "androidx.test.services", "MANAGE_EXTERNAL_STORAGE", "allow")),
        anyOrNull(),
      )
  }

  @Test
  fun prepare_cleansCorrectDirWhenTestStorageServiceIsEffective() {
    val coverageDirOnDevice = "/data/data/pkg/coverage_data/"
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = null,
        coverageDirOnDevice = coverageDirOnDevice,
        useTestStorageService = true,
        additionalOutputCollector,
      )

    mockAdbResponse(listOf("pm", "list", "packages", "androidx.test.services"), "package:androidx.test.services")
    mockAdbResponse(listOf("getprop", "ro.build.version.sdk"), "30")

    collector.prepare()

    val storageDir = AndroidAdditionalTestOutputCollector.TEST_STORAGE_SERVICE_INTERNAL_OUTPUT_DIR.removeSuffix("/")
    val prefixedPath = "$storageDir/data/data/pkg/coverage_data/"

    val inOrder = inOrder(adbController)
    inOrder
      .verify(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("pm", "list", "packages", "androidx.test.services")), anyOrNull())
    inOrder.verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.build.version.sdk")), anyOrNull())
    inOrder
      .verify(adbController)
      .runAdbShellCommand(
        eq(deviceSerial),
        eq(listOf("appops", "set", "androidx.test.services", "MANAGE_EXTERNAL_STORAGE", "allow")),
        anyOrNull(),
      )
    inOrder.verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("rm", "-rf", prefixedPath)), anyOrNull())
    inOrder.verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("mkdir", "-p", prefixedPath)), anyOrNull())
  }

  @Test
  fun collect_prioritizesAgentDirectoryPull_overLegacyProperties() {
    val agentDir = "/data/user/0/pkg/code_cache"
    val agentFilesystemInfo =
      CoverageAgentFilesystemInfo().apply {
        agentBinaryPathOnDevice = "/path/to/agent.so"
        dataDirectoryOnDevice = agentDir
      }
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = "/data/data/pkg/coverage.ec",
        coverageDirOnDevice = null,
        useTestStorageService = false,
        additionalOutputCollector,
        agentFilesystemInfo = agentFilesystemInfo,
      )

    collector.collect()

    // Should pull from the agent directory to get hits + metadata
    verify(additionalOutputCollector).pullDirectory(eq(agentDir), eq(coverageDirOnHost), eq(".pb"))
    // Should NOT pull the legacy .ec file
    verify(additionalOutputCollector, never()).pullFile(anyString(), anyOrNull())
  }

  @Test
  fun prepare_cleansAgentDirectory_whenAgentIsPresent() {
    val agentDir = "/data/user/0/pkg/code_cache"
    val agentFilesystemInfo =
      CoverageAgentFilesystemInfo().apply {
        agentBinaryPathOnDevice = "/path/to/agent.so"
        dataDirectoryOnDevice = agentDir
      }
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = null,
        coverageDirOnDevice = null,
        useTestStorageService = false,
        additionalOutputCollector,
        agentFilesystemInfo = agentFilesystemInfo,
      )

    mockAdbResponse(listOf("pm", "list", "packages", "androidx.test.services"), "package:other")

    collector.prepare()

    val inOrder = inOrder(adbController)
    inOrder.verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("rm", "-rf", agentDir)), anyOrNull())
    inOrder.verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("mkdir", "-p", agentDir)), anyOrNull())
  }

  @Test
  fun collect_pullsSingleFile() {
    val coverageFileOnDevice = "/data/data/pkg/coverage.ec"
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = coverageFileOnDevice,
        coverageDirOnDevice = null,
        useTestStorageService = false,
        additionalOutputCollector,
      )

    collector.collect()

    verify(additionalOutputCollector).pullFile(eq(coverageFileOnDevice), eq(File(coverageDirOnHost, "coverage.ec")))
  }

  @Test
  fun collect_pullsDirectory() {
    val coverageDirOnDevice = "/data/data/pkg/coverage_data/"
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = null,
        coverageDirOnDevice = coverageDirOnDevice,
        useTestStorageService = false,
        additionalOutputCollector,
      )

    collector.collect()

    verify(additionalOutputCollector).pullDirectory(eq(coverageDirOnDevice), eq(coverageDirOnHost), eq(".ec"))
  }

  @Test
  fun collect_fallsBackToLegacyDirectoryPull_whenNoSingleFile() {
    val coverageDirOnDevice = "/data/data/pkg/coverage_data/"
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = null,
        coverageDirOnDevice = coverageDirOnDevice,
        useTestStorageService = false,
        additionalOutputCollector,
      )

    collector.collect()

    // Should fall back to directory pull for .ec files
    verify(additionalOutputCollector).pullDirectory(eq(coverageDirOnDevice), eq(coverageDirOnHost), eq(".ec"))
  }

  @Test
  fun collect_pullsFromStorageService_whenEffective() {
    val coverageFileOnDevice = "coverage.ec"
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = coverageFileOnDevice,
        coverageDirOnDevice = null,
        useTestStorageService = true,
        additionalOutputCollector,
      )

    mockAdbResponse(listOf("pm", "list", "packages", "androidx.test.services"), "package:androidx.test.services")
    mockAdbResponse(listOf("getprop", "ro.build.version.sdk"), "30")

    collector.prepare()
    collector.collect()

    val expectedDevicePath =
      "${AndroidAdditionalTestOutputCollector.TEST_STORAGE_SERVICE_INTERNAL_OUTPUT_DIR.removeSuffix("/")}/coverage.ec"
    verify(additionalOutputCollector).pullFile(eq(expectedDevicePath), eq(File(coverageDirOnHost, "coverage.ec")))
  }

  @Test
  fun collect_handlesPullErrorGracefully() {
    val coverageFileOnDevice = "/data/data/pkg/coverage.ec"
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = coverageFileOnDevice,
        coverageDirOnDevice = null,
        useTestStorageService = false,
        additionalOutputCollector,
      )

    doThrow(RuntimeException("Pull failed")).`when`(additionalOutputCollector).pullFile(anyString(), anyOrNull())

    // Should not throw
    collector.collect()

    verify(additionalOutputCollector).pullFile(eq(coverageFileOnDevice), anyOrNull())
  }

  @Test
  fun prepare_warnsIfTestServiceMissing() {
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        coverageDirOnHost,
        coverageFileOnDevice = "coverage.ec",
        coverageDirOnDevice = null,
        useTestStorageService = true,
        additionalOutputCollector,
      )

    mockAdbResponse(listOf("pm", "list", "packages", "androidx.test.services"), "package:other")

    collector.prepare()

    // effectiveUseTestStorageService should be false, so collect should use original path
    collector.collect()
    verify(additionalOutputCollector).pullFile(eq("coverage.ec"), anyOrNull())
  }

  @Test
  fun prepare_refusesUnnormalizedHostDir() {
    val unnormalizedDir = File(coverageDirOnHost, "sub/../../outside")
    val collector =
      AndroidTestCoverageCollector(
        adbController,
        deviceSerial,
        unnormalizedDir,
        coverageFileOnDevice = "/data/data/pkg/coverage.ec",
        coverageDirOnDevice = null,
        useTestStorageService = false,
        additionalOutputCollector,
      )

    // Mock API level and test service check
    mockAdbResponse(listOf("pm", "list", "packages", "androidx.test.services"), "package:other")

    try {
      collector.prepare()
      org.junit.Assert.fail("Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      assertThat(e.message).contains("Refusing deleteRecursively() on un-normalised path")
    }
  }

  private fun mockAdbResponse(args: List<String>, output: String, exitCode: Int = 0) {
    doReturn(AdbController.CommandResult(exitCode, output, ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(args), anyOrNull())
  }
}
