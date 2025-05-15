/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.lint.checks

import com.android.tools.lint.checks.fx.utils.LatticeTest
import com.google.common.truth.Truth
import org.junit.Test

abstract class ThreadConstraintLatticeTest<T : Enum<T>>(
  private val lattice: ThreadConstraintDetector.ThreadConstraintLattice<T>
) :
  LatticeTest<ThreadConstraintDetector.ThreadConstraint<T>>(
    lattice = lattice,
    poolInits = lattice.threadTag.enumConstants.map { lattice.of(it) },
  ) {

  @Test
  fun `AnyThread strictly precedes the 'meet' over explicit tags`() {
    val allTags = lattice.threadTag.enumConstants.map { lattice.of(it) }
    val meet = allTags.fold(lattice.top, lattice::meetOf)
    for (tag in allTags) Truth.assertThat(meet precedes tag).isTrue()
    Truth.assertThat(lattice.AnyThread precedes meet).isTrue()
    Truth.assertThat(meet precedes lattice.AnyThread).isFalse()
  }
}
