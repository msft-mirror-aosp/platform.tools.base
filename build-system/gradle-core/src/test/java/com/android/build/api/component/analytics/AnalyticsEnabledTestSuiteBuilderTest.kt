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

import com.android.build.api.variant.JUnitEngineSpecBuilder
import com.android.build.api.variant.TestSuiteBuilder
import com.android.build.api.variant.TestSuiteTargetBuilder
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
import org.mockito.kotlin.verify
import org.mockito.quality.Strictness

class AnalyticsEnabledTestSuiteBuilderTest {

     @get:Rule
     val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

     private val delegate: TestSuiteBuilder = mock()

     private val stats = GradleBuildVariant.newBuilder()
     private val proxy: AnalyticsEnabledTestSuiteBuilder by lazy {
         object: AnalyticsEnabledTestSuiteBuilder(delegate, stats) {}
     }

    @Test
    fun junitEngineSpec() {
        val junitEngineSpec = Mockito.mock<JUnitEngineSpecBuilder>()
        Mockito.`when`(delegate.junitEngineSpec).thenReturn(junitEngineSpec)
        val junitEngineSpecProxy = proxy.junitEngineSpec

        Truth.assertThat(junitEngineSpecProxy).isInstanceOf(
            AnalyticsEnabledJUnitEngineSpecBuilder::class.java
        )

        Truth.assertThat(
            stats.variantApiAccess.variantAccessList.first().type
        ).isEqualTo(VariantMethodType.JUNIT_ENGINE_SPEC_BUILDER_VALUE)
        verify(delegate, times(1))
            .junitEngineSpec
    }

    @Test
    fun target() {
        val targets = mapOf<String, TestSuiteTargetBuilder>()
        Mockito.`when`(proxy.targets).thenReturn(targets)
        val targetProxy = proxy.targets

        Truth.assertThat(targetProxy).isEqualTo(targets)

        Truth.assertThat(
            stats.variantApiAccess.variantAccessList.first().type
        ).isEqualTo(VariantMethodType.TEST_SUITE_BUILDER_TARGETS_VALUE)
        verify(delegate, times(1))
            .targets
    }
 }
