/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.api.variant.impl

import com.android.build.api.variant.ApplicationVariantBuilder
import com.android.build.api.variant.ComponentIdentity
import com.android.build.api.variant.GeneratesApkBuilder
import com.android.build.api.variant.HostTestBuilder
import com.android.build.api.variant.VariantBuilder
import com.android.build.gradle.internal.core.PostProcessingOptions
import com.android.build.gradle.internal.core.dsl.ApplicationVariantDslInfo
import com.android.build.gradle.internal.core.dsl.VariantDslInfo
import com.android.build.gradle.internal.core.dsl.features.DexingDslInfo
import com.android.build.gradle.internal.core.dsl.features.OptimizationDslInfo
import com.android.build.gradle.internal.fixtures.FakeProviderFactory
import com.android.build.gradle.internal.services.ProjectServices
import com.android.build.gradle.internal.services.VariantBuilderServices
import com.android.build.gradle.options.ProjectOptions
import com.google.common.collect.ImmutableMap
import com.google.common.truth.Truth
import com.google.wireless.android.sdk.stats.GradleBuildVariant
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.quality.Strictness

internal class VariantBuilderImplTest {
    @get:Rule
    val rule: MockitoRule = MockitoJUnit.rule().strictness(Strictness.STRICT_STUBS)

    private val variantDslInfo: ApplicationVariantDslInfo = mock()
    private val componentIdentity: ComponentIdentity = mock()
    private val variantBuilderServices: VariantBuilderServices = mock()
    private val globalVariantBuilderConfig: GlobalVariantBuilderConfig = mock()

    val builder: ApplicationVariantBuilder by lazy {
        object : ApplicationVariantBuilderImpl(
            globalVariantBuilderConfig,
            variantDslInfo,
            componentIdentity,
            variantBuilderServices
        ) {
            override fun <T : VariantBuilder> createUserVisibleVariantObject(
                projectServices: ProjectServices,
                stats: GradleBuildVariant.Builder?
            ): T {
                throw RuntimeException("Unexpected method invocation")
            }

            override val hostTests: Map<String, HostTestBuilder> = mapOf()
        }
    }

    @Before
    fun setup() {
        val optimizationDslInfo = mock<OptimizationDslInfo>()
        val dexingDslInfo = mock<DexingDslInfo>()
        val postProcessingOptions = mock<PostProcessingOptions>()
        whenever(optimizationDslInfo.postProcessingOptions).thenReturn(postProcessingOptions)
        whenever(postProcessingOptions.hasPostProcessingConfiguration()).thenReturn(false)
        whenever(variantDslInfo.dexingDslInfo).thenReturn(dexingDslInfo)
        whenever(variantDslInfo.optimizationDslInfo).thenReturn(optimizationDslInfo)
        whenever(variantDslInfo.minSdkVersion).thenReturn(MutableAndroidVersion(12, null))
        whenever(variantDslInfo.targetSdkVersion).thenReturn(null)
    }

    @Test
    fun testMinSdkSetters() {
        Truth.assertThat(builder.minSdk).isEqualTo(12)
        Truth.assertThat(builder.minSdkPreview).isNull()

        builder.minSdk = 43
        Truth.assertThat(builder.minSdk).isEqualTo(43)
        Truth.assertThat(builder.minSdkPreview).isNull()

        builder.minSdkPreview = "M"
        Truth.assertThat(builder.minSdk).isNull()
        Truth.assertThat(builder.minSdkPreview).isEqualTo("M")

        builder.minSdkPreview = "N"
        Truth.assertThat(builder.minSdk).isNull()
        Truth.assertThat(builder.minSdkPreview).isEqualTo("N")

        builder.minSdk = 23
        Truth.assertThat(builder.minSdk).isEqualTo(23)
        Truth.assertThat(builder.minSdkPreview).isNull()
    }

    @Test
    fun testTargetSdkSetters() {
        // check we get the minSdkVersion by default
        Truth.assertThat(getTargetSdk()).isEqualTo(12)
        Truth.assertThat(getTargetSdkPreview()).isNull()
        builder.minSdk = 43
        Truth.assertThat(getTargetSdk()).isEqualTo(43)
        Truth.assertThat(getTargetSdkPreview()).isNull()
        builder.minSdkPreview = "N"
        Truth.assertThat(getTargetSdk()).isNull()
        Truth.assertThat(getTargetSdkPreview()).isEqualTo("N")

        setTargetSdk(43)
        Truth.assertThat(getTargetSdk()).isEqualTo(43)
        Truth.assertThat(getTargetSdkPreview()).isNull()
        // check the min sdk is not impacted by changes to target
        Truth.assertThat(builder.minSdk).isNull()
        Truth.assertThat(builder.minSdkPreview).isEqualTo("N")

        setTargetSdkPreview("M")
        Truth.assertThat(getTargetSdk()).isNull()
        Truth.assertThat(getTargetSdkPreview()).isEqualTo("M")

        setTargetSdkPreview("N")
        Truth.assertThat(getTargetSdk()).isNull()
        Truth.assertThat(getTargetSdkPreview()).isEqualTo("N")

        setTargetSdk(23)
        Truth.assertThat(getTargetSdk()).isEqualTo(23)
        Truth.assertThat(getTargetSdkPreview()).isNull()

        // check changing the min sdk does impact the target after it's forked
        builder.minSdk = 43
        Truth.assertThat(getTargetSdk()).isEqualTo(23)
        Truth.assertThat(getTargetSdkPreview()).isNull()
    }

    private fun getTargetSdk() =
        GeneratesApkBuilder::class.java
            .getMethod("getTargetSdk")
            .invoke(builder)

    private fun setTargetSdk(value: Int) =
        GeneratesApkBuilder::class.java
            .getMethod("setTargetSdk", Integer::class.java)
            .invoke(builder, value)

    private fun getTargetSdkPreview() =
        GeneratesApkBuilder::class.java
            .getMethod("getTargetSdkPreview")
            .invoke(builder)

    private fun setTargetSdkPreview(value: Any) =
        GeneratesApkBuilder::class.java
            .getMethod("setTargetSdkPreview", String::class.java)
            .invoke(builder, value)
}
