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

package com.android.build.api.component.analytics

import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.api.variant.JUnitEngineSpecBuilder
import com.android.tools.build.gradle.internal.profile.VariantMethodType
import com.google.common.truth.Truth
import com.google.wireless.android.sdk.stats.GradleBuildVariant
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.quality.Strictness

class AnalyticsEnabledJUnitEngineSpecBuilderTest {

  @get:Rule val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

  private val delegate: JUnitEngineSpecBuilder = mock()

  private val stats: GradleBuildVariant.Builder = GradleBuildVariant.newBuilder()
  private val proxy: AnalyticsEnabledJUnitEngineSpecBuilder by lazy { object : AnalyticsEnabledJUnitEngineSpecBuilder(delegate, stats) {} }

  @Test
  fun testInputs() {
    val inputs = mutableListOf(AgpTestSuiteInputParameters.TEST_CLASSES)
    Mockito.`when`(delegate.inputs).thenReturn(inputs)

    Truth.assertThat(proxy.inputs).isEqualTo(inputs)
    Truth.assertThat(stats.variantApiAccess.variantAccessCount).isEqualTo(1)

    proxy.inputs.add(AgpTestSuiteInputParameters.ADB_EXECUTABLE)

    Mockito.verify(delegate, times(2)).inputs

    Truth.assertThat(stats.variantApiAccess.variantAccessCount).isEqualTo(2)
    Truth.assertThat(stats.variantApiAccess.variantAccessList.first().type).isEqualTo(VariantMethodType.JUNIT_ENGINE_BUILDER_INPUTS_VALUE)
    Truth.assertThat(proxy.inputs).containsExactly(AgpTestSuiteInputParameters.TEST_CLASSES, AgpTestSuiteInputParameters.ADB_EXECUTABLE)
  }
}
