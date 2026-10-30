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

import com.android.tools.ui.inspector.model.DumpWarning
import com.android.tools.ui.inspector.model.UiDump
import com.android.tools.ui.inspector.model.UiNode

/** The warning of a dump of an app without Compose source information, see [lacksComposeSourceInformation]. */
internal val NO_COMPOSE_SOURCE_INFORMATION_WARNING =
  DumpWarning(
    id = "noComposeSourceInformation",
    message =
      "The app has no Compose source information, so composables have no source location, the composables the app defines are " +
        "missing, some of the composables it calls are left out or show a library file as their location, and semantics are " +
        "unavailable. Build the app with Compose source information: in Gradle, set composeCompiler { includeSourceInformation = true }; " +
        "in google3, build with --define=extra_kt_jvm_opts=\"-P plugin:androidx.compose.compiler.plugins.kotlin:sourceInformation=true\".",
  )

/**
 * Whether the composables of [uiDump] show that the app has no Compose source information: some composable called from the app has no
 * source location, and none has a line number.
 */
internal fun lacksComposeSourceInformation(uiDump: UiDump): Boolean {
  val appComposables = mutableListOf<UiNode.ComposeNode>()
  uiDump.windows.forEach { collectAppComposables(it.root, appComposables) }
  return appComposables.any { it.sourceLocation == null } && appComposables.none { (it.sourceLocation?.lineNumber ?: 0) > 0 }
}

/** Adds to [appComposables] the composables of the tree of [node] that are not system-created, also those under Views they host. */
private fun collectAppComposables(node: UiNode, appComposables: MutableList<UiNode.ComposeNode>) {
  if (node is UiNode.ComposeNode && !node.isSystemCreated) {
    appComposables.add(node)
  }
  node.children.forEach { collectAppComposables(it, appComposables) }
}
