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

/**
 * The `--include` vocabulary: every [Facet] by its command-line name, plus `all`, which stands for all of them. Every CLI that exposes the
 * UI inspector uses it, so that the same names select the same facets everywhere.
 */
enum class IncludeFacet(val cliName: String, val facet: Facet?) {
  ATTRIBUTES("attributes", Facet.ATTRIBUTES),
  SEMANTICS("semantics", Facet.SEMANTICS),
  RESOLUTION_STACK("resolution-stack", Facet.RESOLUTION_STACK),
  SYSTEM_COMPOSABLES("system-composables", Facet.SYSTEM_COMPOSABLES),
  ALL("all", null);

  companion object {
    /** Returns the [IncludeFacet] named [cliName], ignoring surrounding whitespace, or null if there is none. */
    fun fromCliName(cliName: String): IncludeFacet? {
      val trimmed = cliName.trim()
      return entries.firstOrNull { it.cliName == trimmed }
    }
  }
}

/** The facets [included] asks for: `all` stands for every facet. */
fun requestedFacets(included: Collection<IncludeFacet>): Set<Facet> =
  if (IncludeFacet.ALL in included) Facet.entries.toSet() else included.mapNotNull { it.facet }.toSet()
