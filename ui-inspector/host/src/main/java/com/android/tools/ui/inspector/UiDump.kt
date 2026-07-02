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

import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import kotlin.time.Duration

/** Represents a complete snapshot of the UI tree, including the root view nodes, device configuration, and string table. */
internal data class UiDump(
  val roots: List<UiNode.ViewNode>,
  val configuration: ViewInspectorProtocol.Configuration?,
  val stringTable: Map<Int, String>,
  val appContext: ViewInspectorProtocol.AppContext?,
)

/** Represents a captured UI hierarchy dump along with the elapsed time since tracking began. */
internal data class TimedUiDump(val elapsedTime: Duration, val uiDump: UiDump)
