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

import com.android.tools.ui.inspector.model.UiNode
import com.google.common.truth.Truth.assertThat
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import org.junit.Test

class CommandsTest {

  @Test
  fun testMergeComposeRootsWarnsWhenTargetViewIsMissing() {
    val viewRoot = viewNode(1)
    val composeRoots = listOf(composableRoot(targetViewId = 999, composableId = 100))
    val logger = RecordingLogger()

    val merged =
      mergeComposeRoots(
        viewRoot,
        composeRoots,
        stringTable = emptyMap(),
        parameters = null,
        includeParameters = false,
        includeSemantics = false,
        logger = logger,
      )

    val (level, message) = logger.messages.single()
    assertThat(level).isEqualTo(LogLevel.WARNING)
    assertThat(message).contains("target view id 999")
    assertThat(message).contains("view root 1")
    // Nothing attached: the tree comes back as it was.
    assertThat(merged).isSameAs(viewRoot)
  }

  @Test
  fun testMergeComposeRootsContinuesAfterFailedAttachment() {
    val composeView = viewNode(20)
    val viewRoot = viewNode(1, composeView)
    val composeRoots = listOf(composableRoot(targetViewId = 999, composableId = 100), composableRoot(targetViewId = 20, composableId = 200))

    val logger = RecordingLogger()
    val merged =
      mergeComposeRoots(
        viewRoot,
        composeRoots,
        stringTable = emptyMap(),
        parameters = null,
        includeParameters = false,
        includeSemantics = false,
        logger = logger,
      )

    // The missing target is warned about; the valid root after it still attaches.
    assertThat(logger.text()).contains("target view id 999")
    val mergedComposeView = merged.children.single { it.id == 20L }
    assertThat(mergedComposeView.children.map { it.id }).containsExactly(200L)
    // The input tree is untouched.
    assertThat(composeView.children).isEmpty()
  }

  @Test
  fun testMergeComposeRootsAttachesNestedRootsInEitherResponseOrder() {
    // The inner AndroidComposeView (30) sits below the outer one (20); both orders must fully attach.
    for (reversed in listOf(false, true)) {
      val innerComposeView = viewNode(30)
      val viewRoot = viewNode(1, viewNode(20, innerComposeView))
      val composeRoots =
        listOf(composableRoot(targetViewId = 20, composableId = 100), composableRoot(targetViewId = 30, composableId = 200))

      val logger = RecordingLogger()
      val merged =
        mergeComposeRoots(
          viewRoot,
          if (reversed) composeRoots.reversed() else composeRoots,
          stringTable = emptyMap(),
          parameters = null,
          includeParameters = false,
          includeSemantics = false,
          logger = logger,
        )

      assertThat(logger.messages).isEmpty()
      val mergedInnerComposeView = merged.children.single { it.id == 20L }.children.single { it.id == 30L }
      assertThat(mergedInnerComposeView.children.map { it.id }).containsExactly(200L)
      assertThat(innerComposeView.children).isEmpty()
    }
  }

  private fun viewNode(id: Long, vararg children: UiNode): UiNode.ViewNode =
    UiNode.ViewNode(
      id = id,
      className = "android.view.View",
      bounds = UiNode.Bounds(0, 0, 100, 100),
      idResource = null,
      layoutResource = null,
      attributes = emptyList(),
      children = children.toList(),
    )

  private fun composableRoot(targetViewId: Long, composableId: Long): LayoutInspectorComposeProtocol.ComposableRoot =
    LayoutInspectorComposeProtocol.ComposableRoot.newBuilder()
      .setViewId(targetViewId)
      .addNodes(LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(composableId))
      .build()
}
