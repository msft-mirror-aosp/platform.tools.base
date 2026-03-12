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

package com.android.build.gradle.internal.ide.v2

import com.android.build.api.dsl.Lint
import com.google.common.truth.Truth
import java.io.File
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ConvertersTest {

  @Test
  fun `test Lint convert with baseline convention`() {
    val lint = mock<Lint>()
    whenever(lint.disable).thenReturn(mutableSetOf())
    whenever(lint.enable).thenReturn(mutableSetOf())
    whenever(lint.informational).thenReturn(mutableSetOf())
    whenever(lint.warning).thenReturn(mutableSetOf())
    whenever(lint.error).thenReturn(mutableSetOf())
    whenever(lint.fatal).thenReturn(mutableSetOf())
    whenever(lint.checkOnly).thenReturn(mutableSetOf())
    whenever(lint.baseline).thenReturn(null)

    val projectDir = File("/path/to/project")

    // Convention disabled
    val options1 = lint.convert(projectDir, useBaselineConvention = false)
    Truth.assertThat(options1.baseline).isNull()

    // Convention enabled
    val options2 = lint.convert(projectDir, useBaselineConvention = true)
    Truth.assertThat(options2.baseline).isEqualTo(File(projectDir, "lint-baseline.xml"))

    // DSL overrides convention
    val explicitBaseline = File("/path/to/explicit-baseline.xml")
    whenever(lint.baseline).thenReturn(explicitBaseline)
    val options3 = lint.convert(projectDir, useBaselineConvention = true)
    Truth.assertThat(options3.baseline).isEqualTo(explicitBaseline)
  }
}
