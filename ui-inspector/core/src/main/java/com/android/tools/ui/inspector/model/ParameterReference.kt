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
 * Identifies a value inside a Composable parameter whose content the Compose inspector left out, so that the content can be requested from
 * the inspector later.
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
 * [composableId] and [valueHash].
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
 * @param valueHash A hash of the value's name and summary, e.g. `feed` and `List[131]`, see [hashOf]. It lets get-parameter detect that the
 *   UI changed since the token was printed. It is a hash so that the token stays short, and free of characters that shells interpret, like
 *   the brackets of `[3]`.
 */
data class ParameterReference(
  val rootViewId: Long,
  val composableId: Long,
  val kind: Kind,
  val parameterIndex: Int,
  val compositeIndex: List<Int>,
  val startIndex: Int,
  val valueHash: Int,
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

  /** Whether [parameter] can be the value this reference identifies: whether its name and value hash to [valueHash]. */
  internal fun matches(parameter: UiNode.ComposeParameter): Boolean = hashOf(parameter.name, parameter.value) == valueHash

  /**
   * Encodes this reference as the `continuationToken` printed in dumps, so that it can be passed back unchanged: its format is not stable.
   */
  fun encode(): String =
    listOf(
        rootViewId,
        composableId,
        kind.code,
        parameterIndex,
        compositeIndex.joinToString("."),
        startIndex,
        valueHash.toUInt().toString(36),
      )
      .joinToString(":")

  companion object {
    /** The number of fields [encode] writes, one per property. */
    private const val FIELD_COUNT = 7

    /**
     * Hashes the [name] and the summary [value] of a parameter value for [valueHash]. The summary of a collection is e.g. `List[131]`, and
     * that of an object is usually its class name. An empty summary counts as none: the dump and get-parameter can report the same value as
     * a single value or as a group, which drops it. The summary is hashed through its text, which unlike its hash code is the same in every
     * process. That holds for the data classes of [UiNode.ComposeParameter.Value], not for the text of
     * [UiNode.ComposeParameter.Value.NullVal], which counts as none.
     */
    internal fun hashOf(name: String, value: UiNode.ComposeParameter.Value?): Int {
      val reported = value?.takeUnless { it == UiNode.ComposeParameter.Value.NullVal || it == UiNode.ComposeParameter.Value.StringVal("") }
      return "$name\u0000$reported".hashCode()
    }

    /**
     * Decodes a string produced by [encode].
     *
     * @throws IllegalArgumentException when [token] is malformed.
     */
    fun parse(token: String): ParameterReference {
      fun invalid(): Nothing =
        throw IllegalArgumentException("Invalid continuation token '$token': pass the \"continuationToken\" of a parameter unchanged.")
      fun String.toIndex(): Int = toIntOrNull()?.takeIf { it >= 0 } ?: invalid()

      val fields = token.split(":")
      if (fields.size != FIELD_COUNT) invalid()
      return ParameterReference(
        rootViewId = fields[0].toLongOrNull() ?: invalid(),
        composableId = fields[1].toLongOrNull() ?: invalid(),
        kind = Kind.entries.find { it.code == fields[2] } ?: invalid(),
        parameterIndex = fields[3].toIndex(),
        compositeIndex = if (fields[4].isEmpty()) emptyList() else fields[4].split(".").map { it.toIndex() },
        startIndex = fields[5].toIndex(),
        valueHash = fields[6].toUIntOrNull(36)?.toInt() ?: invalid(),
      )
    }
  }
}
