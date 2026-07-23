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

/** The set of changes between two sequential [UiDump] samples. An absent part means that part did not change. */
internal data class UiDumpDiff(val configurationDiff: ConfigurationDiff?, val treeDiff: TreeDiff?) {
  val hasChanges: Boolean
    get() = configurationDiff != null || treeDiff != null
}

/** Computes the changes from [previous] to [current], normalizing empty diffs to null. */
internal fun diffUiDumps(previous: UiDump, current: UiDump): UiDumpDiff {
  val configurationDiff = createConfigurationDiff(previous.configuration, current.configuration)?.takeIf { it.differences.isNotEmpty() }
  val treeDiff =
    diffTrees(previous.roots, current.roots).takeIf { it.added.isNotEmpty() || it.removed.isNotEmpty() || it.modified.isNotEmpty() }
  return UiDumpDiff(configurationDiff, treeDiff)
}
