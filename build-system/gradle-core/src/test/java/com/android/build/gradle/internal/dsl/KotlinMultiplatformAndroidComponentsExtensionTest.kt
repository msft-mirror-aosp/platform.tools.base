/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.internal.dsl

import com.android.build.api.AndroidPluginVersion
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.api.dsl.SdkComponents
import com.android.build.api.extension.impl.KotlinMultiplatformAndroidComponentsExtensionImpl
import com.android.build.api.extension.impl.VariantApiOperationsRegistrar
import com.android.build.api.instrumentation.manageddevice.ManagedDeviceRegistry
import com.android.build.api.variant.KotlinMultiplatformAndroidVariant
import com.android.build.api.variant.KotlinMultiplatformAndroidVariantBuilder
import com.android.build.gradle.internal.SdkComponentsBuildService
import com.android.build.gradle.internal.fixtures.FakeGradleProvider
import com.android.build.gradle.internal.services.DslServices
import com.android.build.gradle.internal.services.createDslServices
import com.google.common.truth.Truth
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock

class KotlinMultiplatformAndroidComponentsExtensionTest {
    private lateinit var dslServices: DslServices
    private lateinit var sdkComponents: SdkComponents
    private lateinit var managedDeviceRegistry: ManagedDeviceRegistry
    private lateinit var applicationExtension: ApplicationExtension
    private lateinit var variantApiOperationsRegistrar: VariantApiOperationsRegistrar<KotlinMultiplatformAndroidLibraryExtension, KotlinMultiplatformAndroidVariantBuilder, KotlinMultiplatformAndroidVariant>
    private lateinit var extension: KotlinMultiplatformAndroidLibraryExtension
    private lateinit var androidTarget: KotlinMultiplatformAndroidLibraryTarget
    val mockProvider: () -> KotlinMultiplatformAndroidLibraryTarget = { androidTarget }

    @Before
    fun setUp() {
        val sdkComponentsBuildService = mock<SdkComponentsBuildService>()
        dslServices = createDslServices(sdkComponents = FakeGradleProvider(sdkComponentsBuildService))
        sdkComponents = mock<SdkComponents>()
        managedDeviceRegistry = mock<ManagedDeviceRegistry>()
        applicationExtension = mock<ApplicationExtension>()
        extension = mock<KotlinMultiplatformAndroidLibraryExtension>()
        variantApiOperationsRegistrar = VariantApiOperationsRegistrar(extension)
        androidTarget = mock<KotlinMultiplatformAndroidLibraryTarget>()
    }

    @Test
    fun testPluginVersion() {
        val androidComponents = KotlinMultiplatformAndroidComponentsExtensionImpl(
            dslServices,
            sdkComponents,
            managedDeviceRegistry,
            variantApiOperationsRegistrar,
            extension,
            mockProvider
        )
        Truth.assertThat(androidComponents.pluginVersion).isNotNull()
        Truth.assertThat(androidComponents.pluginVersion >= AndroidPluginVersion(4, 2)).isTrue()
    }

    @Test
    fun testSdkComponents() {
        val sdkComponentsFromComponents = KotlinMultiplatformAndroidComponentsExtensionImpl(
            dslServices,
            sdkComponents,
            managedDeviceRegistry,
            variantApiOperationsRegistrar,
            extension,
            mockProvider
        ).sdkComponents
        Truth.assertThat(sdkComponentsFromComponents).isSameInstanceAs(sdkComponents)
    }

    @Test
    fun testCustomDeviceRegistry() {
        val deviceRegistryFromComponents = KotlinMultiplatformAndroidComponentsExtensionImpl(
            dslServices,
            sdkComponents,
            managedDeviceRegistry,
            variantApiOperationsRegistrar,
            extension,
            mockProvider
        ).managedDeviceRegistry
        Truth.assertThat(deviceRegistryFromComponents).isSameInstanceAs(managedDeviceRegistry)
    }

    @Test
    fun testCallingOnVariant() {
        val variant = mock<KotlinMultiplatformAndroidVariant>()
        val componentsExtension = KotlinMultiplatformAndroidComponentsExtensionImpl(
            dslServices,
            sdkComponents,
            managedDeviceRegistry,
            variantApiOperationsRegistrar,
            extension,
            mockProvider
        )

        var called = false
        componentsExtension.onVariant {
            Truth.assertThat(it).isEqualTo(variant)
            called = true
        }

        variantApiOperationsRegistrar.variantOperations.executeOperations(variant)
        Truth.assertThat(called).isTrue()
    }

    @Test
    fun testDslFinalizationBlock() {
        val componentsExtension = KotlinMultiplatformAndroidComponentsExtensionImpl(
            dslServices,
            sdkComponents,
            managedDeviceRegistry,
            variantApiOperationsRegistrar,
            extension,
            mockProvider
        )

        var called = false
        componentsExtension.finalizeDsl {
            Truth.assertThat(it).isEqualTo(extension)
            called = true
        }

        variantApiOperationsRegistrar.executeDslFinalizationBlocks()
        Truth.assertThat(called).isTrue()
    }
}
