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

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FacetTest {

  @Test
  fun testDumpOptions_noFacetsIsLean() {
    assertThat(DumpOptions.of(emptySet()))
      .isEqualTo(DumpOptions(attributes = false, resolutionStack = false, semantics = false, systemComposables = false))
  }

  @Test
  fun testDumpOptions_eachFacetStandsAlone() {
    assertThat(DumpOptions.of(setOf(Facet.ATTRIBUTES)))
      .isEqualTo(DumpOptions(attributes = true, resolutionStack = false, semantics = false, systemComposables = false))
    assertThat(DumpOptions.of(setOf(Facet.SEMANTICS)))
      .isEqualTo(DumpOptions(attributes = false, resolutionStack = false, semantics = true, systemComposables = false))
    assertThat(DumpOptions.of(setOf(Facet.SYSTEM_COMPOSABLES)))
      .isEqualTo(DumpOptions(attributes = false, resolutionStack = false, semantics = false, systemComposables = true))
  }

  @Test
  fun testDumpOptions_resolutionStackBringsAttributes() {
    assertThat(DumpOptions.of(setOf(Facet.RESOLUTION_STACK)))
      .isEqualTo(DumpOptions(attributes = true, resolutionStack = true, semantics = false, systemComposables = false))
  }

  @Test
  fun testDumpOptions_allFacets() {
    assertThat(DumpOptions.of(Facet.entries.toSet()))
      .isEqualTo(DumpOptions(attributes = true, resolutionStack = true, semantics = true, systemComposables = true))
  }
}
