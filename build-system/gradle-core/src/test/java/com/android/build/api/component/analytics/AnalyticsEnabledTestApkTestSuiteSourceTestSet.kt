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

import com.android.build.api.variant.TestSuiteSourceSet
import com.android.build.api.variant.TestSuiteSourceType
import com.android.tools.build.gradle.internal.profile.VariantPropertiesMethodType
import com.google.common.truth.Truth
import com.google.wireless.android.sdk.stats.GradleBuildVariant
import org.junit.Rule
import org.junit.Test
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.quality.Strictness

class AnalyticsEnabledTestApkTestSuiteSourceTestSet {

    @get:Rule
    val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

    private val delegate: TestSuiteSourceSet.TestApk = mock()

    private val stats = GradleBuildVariant.newBuilder()
    private val proxy: AnalyticsEnabledTestApkTestSuiteSourceSet by lazy {
        object : AnalyticsEnabledTestApkTestSuiteSourceSet(delegate, stats) {
            override val type: TestSuiteSourceType
                get() = TestSuiteSourceType.TEST_APK
        }
    }

    @Test
    fun testGetJava() {
        proxy.java

        Truth.assertThat(
            stats.variantApiAccess.variantPropertiesAccessList.first().type
        ).isEqualTo(VariantPropertiesMethodType.TEST_SUITE_SOURCE_JAVA_VALUE)
        verify(delegate).java
    }

    @Test
    fun testGetKotlin() {
        proxy.kotlin

        Truth.assertThat(
            stats.variantApiAccess.variantPropertiesAccessList.first().type
        ).isEqualTo(VariantPropertiesMethodType.TEST_SUITE_SOURCE_KOTLIN_VALUE)
        verify(delegate).kotlin
    }

    @Test
    fun testGetResources() {
        proxy.resources

        Truth.assertThat(
            stats.variantApiAccess.variantPropertiesAccessList.first().type
        ).isEqualTo(VariantPropertiesMethodType.TEST_SUITE_SOURCE_RESOURCES_VALUE)
        verify(delegate).resources
    }
}
