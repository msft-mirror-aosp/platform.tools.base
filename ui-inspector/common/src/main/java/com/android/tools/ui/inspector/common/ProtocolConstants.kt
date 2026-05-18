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

package com.android.tools.ui.inspector.common

/** Shared constants and identifiers for the UI Inspector communication protocol. */
object ProtocolConstants {
  const val SOCKET_NAME_PREFIX = "ui_inspector_"
  const val VIEW_INSPECTOR_ID = "ui.inspector.inspectors.view.inspector"
  const val COMPOSE_INSPECTOR_ID = "layoutinspector.compose.inspection"
  const val COMPOSE_UI_LIBRARY_ID = "androidx.compose.ui:ui"

  /* Returns the unique socket name for a given process ID. */
  fun getSocketName(pid: String): String = "$SOCKET_NAME_PREFIX$pid"
}
