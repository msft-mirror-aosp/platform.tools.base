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
import com.android.adblib.DeviceState
import com.android.adblib.testing.FakeAdbSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import picocli.CommandLine

class CliHostTest {

  private val originalFactory = DumpUiCommand.sessionFactory

  @After
  fun tearDown() {
    DumpUiCommand.sessionFactory = originalFactory
  }

  @Test
  fun testNoArgsReturnsError() {
    val exitCode = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand()).execute()
    assertEquals(1, exitCode)
  }

  @Test
  fun testDumpUiMissingArgsReturnsError() {
    val exitCode = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand()).execute("dump-ui")
    assertEquals(2, exitCode)
  }

  @Test
  fun testDumpUiValidArgsReturnsOk() {
    val fakeSession = FakeAdbSession()
    val deviceSerial = "123"

    // Setup fake device
    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    DumpUiCommand.sessionFactory = { fakeSession }

    val exitCode =
      CommandLine(UiInspectorCommand())
        .addSubcommand("dump-ui", DumpUiCommand())
        .execute("dump-ui", "--serial", deviceSerial, "--package", "com.example")
    assertEquals(0, exitCode)
  }
}
