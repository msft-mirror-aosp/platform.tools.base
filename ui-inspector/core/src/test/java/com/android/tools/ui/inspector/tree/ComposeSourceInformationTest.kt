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

package com.android.tools.ui.inspector.tree

import com.android.tools.ui.inspector.model.UiDump
import com.android.tools.ui.inspector.model.UiNode
import com.android.tools.ui.inspector.model.UiWindow
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComposeSourceInformationTest {

  @Test
  fun testViewsOnlyDumpDoesNotLackSourceInformation() {
    assertThat(lacksComposeSourceInformation(dump(view(1, view(2))))).isFalse()
  }

  @Test
  fun testAppComposablesWithoutLocationLackSourceInformation() {
    // The library composables keep the locations of the library's own source information.
    val tree = view(1, compose(10, compose(11, location = LIBRARY_LOCATION, isSystemCreated = true), compose(12)))

    assertThat(lacksComposeSourceInformation(dump(tree))).isTrue()
  }

  @Test
  fun testAppComposableWithLineNumberHasSourceInformation() {
    // E.g. a library outside the framework packages, built with source information, in an app built without it.
    val tree = view(1, compose(10, compose(11), compose(12, location = UiNode.SourceLocation("Chart.kt", 30))))

    assertThat(lacksComposeSourceInformation(dump(tree))).isFalse()
  }

  @Test
  fun testSystemComposablesOnlyDoNotLackSourceInformation() {
    // E.g. a popup window whose composables are all called from framework code.
    val tree = view(1, compose(10, compose(11, location = LIBRARY_LOCATION, isSystemCreated = true), isSystemCreated = true))

    assertThat(lacksComposeSourceInformation(dump(tree))).isFalse()
  }

  @Test
  fun testLocationWithoutLineNumberIsNotEvidenceOfSourceInformation() {
    val tree = view(1, compose(10, compose(11, location = UiNode.SourceLocation("Main.kt", 0))))

    assertThat(lacksComposeSourceInformation(dump(tree))).isTrue()
  }

  @Test
  fun testComposablesUnderHostedViewsAreChecked() {
    val tree = view(1, compose(10, view(20, compose(30)), isSystemCreated = true))

    assertThat(lacksComposeSourceInformation(dump(tree))).isTrue()
  }

  @Test
  fun testAllWindowsAreChecked() {
    val withoutLocation = view(1, compose(10))
    val withLineNumber = view(2, compose(20, location = UiNode.SourceLocation("Main.kt", 12)))

    assertThat(lacksComposeSourceInformation(dump(withoutLocation, withLineNumber))).isFalse()
    assertThat(lacksComposeSourceInformation(dump(withoutLocation, view(3)))).isTrue()
  }

  private fun dump(vararg roots: UiNode.ViewNode): UiDump =
    UiDump(windows = roots.map { UiWindow(root = it, configuration = null, theme = null) }, displays = emptyList(), warnings = emptyList())

  private fun view(id: Long, vararg children: UiNode): UiNode.ViewNode =
    UiNode.ViewNode(
      id = id,
      className = "View",
      bounds = UiNode.Bounds(0, 0, 10, 10),
      children = children.toList(),
      idResource = null,
      layoutResource = null,
      attributes = emptyList(),
    )

  private fun compose(
    id: Long,
    vararg children: UiNode,
    location: UiNode.SourceLocation? = null,
    isSystemCreated: Boolean = false,
  ): UiNode.ComposeNode =
    UiNode.ComposeNode(
      id = id,
      className = "Composable",
      bounds = UiNode.Bounds(0, 0, 10, 10),
      children = children.toList(),
      sourceLocation = location,
      parameters = emptyList(),
      mergedSemantics = emptyList(),
      unmergedSemantics = emptyList(),
      isSystemCreated = isSystemCreated,
    )

  private companion object {
    val LIBRARY_LOCATION = UiNode.SourceLocation("Text.kt", 125)
  }
}
