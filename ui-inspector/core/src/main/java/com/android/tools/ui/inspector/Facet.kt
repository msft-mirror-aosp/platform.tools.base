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

/** Optional data a dump can carry on top of the tree structure and bounds. A dump without facets is lean; richness is asked for. */
enum class Facet {
  /** View attributes and Compose parameters. */
  ATTRIBUTES,
  /** Compose merged and unmerged semantics. */
  SEMANTICS,
  /**
   * Which resource defined each View attribute, and the style/theme chain it came through. Implies [ATTRIBUTES]: resolution stacks are
   * per-attribute data. The platform only exposes them after enabling `debug_view_attributes_application_package` device setting for the
   * app. So a dump with this facet may set that setting to its app, which restarts the app's activities; the dump's logger says so when it
   * happens.
   */
  RESOLUTION_STACK,
  /** Compose nodes the framework created, which a lean dump strips. */
  SYSTEM_COMPOSABLES,
}

/** What a dump fetches and keeps. */
internal data class DumpOptions(
  val attributes: Boolean,
  val resolutionStack: Boolean,
  val semantics: Boolean,
  val systemComposables: Boolean,
) {
  companion object {
    /** [Facet.RESOLUTION_STACK] brings [Facet.ATTRIBUTES] along: resolution stacks are per-attribute data. */
    fun of(facets: Set<Facet>): DumpOptions =
      DumpOptions(
        attributes = Facet.ATTRIBUTES in facets || Facet.RESOLUTION_STACK in facets,
        resolutionStack = Facet.RESOLUTION_STACK in facets,
        semantics = Facet.SEMANTICS in facets,
        systemComposables = Facet.SYSTEM_COMPOSABLES in facets,
      )
  }
}
