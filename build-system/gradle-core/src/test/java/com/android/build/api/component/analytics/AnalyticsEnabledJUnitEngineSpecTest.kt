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
import com.android.build.api.variant.JUnitEngineSpec
import com.android.build.gradle.internal.fixtures.FakeProviderFactory
import com.android.tools.build.gradle.internal.profile.VariantMethodType
import com.google.common.truth.Truth
import com.google.wireless.android.sdk.stats.GradleBuildVariant
import org.gradle.api.artifacts.dsl.DependencyCollector
import org.gradle.api.provider.Provider
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.quality.Strictness

class AnalyticsEnabledJUnitEngineSpecTest {
  @get:Rule val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

  private val delegate: JUnitEngineSpec = mock()

  private val stats: GradleBuildVariant.Builder = GradleBuildVariant.newBuilder()
  private val proxy: AnalyticsEnabledJUnitEngineSpec by lazy { object : AnalyticsEnabledJUnitEngineSpec(delegate, stats) {} }

  @Test
  fun testInputs() {
    val inputs = mutableListOf(AgpTestSuiteInputParameters.TEST_CLASSES)
    Mockito.`when`(delegate.inputs).thenReturn(inputs)

    Truth.assertThat(proxy.inputs).isEqualTo(inputs)
    Truth.assertThat(stats.variantApiAccess.variantAccessCount).isEqualTo(1)

    verify(delegate, times(1)).inputs

    Truth.assertThat(stats.variantApiAccess.variantAccessCount).isEqualTo(1)
    Truth.assertThat(stats.variantApiAccess.variantAccessList.first().type).isEqualTo(VariantMethodType.JUNIT_ENGINE_BUILDER_INPUTS_VALUE)
    Truth.assertThat(proxy.inputs).containsExactly(AgpTestSuiteInputParameters.TEST_CLASSES)
  }

  @Test
  fun testIncludeEngines() {
    val engines = mutableSetOf("engine1")
    Mockito.`when`(delegate.includeEngines).thenReturn(engines)

    Truth.assertThat(proxy.includeEngines).isEqualTo(engines)
    Truth.assertThat(stats.variantApiAccess.variantAccessCount).isEqualTo(1)

    proxy.includeEngines.add("engine2")

    verify(delegate, times(2)).includeEngines

    Truth.assertThat(stats.variantApiAccess.variantAccessCount).isEqualTo(2)
    Truth.assertThat(stats.variantApiAccess.variantAccessList.first().type)
      .isEqualTo(VariantMethodType.JUNIT_ENGINE_BUILDER_INCLUDE_ENGINES_VALUE)
    Truth.assertThat(proxy.includeEngines).containsExactly("engine1", "engine2")
  }

  @Test
  fun testAddInputProperty() {

    proxy.addInputProperty("Foo", "Bar")

    verify(delegate).addInputProperty(eq("Foo"), eq("Bar"))

    Truth.assertThat(stats.variantApiAccess.variantAccessCount).isEqualTo(1)
    Truth.assertThat(stats.variantApiAccess.variantAccessList.first().type)
      .isEqualTo(VariantMethodType.JUNIT_ENGINE_BUILDER_INPUT_PROPERTIES_VALUE)
  }

  @Test
  fun testAddInputProviderProperty() {

    proxy.addInputProperty("Foo", FakeProviderFactory.factory.provider { "Bar" })

    val providerCaptor = argumentCaptor<Provider<String>>()

    verify(delegate).addInputProperty(eq("Foo"), providerCaptor.capture())
    Truth.assertThat(providerCaptor.firstValue.get()).isEqualTo("Bar")

    Truth.assertThat(stats.variantApiAccess.variantAccessCount).isEqualTo(1)
    Truth.assertThat(stats.variantApiAccess.variantAccessList.first().type)
      .isEqualTo(VariantMethodType.JUNIT_ENGINE_BUILDER_INPUT_PROPERTIES_VALUE)
  }

  @Test
  fun enginesDependencies() {
    val dependencyCollector: DependencyCollector = mock()
    Mockito.`when`(delegate.enginesDependencies).thenReturn(dependencyCollector)

    Truth.assertThat(proxy.enginesDependencies).isEqualTo(dependencyCollector)

    Truth.assertThat(stats.variantApiAccess.variantAccessCount).isEqualTo(1)
    Truth.assertThat(stats.variantApiAccess.variantAccessList.first().type)
      .isEqualTo(VariantMethodType.JUNIT_ENGINE_BUILDER_ENGINE_DEPENDENCIES_VALUE)
    verify(delegate, times(1)).enginesDependencies
  }
}
