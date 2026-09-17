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

package com.android.tools.ui.inspector.model

/**
 * A UI snapshot whose windows preserve the root tree and metadata captured from the same root context. Window order matches the agent
 * response.
 */
internal data class UiDump(val windows: List<UiWindow>, val displays: List<DisplayInfo>)

/** One app window. Configuration and theme are null when the agent could not provide them; the root is always present. */
internal data class UiWindow(val root: UiNode.ViewNode, val configuration: DeviceConfiguration?, val theme: String?)

/** A physical display. [orientation] is the display rotation in degrees (0, 90, 180, or 270); null when unavailable. */
data class DisplayInfo(val id: Int, val widthPx: Int, val heightPx: Int, val orientation: Int?)
