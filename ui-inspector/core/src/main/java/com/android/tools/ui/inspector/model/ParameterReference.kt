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
 * Identifies a value inside a Composable parameter whose content the Compose inspector left out of the dump, so that the content can be
 * requested from the inspector later.
 *
 * The inspector does not send parameter values in full: it sends only the first few elements of each collection, and no content for values
 * nested too deeply. Where it stops, it attaches a reference to the value. This class holds that reference, plus the window it belongs to
 * and, for a collection, where to resume.
 *
 * The fields locate the value step by step: the window, the Composable in it, the parameter of the Composable, then the path from the
 * parameter down to the value. For example, for `FeedScreen(feedState: FeedState)`, where `feedState.feed` is a list of 131 items of which
 * only 5 were sent, the reference to `feed` has [kind] [Kind.PARAMETERS], [parameterIndex] the position of `feedState` among the parameters
 * of `FeedScreen`, [compositeIndex] `[i]` where `i` is the position of `feed` among the properties of `FeedState`, and [startIndex] 5.
 *
 * A reference only makes sense to the Compose inspector of the app process that produced it, and only while the UI is unchanged: see
 * [composableId].
 *
 * @param rootViewId The id of the root view of the window holding the Composable, the same as the `id` of the window root in the dump. The
 *   inspector captures each window separately.
 * @param composableId The id of the Composable holding the parameter, the same as its `id` in the dump. The Compose inspector assigns these
 *   ids itself and they are not stable: depending on the Composable, it derives the id from the Composable's place in the composition, from
 *   its graphics layer, or from a counter restarted at every capture. So an id can change, or be given to another Composable, when the UI
 *   changes.
 * @param kind Which of the three parameter lists of the Composable holds the parameter, see [Kind].
 * @param parameterIndex The position of the parameter in the list given by [kind], counting from 0.
 * @param compositeIndex The path from the parameter down to the value, one position per level of nesting: the position of an element in a
 *   collection, or of a property in an object. Empty when the value is the parameter itself.
 * @param startIndex For a collection, the position right after the last element sent, i.e. where to resume; 0 for any other value.
 */
data class ParameterReference(
  val rootViewId: Long,
  val composableId: Long,
  val kind: Kind,
  val parameterIndex: Int,
  val compositeIndex: List<Int>,
  val startIndex: Int,
) {
  /**
   * The parameter lists of a Composable, as the Compose inspector reports them: the arguments of the Composable function
   * ([UiNode.ComposeNode.parameters]), and its merged and unmerged semantics ([UiNode.ComposeNode.mergedSemantics],
   * [UiNode.ComposeNode.unmergedSemantics]). Each list numbers its parameters from 0, so a [parameterIndex] is only meaningful together
   * with its list.
   */
  enum class Kind(internal val code: String) {
    PARAMETERS("p"),
    MERGED_SEMANTICS("m"),
    UNMERGED_SEMANTICS("u"),
  }

  /**
   * Encodes this reference as the `continuationToken` printed in dumps, so that it can be passed back unchanged: its format is not stable.
   */
  fun encode(): String =
    listOf(rootViewId, composableId, kind.code, parameterIndex, compositeIndex.joinToString("."), startIndex).joinToString(":")
}
