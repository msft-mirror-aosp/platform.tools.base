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

package com.android.tools.ui.inspector.printer.text

import com.android.tools.ui.inspector.DeviceConfiguration
import com.android.tools.ui.inspector.TimedUiDump
import com.android.tools.ui.inspector.UiDump
import com.android.tools.ui.inspector.UiNode
import com.android.tools.ui.inspector.printer.SemanticsDisplayMode
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test

class UiDumpDiffPrinterTest {

  @Test
  fun testPrintTrackedChangesUnchangedSamplesPrintNoChanges() {
    val samples = listOf(TimedUiDump(0.milliseconds, dump(width = 10)), TimedUiDump(100.milliseconds, dump(width = 10)))

    val output = captureOutput { printTrackedChanges(samples, it, SemanticsDisplayMode.BOTH) }

    assertThat(output).contains("--- Frame 2 (+100ms) ---")
    assertThat(output).contains(" No changes")
    assertThat(output).doesNotContain("Modified Configuration")
  }

  @Test
  fun testPrintTrackedChangesConfigurationOnlyChangeStillPrintsNoTreeChanges() {
    val samples =
      listOf(
        TimedUiDump(0.milliseconds, dump(width = 10, fontScale = 1.0f)),
        TimedUiDump(100.milliseconds, dump(width = 10, fontScale = 1.5f)),
      )

    val output = captureOutput { printTrackedChanges(samples, it, SemanticsDisplayMode.BOTH) }

    assertThat(output).contains(" Modified Configuration:")
    assertThat(output).contains("Font Scale")
    assertThat(output).contains(" No changes")
  }

  @Test
  fun testPrintTrackedChangesTreeChangePrintsModifiedNodes() {
    val samples = listOf(TimedUiDump(0.milliseconds, dump(width = 10)), TimedUiDump(100.milliseconds, dump(width = 20)))

    val output = captureOutput { printTrackedChanges(samples, it, SemanticsDisplayMode.BOTH) }

    assertThat(output).contains(" Modified nodes:")
    assertThat(output).doesNotContain(" No changes")
  }

  private fun dump(width: Int, fontScale: Float? = null): UiDump {
    val node =
      UiNode.ViewNode(
        id = 1L,
        className = "android.view.View",
        bounds = UiNode.Bounds(0, 0, width, 10),
        idResource = null,
        layoutResource = null,
        attributes = emptyList(),
      )
    return UiDump(
      roots = listOf(node),
      configuration = fontScale?.let { DeviceConfiguration(fontScale = it) },
      stringTable = emptyMap(),
      appContext = null,
    )
  }

  private fun captureOutput(action: (PrintStream) -> Unit): String {
    val outContent = ByteArrayOutputStream()
    action(PrintStream(outContent))
    return outContent.toString(Charsets.UTF_8.name())
  }
}
