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

package com.android.tools.ui.inspector

import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceList
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.testing.FakeAdbSession
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import picocli.CommandLine

class CliHostTest {

  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun testNoArgsReturnsError() {
    val exitCode = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand()).execute()
    assertThat(exitCode).isEqualTo(1)
  }

  @Test
  fun testDumpUiParsesWithNoOptions() {
    val cmd = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand())
    val parseResult = cmd.parseArgs("dump-ui")
    val dumpCmd = parseResult.subcommand().commandSpec().userObject() as DumpUiCommand
    assertThat(dumpCmd.device).isNull()
    assertThat(dumpCmd.packageName).isNull()
  }

  @Test
  fun testCommandLineOptionsDefaults() {
    val cmd = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand())
    val parseResult = cmd.parseArgs("dump-ui", "--device", "123", "--package", "com.example")
    val dumpCmd = parseResult.subcommand().commandSpec().userObject() as DumpUiCommand
    assertThat(dumpCmd.includeSystemComposables).isFalse()
    assertThat(dumpCmd.includeAttributes).isFalse()
    assertThat(dumpCmd.composeInspectorJarPath).isNull()
    assertThat(dumpCmd.output).isNull()
  }

  @Test
  fun testCommandLineOptionsFlags() {
    val cmd = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand())
    val parseResult = cmd.parseArgs("dump-ui", "--device", "123", "--package", "com.example", "--include-system-composables")
    val dumpCmd = parseResult.subcommand().commandSpec().userObject() as DumpUiCommand
    assertThat(dumpCmd.includeSystemComposables).isTrue()
  }

  @Test
  fun testCommandLineOptionsComposeInspector() {
    val cmd = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand())
    val parseResult =
      cmd.parseArgs("dump-ui", "--device", "123", "--package", "com.example", "--compose-inspector", "local/path/to/inspector.jar")
    val dumpCmd = parseResult.subcommand().commandSpec().userObject() as DumpUiCommand
    assertThat(dumpCmd.composeInspectorJarPath).isEqualTo("local/path/to/inspector.jar")
  }

  @Test
  fun testCommandLineOptionsOutput() {
    val cmd = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand())
    val parseResult = cmd.parseArgs("dump-ui", "--device", "123", "--package", "com.example", "--output", "out/dump.json")
    val dumpCmd = parseResult.subcommand().commandSpec().userObject() as DumpUiCommand
    assertThat(dumpCmd.output).isEqualTo(Paths.get("out/dump.json"))
  }

  @Test
  fun testCommandLineOptionsOutputShortName() {
    val cmd = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand())
    val parseResult = cmd.parseArgs("dump-ui", "--device", "123", "--package", "com.example", "-o", "dump.json")
    val dumpCmd = parseResult.subcommand().commandSpec().userObject() as DumpUiCommand
    assertThat(dumpCmd.output).isEqualTo(Paths.get("dump.json"))
  }

  @Test
  fun testListPackagesDeviceOption() {
    val cmd = CommandLine(UiInspectorCommand()).addSubcommand("list-packages", ListPackagesCommand())
    val parseResult = cmd.parseArgs("list-packages", "--device", "123")
    val listCmd = parseResult.subcommand().commandSpec().userObject() as ListPackagesCommand
    assertThat(listCmd.device).isEqualTo("123")
  }

  @Test
  fun testDeviceOptionIsOptional() {
    val dumpCmd = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand())
    val dumpParse = dumpCmd.parseArgs("dump-ui", "--package", "com.example")
    assertThat((dumpParse.subcommand().commandSpec().userObject() as DumpUiCommand).device).isNull()

    val trackCmd = CommandLine(UiInspectorCommand()).addSubcommand("track-changes", TrackChangesCommand())
    val trackParse = trackCmd.parseArgs("track-changes", "--package", "com.example")
    assertThat((trackParse.subcommand().commandSpec().userObject() as TrackChangesCommand).device).isNull()

    val listCmd = CommandLine(UiInspectorCommand()).addSubcommand("list-packages", ListPackagesCommand())
    val listParse = listCmd.parseArgs("list-packages")
    assertThat((listParse.subcommand().commandSpec().userObject() as ListPackagesCommand).device).isNull()
  }

  @Test
  fun testDumpUiDeviceResolutionFailureLeavesOutputFileUntouched() {
    val outputFile = tempFolder.newFile("dump.json").toPath()
    Files.write(outputFile, "existing content".toByteArray(Charsets.UTF_8))
    val noDevicesSession = FakeAdbSession().apply { hostServices.devices = DeviceList(emptyList(), emptyList()) }
    val originalFactory = sessionFactory
    sessionFactory = { noDevicesSession }
    try {
      val exitCode =
        CommandLine(UiInspectorCommand())
          .addSubcommand("dump-ui", DumpUiCommand())
          .execute("dump-ui", "--package", "com.example", "-o", outputFile.toString())

      assertThat(exitCode).isEqualTo(1)
      assertThat(String(Files.readAllBytes(outputFile), Charsets.UTF_8)).isEqualTo("existing content")
    } finally {
      sessionFactory = originalFactory
    }
  }

  @Test
  fun testDumpUiPackageResolutionFailureLeavesOutputFileUntouched() {
    val outputFile = tempFolder.newFile("dump.json").toPath()
    Files.write(outputFile, "existing content".toByteArray(Charsets.UTF_8))
    val session =
      FakeAdbSession().apply {
        hostServices.devices = DeviceList(listOf(DeviceInfo("abc", DeviceState.ONLINE)), emptyList())
        deviceServices.configureShellCommand(DeviceSelector.fromSerialNumber("abc"), TOP_ACTIVITY_SHELL_COMMAND, "", exitCode = 1)
      }
    val originalFactory = sessionFactory
    sessionFactory = { session }
    try {
      val exitCode =
        CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand()).execute("dump-ui", "-o", outputFile.toString())

      assertThat(exitCode).isEqualTo(1)
      assertThat(String(Files.readAllBytes(outputFile), Charsets.UTF_8)).isEqualTo("existing content")
    } finally {
      sessionFactory = originalFactory
    }
  }

  @Test
  fun testSerialOptionNoLongerSupported() {
    val dumpCmd = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand())
    assertThrows(CommandLine.UnmatchedArgumentException::class.java) {
      dumpCmd.parseArgs("dump-ui", "--device", "123", "--serial", "456", "--package", "com.example")
    }
    val listCmd = CommandLine(UiInspectorCommand()).addSubcommand("list-packages", ListPackagesCommand())
    assertThrows(CommandLine.UnmatchedArgumentException::class.java) {
      listCmd.parseArgs("list-packages", "--device", "123", "--serial", "456")
    }
  }

  @Test
  fun testTrackChangesOptionsDefaults() {
    val cmd = CommandLine(UiInspectorCommand()).addSubcommand("track-changes", TrackChangesCommand())
    val parseResult = cmd.parseArgs("track-changes", "--device", "123", "--package", "com.example")
    val trackCmd = parseResult.subcommand().commandSpec().userObject() as TrackChangesCommand
    assertThat(trackCmd.includeSystemComposables).isFalse()
    assertThat(trackCmd.includeAttributes).isFalse()
    assertThat(trackCmd.intervalMs).isEqualTo(100)
    assertThat(trackCmd.durationSec).isEqualTo(5)
  }

  @Test
  fun testTrackChangesCustomIntervalAndDuration() {
    val cmd = CommandLine(UiInspectorCommand()).addSubcommand("track-changes", TrackChangesCommand())
    val parseResult = cmd.parseArgs("track-changes", "--device", "123", "--package", "com.example", "--interval", "50", "--duration", "10")
    val trackCmd = parseResult.subcommand().commandSpec().userObject() as TrackChangesCommand
    assertThat(trackCmd.intervalMs).isEqualTo(50)
    assertThat(trackCmd.durationSec).isEqualTo(10)
  }
}
