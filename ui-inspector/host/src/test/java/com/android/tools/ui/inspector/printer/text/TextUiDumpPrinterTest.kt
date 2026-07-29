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
import com.android.tools.ui.inspector.Dimension
import com.android.tools.ui.inspector.DisplayInfo
import com.android.tools.ui.inspector.UiDump
import com.android.tools.ui.inspector.UiNode
import com.android.tools.ui.inspector.UiWindow
import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import org.junit.Test

class TextUiDumpPrinterTest {

  @Test
  fun testPrintDump_twoWindowsInOrderWithPerWindowMetadata() {
    val output = ByteArrayOutputStream()
    val dump =
      UiDump(
        windows =
          listOf(
            UiWindow(
              root = view(1, "DecorView", 1080, 1920),
              configuration = DeviceConfiguration(density = Dimension.Dpi(420), fontScale = 1.0f),
              theme = "@style/Theme.Main",
            ),
            UiWindow(
              root = view(2, "PresentationDecorView", 800, 600),
              configuration = DeviceConfiguration(density = Dimension.Dpi(160)),
              theme = null,
            ),
          ),
        displays =
          listOf(
            DisplayInfo(id = 0, widthPx = 1080, heightPx = 1920, orientation = 0),
            DisplayInfo(id = 1, widthPx = 800, heightPx = 600, orientation = null),
          ),
      )

    TextUiDumpPrinter(PrintStream(output)).printDump(dump)

    val expected =
      """
Displays:
 - Display 0: 1080x1920 px, rotation 0°
 - Display 1: 800x600 px

Window 1:
 Theme: @style/Theme.Main
Device Configuration:
 Density: 420 dpi
 Font Scale: 1.0

View Hierarchy:
[DecorView] (0, 0, 1080, 1920)

Window 2:
Device Configuration:
 Density: 160 dpi

View Hierarchy:
[PresentationDecorView] (0, 0, 800, 600)
"""
        .trim()
    assertThat(output.toString().trim().normalizeLineEndings()).isEqualTo(expected.normalizeLineEndings())
  }

  @Test
  fun testPrintDump_omitsAbsentMetadataWithoutDanglingHeaders() {
    val output = ByteArrayOutputStream()

    TextUiDumpPrinter(PrintStream(output))
      .printDump(
        UiDump(windows = listOf(UiWindow(root = view(1, "View", 10, 10), configuration = null, theme = null)), displays = emptyList())
      )

    val text = output.toString().normalizeLineEndings()
    assertThat(text).doesNotContain("Displays:")
    assertThat(text).doesNotContain("Theme:")
    assertThat(text).doesNotContain("Device Configuration:")
    assertThat(text).contains("Window 1:\nView Hierarchy:")
  }

  private fun view(id: Long, className: String, width: Int, height: Int) =
    UiNode.ViewNode(
      id = id,
      className = className,
      bounds = UiNode.Bounds(0, 0, width, height),
      idResource = null,
      layoutResource = null,
      attributes = emptyList(),
    )

  private fun String.normalizeLineEndings(): String = replace("\r\n", "\n").replace('\r', '\n')
}
