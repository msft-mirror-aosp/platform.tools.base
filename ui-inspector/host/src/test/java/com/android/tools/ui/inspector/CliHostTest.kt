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

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import picocli.CommandLine

class CliHostTest {

  @Test
  fun testNoArgsReturnsError() {
    val exitCode = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand()).execute()
    assertThat(exitCode).isEqualTo(1)
  }

  @Test
  fun testDumpUiMissingArgsReturnsError() {
    val exitCode = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand()).execute("dump-ui")
    assertThat(exitCode).isEqualTo(2)
  }

  @Test
  fun testCommandLineOptionsDefaults() {
    val cmd = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand())
    val parseResult = cmd.parseArgs("dump-ui", "--device", "123", "--package", "com.example")
    val dumpCmd = parseResult.subcommand().commandSpec().userObject() as DumpUiCommand
    assertThat(dumpCmd.includeSystemComposables).isFalse()
    assertThat(dumpCmd.includeAttributes).isFalse()
    assertThat(dumpCmd.composeInspectorJarPath).isNull()
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
  fun testListPackagesDeviceOption() {
    val cmd = CommandLine(UiInspectorCommand()).addSubcommand("list-packages", ListPackagesCommand())
    val parseResult = cmd.parseArgs("list-packages", "--device", "123")
    val listCmd = parseResult.subcommand().commandSpec().userObject() as ListPackagesCommand
    assertThat(listCmd.device).isEqualTo("123")
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
