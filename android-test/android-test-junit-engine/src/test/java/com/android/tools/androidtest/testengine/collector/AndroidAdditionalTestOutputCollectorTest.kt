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
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

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

    // Mock pull to actually create the file so it exists when we check it.
    val expectedTraceFile = File(hostOutputDir, "trace.pb")
    whenever(adbController.pull(eq(deviceSerial), eq("$benchmarkOutputDir/trace.pb"), eq(expectedTraceFile.absolutePath), anyOrNull()))
      .thenAnswer {
        expectedTraceFile.createNewFile()
        AdbController.CommandResult(0, "", "")
      }

    val result = collector.addBenchmarkOutput(testResult)

    val messageFile = File(hostOutputDir, "additionaltestoutput.benchmark.message_pkg.Cls.meth.txt")
    assertThat(messageFile.exists()).isTrue()
    assertThat(messageFile.readText(StandardCharsets.UTF_8)).isEqualTo("[file](uri://trace.pb)")

    verify(adbController).pull(eq(deviceSerial), eq("$benchmarkOutputDir/trace.pb"), eq(expectedTraceFile.absolutePath), anyOrNull())

    assertThat(result.traceFiles).containsExactly(expectedTraceFile)
    assertThat(result.messageFile).isEqualTo(messageFile)
  }

  @Test
  fun prepare_refusesUnnormalizedHostDir() {
    val unnormalizedDir = File(hostOutputDir, "sub/../../outside")
    val collector =
      AndroidAdditionalTestOutputCollector(
        adbController,
        deviceSerial,
        unnormalizedDir,
        deviceOutputDir,
        instrumentationTargetPackageId = "instr.pkg",
        testedApplicationId = "pkg",
        useTestStorageService = false,
      )

    // Mock API level 33 for the test
    doReturn(AdbController.CommandResult(0, "33", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.build.version.sdk")), anyOrNull())

    try {
      collector.prepare()
      org.junit.Assert.fail("Expected IllegalArgumentException")
    } catch (e: IllegalArgumentException) {
      assertThat(e.message).contains("Refusing deleteRecursively() on un-normalised path")
    }
  }

  @Test
  fun collect_skipsAllDotsFiles() {
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
    // Mock ls to return a malicious path element containing only dots
    doReturn(AdbController.CommandResult(0, "..", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("ls", deviceOutputDir)), anyOrNull())
    // Mock isDirectory for the file to return false
    doReturn(AdbController.CommandResult(1, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", "$deviceOutputDir/..")), anyOrNull())

    // Mock API level check
    doReturn(AdbController.CommandResult(0, "33", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.build.version.sdk")), anyOrNull())

    collector.collect()

    // Verify pull was never called
    org.mockito.Mockito.verify(adbController, org.mockito.Mockito.never()).pull(anyString(), anyString(), anyString(), anyOrNull())
  }

  @Test
  fun pullDirectory_sanitizesAndBlocksTraversal() {
    val collector =
      AndroidAdditionalTestOutputCollector(
        adbController,
        deviceSerial,
        hostOutputDir,
        additionalOutputDirectoryOnDevice = null,
        instrumentationTargetPackageId = "instr.pkg",
        testedApplicationId = "pkg",
        useTestStorageService = false,
      )

    val deviceDir = "/sdcard/files"
    // Mock ls to return files, including malicious ones
    doReturn(AdbController.CommandResult(0, "safe.txt\n..\nsub/../../evil.txt", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("ls", deviceDir)), anyOrNull())

    collector.pullDirectory(deviceDir, hostOutputDir)

    // verify safe.txt is pulled
    verify(adbController).pull(eq(deviceSerial), eq("$deviceDir/safe.txt"), eq(File(hostOutputDir, "safe.txt").absolutePath), anyOrNull())

    // verify ".." is not pulled
    org.mockito.Mockito.verify(adbController, org.mockito.Mockito.never())
      .pull(eq(deviceSerial), eq("$deviceDir/.."), anyString(), anyOrNull())

    // verify "sub/../../evil.txt" is sanitized and pulled as "sub_.._.._evil.txt"
    verify(adbController)
      .pull(eq(deviceSerial), eq("$deviceDir/sub/../../evil.txt"), eq(File(hostOutputDir, "sub_.._.._evil.txt").absolutePath), anyOrNull())
  }

  @Test
  fun addBenchmarkOutput_escapedPathsAreIgnored() {
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
            AndroidAdditionalTestOutputCollector.BENCHMARK_V3_TEST_METRICS_KEY to
              "benchmark: [file](uri://../evil.txt)\n[file](uri://safe.pb)",
            AndroidAdditionalTestOutputCollector.BENCHMARK_V3_PATH_TEST_METRICS_KEY to benchmarkOutputDir,
          ),
      )

    // Mock isDirectory for benchmarkOutputDir
    doReturn(AdbController.CommandResult(0, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", benchmarkOutputDir)), anyOrNull())
    // Mock ls for benchmarkOutputDir
    doReturn(AdbController.CommandResult(0, "../evil.txt\nsafe.pb", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("ls", benchmarkOutputDir)), anyOrNull())
    // Mock isDirectory for files (return 1 for failure, meaning it's not a directory)
    doReturn(AdbController.CommandResult(1, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", "$benchmarkOutputDir/../evil.txt")), anyOrNull())
    doReturn(AdbController.CommandResult(1, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", "$benchmarkOutputDir/safe.pb")), anyOrNull())

    // Mock API level check
    doReturn(AdbController.CommandResult(0, "33", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.build.version.sdk")), anyOrNull())

    // Mock pull for safe.pb
    val expectedSafeFile = File(hostOutputDir, "safe.pb")
    whenever(adbController.pull(eq(deviceSerial), eq("$benchmarkOutputDir/safe.pb"), eq(expectedSafeFile.absolutePath), anyOrNull()))
      .thenAnswer {
        expectedSafeFile.createNewFile()
        AdbController.CommandResult(0, "", "")
      }

    // Mock pull for ../evil.txt (should not be called, but if it is, we want to know)
    val unexpectedEvilFile = File(hostOutputDir.parentFile, "evil.txt")
    whenever(adbController.pull(eq(deviceSerial), eq("$benchmarkOutputDir/../evil.txt"), eq(unexpectedEvilFile.absolutePath), anyOrNull()))
      .thenAnswer {
        unexpectedEvilFile.createNewFile()
        AdbController.CommandResult(0, "", "")
      }

    val result = collector.addBenchmarkOutput(testResult)

    // verify safe.pb is pulled
    verify(adbController).pull(eq(deviceSerial), eq("$benchmarkOutputDir/safe.pb"), eq(expectedSafeFile.absolutePath), anyOrNull())
    // verify ../evil.txt is NOT pulled to the parent directory
    org.mockito.Mockito.verify(adbController, org.mockito.Mockito.never())
      .pull(eq(deviceSerial), eq("$benchmarkOutputDir/../evil.txt"), eq(unexpectedEvilFile.absolutePath), anyOrNull())

    assertThat(result.traceFiles).containsExactly(expectedSafeFile)
  }

  @Test
  fun addBenchmarkOutput_pullsNamedFilesWithoutListingDirectory() {
    val collector = createCollector()
    val benchmarkOutputDir = "/sdcard/benchmark"
    mockApiLevel(33)
    val traceFile = mockPullCreatesFile("$benchmarkOutputDir/a.trace", File(hostOutputDir, "a.trace"))
    val perfettoFile = mockPullCreatesFile("$benchmarkOutputDir/b.perfetto-trace", File(hostOutputDir, "b.perfetto-trace"))

    val result =
      collector.addBenchmarkOutput(
        benchmarkTestResult(
          "benchmark: [method](file://a.trace)\n[perfetto](uri://b.perfetto-trace)\n" +
            "[insight](uri://b.perfetto-trace?selectionParams=abc)\n[absolute](file:///sdcard/abs.trace)",
          benchmarkOutputDir,
        )
      )

    assertThat(result.traceFiles).containsExactly(traceFile, perfettoFile)
    // Only the two named files are pulled; links with a query or an absolute path are not files in the output directory.
    verify(adbController, times(2)).pull(anyString(), anyString(), anyString(), anyOrNull())
    // The output directory is neither listed nor walked entry by entry (b/578508685).
    verify(adbController, never()).runAdbShellCommand(eq(deviceSerial), eq(listOf("ls", benchmarkOutputDir)), anyOrNull())
    verify(adbController, never())
      .runAdbShellCommand(eq(deviceSerial), argThat<List<String>> { take(2) == listOf("test", "-d") }, anyOrNull())
  }

  @Test
  fun collect_skipsFilesAlreadyPulledByBenchmarkOutput() {
    val collector = createCollector()
    mockApiLevel(33)
    val traceFile = mockPullCreatesFile("$deviceOutputDir/trace.pb", File(hostOutputDir, "trace.pb"))
    collector.addBenchmarkOutput(benchmarkTestResult("benchmark: [trace](uri://trace.pb)", deviceOutputDir))

    doReturn(AdbController.CommandResult(0, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", deviceOutputDir)), anyOrNull())
    doReturn(AdbController.CommandResult(0, "trace.pb\npkg-benchmarkData.json", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("ls", deviceOutputDir)), anyOrNull())

    collector.collect()

    verify(adbController, times(1)).pull(eq(deviceSerial), eq("$deviceOutputDir/trace.pb"), eq(traceFile.absolutePath), anyOrNull())
    verify(adbController, never()).runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", "$deviceOutputDir/trace.pb")), anyOrNull())
    // Files that no test linked to, like the benchmark JSON written at the end of the run, are still collected.
    verify(adbController)
      .pull(
        eq(deviceSerial),
        eq("$deviceOutputDir/pkg-benchmarkData.json"),
        eq(File(hostOutputDir, "pkg-benchmarkData.json").absolutePath),
        anyOrNull(),
      )
  }

  @Test
  fun collect_retriesBenchmarkFilesThatFailedToPull() {
    val collector = createCollector()
    mockApiLevel(33)
    val hostTraceFile = File(hostOutputDir, "trace.pb")
    doReturn(AdbController.CommandResult(1, "", "error"))
      .`when`(adbController)
      .pull(eq(deviceSerial), eq("$deviceOutputDir/trace.pb"), eq(hostTraceFile.absolutePath), anyOrNull())
    val result = collector.addBenchmarkOutput(benchmarkTestResult("benchmark: [trace](uri://trace.pb)", deviceOutputDir))
    assertThat(result.traceFiles).isEmpty()

    doReturn(AdbController.CommandResult(0, "", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("test", "-d", deviceOutputDir)), anyOrNull())
    doReturn(AdbController.CommandResult(0, "trace.pb", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("ls", deviceOutputDir)), anyOrNull())

    collector.collect()

    verify(adbController, times(2)).pull(eq(deviceSerial), eq("$deviceOutputDir/trace.pb"), eq(hostTraceFile.absolutePath), anyOrNull())
  }

  @Test
  fun addBenchmarkOutput_secondaryUserUsesContentProvider() {
    val collector = createCollector()
    val benchmarkOutputDir = "/sdcard/benchmark"
    mockApiLevel(33)
    doReturn(AdbController.CommandResult(0, "10", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("am", "get-current-user")), anyOrNull())
    doReturn(AdbController.CommandResult(0, "Row: 0 _id=42, _data=/storage/emulated/10/benchmark/trace.pb", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), argThat<List<String>> { take(2) == listOf("content", "query") }, anyOrNull())

    val result = collector.addBenchmarkOutput(benchmarkTestResult("benchmark: [trace](uri://trace.pb)", benchmarkOutputDir))

    assertThat(result.traceFiles).containsExactly(File(hostOutputDir, "trace.pb"))
    verify(adbController)
      .runAdbShellCommandToOutputStream(
        eq(deviceSerial),
        eq(listOf("content", "read", "--user", "10", "--uri", "content://media/external/file/42")),
        org.mockito.kotlin.any(),
        anyOrNull(),
      )
    verify(adbController, never()).pull(anyString(), anyString(), anyString(), anyOrNull())
  }

  @Test
  fun addBenchmarkOutput_queriesCurrentUserOnlyOnce() {
    val collector = createCollector()
    val benchmarkOutputDir = "/sdcard/benchmark"
    mockApiLevel(33)
    doReturn(AdbController.CommandResult(0, "0", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("am", "get-current-user")), anyOrNull())

    collector.addBenchmarkOutput(benchmarkTestResult("benchmark: [trace](uri://a.pb)", benchmarkOutputDir))
    collector.addBenchmarkOutput(benchmarkTestResult("benchmark: [trace](uri://b.pb)", benchmarkOutputDir))

    verify(adbController, times(1)).runAdbShellCommand(eq(deviceSerial), eq(listOf("am", "get-current-user")), anyOrNull())
  }

  @Test
  fun addBenchmarkOutput_doesNotCacheFailedCurrentUserQuery() {
    val collector = createCollector()
    val benchmarkOutputDir = "/sdcard/benchmark"
    mockApiLevel(33)
    // The first query fails transiently (empty output); the second returns the secondary user.
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("am", "get-current-user")), anyOrNull()))
      .thenReturn(AdbController.CommandResult(1, "", "error"), AdbController.CommandResult(0, "10", ""))
    doReturn(AdbController.CommandResult(0, "Row: 0 _id=42, _data=/storage/emulated/10/benchmark/b.pb", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), argThat<List<String>> { take(2) == listOf("content", "query") }, anyOrNull())

    // Falls back to the primary user path for this test only.
    collector.addBenchmarkOutput(benchmarkTestResult("benchmark: [trace](uri://a.pb)", benchmarkOutputDir))
    verify(adbController).pull(eq(deviceSerial), eq("$benchmarkOutputDir/a.pb"), anyString(), anyOrNull())

    // The next test queries the user again and uses the content provider for the secondary user.
    collector.addBenchmarkOutput(benchmarkTestResult("benchmark: [trace](uri://b.pb)", benchmarkOutputDir))
    verify(adbController)
      .runAdbShellCommandToOutputStream(
        eq(deviceSerial),
        eq(listOf("content", "read", "--user", "10", "--uri", "content://media/external/file/42")),
        org.mockito.kotlin.any(),
        anyOrNull(),
      )
    verify(adbController, never()).pull(eq(deviceSerial), eq("$benchmarkOutputDir/b.pb"), anyString(), anyOrNull())
  }

  private fun createCollector() =
    AndroidAdditionalTestOutputCollector(
      adbController,
      deviceSerial,
      hostOutputDir,
      deviceOutputDir,
      instrumentationTargetPackageId = "instr.pkg",
      testedApplicationId = "pkg",
      useTestStorageService = false,
    )

  private fun mockApiLevel(apiLevel: Int) {
    doReturn(AdbController.CommandResult(0, "$apiLevel", ""))
      .`when`(adbController)
      .runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop", "ro.build.version.sdk")), anyOrNull())
  }

  /** Makes `adb pull [devicePath] [hostFile]` succeed and create [hostFile], like the real command. */
  private fun mockPullCreatesFile(devicePath: String, hostFile: File): File {
    whenever(adbController.pull(eq(deviceSerial), eq(devicePath), eq(hostFile.absolutePath), anyOrNull())).thenAnswer {
      hostFile.createNewFile()
      AdbController.CommandResult(0, "", "")
    }
    return hostFile
  }

  private fun benchmarkTestResult(benchmarkMessage: String, benchmarkOutputDir: String) =
    TestResult(
      TestIdentifier("pkg", "Cls", "meth"),
      status = -1,
      startTime = Instant.now(),
      endTime = Instant.now(),
      statusBundle =
        mapOf(
          AndroidAdditionalTestOutputCollector.BENCHMARK_V3_TEST_METRICS_KEY to benchmarkMessage,
          AndroidAdditionalTestOutputCollector.BENCHMARK_V3_PATH_TEST_METRICS_KEY to benchmarkOutputDir,
        ),
    )
}
