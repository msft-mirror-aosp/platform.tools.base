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

package com.android.build.api.variant.impl

import com.android.build.api.artifact.impl.ArtifactsImpl
import com.android.build.api.dsl.DefaultConfig
import com.android.build.api.variant.VariantBuilder
import com.android.build.gradle.internal.component.VariantCreationConfig
import com.android.build.gradle.internal.core.dsl.VariantDslInfo
import com.android.build.gradle.internal.dsl.AgpTestSuiteImpl
import com.android.build.gradle.internal.services.TaskCreationServices
import com.android.build.gradle.internal.services.VariantServices
import com.android.build.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.android.build.gradle.internal.testsuites.impl.TestSuiteBuilderImpl
import com.android.build.gradle.internal.variant.VariantComponentInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any

class TestSuiteImplTest {

  @Test
  fun testAndroidResourcesIncluded() {
    val testSuiteBuilder = mock(TestSuiteBuilderImpl::class.java)
    val agpTestSuite = mock(AgpTestSuiteImpl::class.java)
    val junitEngineSpec = mock(com.android.build.gradle.internal.testsuites.impl.JUnitEngineSpecForVariantBuilder::class.java)
    `when`(testSuiteBuilder.testSuite).thenReturn(agpTestSuite)
    `when`(agpTestSuite.requiresUpdateTask).thenReturn(false)
    `when`(testSuiteBuilder.junitEngineSpec).thenReturn(junitEngineSpec)
    `when`(agpTestSuite.androidResourcesIncluded).thenReturn(true)

    val testedVariantComponent =
      mock(VariantComponentInfo::class.java) as VariantComponentInfo<VariantBuilder, VariantDslInfo, VariantCreationConfig>
    val global = mock(GlobalTaskCreationConfig::class.java)
    val variantServices = mock(VariantServices::class.java)
    val mapProperty = mock(org.gradle.api.provider.MapProperty::class.java) as org.gradle.api.provider.MapProperty<String, String>
    `when`(variantServices.mapPropertyOf(String::class.java, String::class.java, mapOf())).thenReturn(mapProperty)
    val booleanProperty = mock(org.gradle.api.provider.Property::class.java) as org.gradle.api.provider.Property<Boolean>
    `when`(booleanProperty.get()).thenReturn(true)
    `when`(variantServices.propertyOf(Boolean::class.java, false)).thenReturn(booleanProperty)
    `when`(variantServices.propertyOf(Boolean::class.java, true)).thenReturn(booleanProperty)
    val booleanProvider = mock(org.gradle.api.provider.Provider::class.java) as org.gradle.api.provider.Provider<Boolean>
    `when`(booleanProvider.get()).thenReturn(false)
    `when`(variantServices.provider<Boolean>(any())).thenReturn(booleanProvider)
    val services = mock(TaskCreationServices::class.java)
    val artifacts = mock(ArtifactsImpl::class.java)
    val defaultConfig = mock(DefaultConfig::class.java)

    val testSuiteImpl =
      TestSuiteImpl(
        testSuiteBuilder = testSuiteBuilder,
        sourceContainers = emptyList(),
        testedVariantComponent = testedVariantComponent,
        global = global,
        variantServices = variantServices,
        services = services,
        artifacts = artifacts,
        defaultConfig = defaultConfig,
        manifestDataProviderBuilder = { mock(com.android.build.gradle.internal.manifest.ManifestDataProvider::class.java) },
      )

    assertEquals(true, testSuiteImpl.androidResourcesIncluded)
  }
}
