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
import org.mockito.quality.Strictness

class AnalyticsEnabledTestSuiteTargetBuilderTest {
    @get:Rule
    val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

    private val delegate: TestSuiteTargetBuilder = mock()

    private val stats = GradleBuildVariant.newBuilder()
    private val proxy: AnalyticsEnabledTestSuiteTargetBuilder by lazy {
        object: AnalyticsEnabledTestSuiteTargetBuilder(delegate, stats) {}
    }

    @Test
    fun testEnable() {
        proxy.enable = true
        Mockito.verify(delegate, times(1)).enable = true

        Truth.assertThat(
            stats.variantApiAccess.variantAccessList.first().type
        ).isEqualTo(VariantMethodType.TEST_SUITE_TARGET_BUILDER_ENABLE_VALUE)
    }

    @Test
    fun testTargetDevices() {
        val targetDevices = mutableListOf("device1")
        Mockito.`when`(proxy.targetDevices).thenReturn(targetDevices)
        val targetDevicesProxy = proxy.targetDevices

        Truth.assertThat(targetDevicesProxy).containsExactly("device1")
        targetDevicesProxy.add("device2")
        Truth.assertThat(proxy.targetDevices).containsExactly(
            "device1", "device2")

        Truth.assertThat(
            stats.variantApiAccess.variantAccessList.first().type
        ).isEqualTo(VariantMethodType.TEST_SUITE_TARGET_BUILDER_TARGET_DEVICES_VALUE)
        Mockito.verify(delegate, times(2)).targetDevices
    }
}
