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
import org.junit.After
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
    assertThat(exitCode).isEqualTo(1)
  }

  @Test
  fun testDumpUiMissingArgsReturnsError() {
    val exitCode = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand()).execute("dump-ui")
    assertThat(exitCode).isEqualTo(2)
  }
}
