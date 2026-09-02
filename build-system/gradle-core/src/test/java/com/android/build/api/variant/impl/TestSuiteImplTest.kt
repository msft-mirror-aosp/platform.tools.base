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
import com.android.build.api.variant.InternalSources
import com.android.build.api.variant.TestSuiteSourceSet
import com.android.build.api.variant.VariantBuilder
import com.android.build.gradle.internal.api.TestApkTestSuiteSourceSet
import com.android.build.gradle.internal.component.ApplicationCreationConfig
import com.android.build.gradle.internal.component.TestSuiteCreationConfig
import com.android.build.gradle.internal.component.VariantCreationConfig
import com.android.build.gradle.internal.core.dsl.VariantDslInfo
import com.android.build.gradle.internal.dsl.AgpTestSuiteImpl
import com.android.build.gradle.internal.manifest.ManifestData
import com.android.build.gradle.internal.manifest.ManifestDataProvider
import com.android.build.gradle.internal.services.TaskCreationServices
import com.android.build.gradle.internal.services.VariantServices
import com.android.build.gradle.internal.services.createVariantPropertiesApiServices
import com.android.build.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.android.build.gradle.internal.testsuites.impl.JUnitEngineSpecForVariantBuilder
import com.android.build.gradle.internal.testsuites.impl.TestSuiteApkCreationConfig
import com.android.build.gradle.internal.testsuites.impl.TestSuiteBuilderImpl
import com.android.build.gradle.internal.testsuites.impl.TestSuiteSources
import com.android.build.gradle.internal.variant.VariantComponentInfo
import com.android.builder.core.ComponentTypeImpl
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
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
    val junitEngineSpec = mock(JUnitEngineSpecForVariantBuilder::class.java)
    `when`(testSuiteBuilder.testSuite).thenReturn(agpTestSuite)
    `when`(agpTestSuite.requiresUpdateTask).thenReturn(false)
    `when`(testSuiteBuilder.junitEngineSpec).thenReturn(junitEngineSpec)
    `when`(agpTestSuite.androidResourcesIncluded).thenReturn(true)

    val testedVariantComponent =
      mock(VariantComponentInfo::class.java) as VariantComponentInfo<VariantBuilder, VariantDslInfo, VariantCreationConfig>
    val global = mock(GlobalTaskCreationConfig::class.java)
    val variantServices = mock(VariantServices::class.java)
    val mapProperty = mock(MapProperty::class.java) as MapProperty<String, String>
    `when`(variantServices.mapPropertyOf(String::class.java, String::class.java, mapOf())).thenReturn(mapProperty)
    val booleanProperty = mock(Property::class.java) as Property<Boolean>
    `when`(booleanProperty.get()).thenReturn(true)
    `when`(variantServices.propertyOf(Boolean::class.java, false)).thenReturn(booleanProperty)
    `when`(variantServices.propertyOf(Boolean::class.java, true)).thenReturn(booleanProperty)
    val booleanProvider = mock(Provider::class.java) as Provider<Boolean>
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
        manifestDataProviderBuilder = { mock(ManifestDataProvider::class.java) },
      )

    assertEquals(true, testSuiteImpl.androidResourcesIncluded)
  }

  @Test
  fun testInstrumentationRunnerFromManifest() {
    val testSuiteBuilder = mock(TestSuiteBuilderImpl::class.java)
    val agpTestSuite = mock(AgpTestSuiteImpl::class.java)
    val junitEngineSpec = mock(JUnitEngineSpecForVariantBuilder::class.java)
    `when`(testSuiteBuilder.testSuite).thenReturn(agpTestSuite)
    `when`(agpTestSuite.requiresUpdateTask).thenReturn(false)
    `when`(testSuiteBuilder.junitEngineSpec).thenReturn(junitEngineSpec)

    val variantServices = createVariantPropertiesApiServices()
    val mapProp = variantServices.mapPropertyOf(String::class.java, String::class.java, emptyMap())
    `when`(junitEngineSpec.inputProperties).thenReturn(mapProp)

    val testedVariantComponent =
      mock(VariantComponentInfo::class.java) as VariantComponentInfo<VariantBuilder, VariantDslInfo, VariantCreationConfig>
    val global = mock(GlobalTaskCreationConfig::class.java)
    val services = mock(TaskCreationServices::class.java)
    val artifacts = mock(ArtifactsImpl::class.java)
    val defaultConfig = mock(DefaultConfig::class.java)

    val manifestData = ManifestData(instrumentationRunner = "com.example.custom.CustomRunner")
    val manifestDataProvider = mock(ManifestDataProvider::class.java)
    `when`(manifestDataProvider.manifestData).thenReturn(variantServices.provider { manifestData })

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
        manifestDataProviderBuilder = { manifestDataProvider },
      )

    val testApkSource = mock(TestSuiteSourceSet.TestApk::class.java)
    `when`(testApkSource.manifestFile).thenReturn(File("fake/path/AndroidManifest.xml"))

    val runnerProvider = testSuiteImpl.instrumentationRunner(testApkSource)
    assertThat(runnerProvider.get()).isEqualTo("com.example.custom.CustomRunner")
  }

  @Test
  fun testInstrumentationRunnerFallbackWhenManifestEmpty() {
    val testSuiteBuilder = mock(TestSuiteBuilderImpl::class.java)
    val agpTestSuite = mock(AgpTestSuiteImpl::class.java)
    val junitEngineSpec = mock(JUnitEngineSpecForVariantBuilder::class.java)
    `when`(testSuiteBuilder.testSuite).thenReturn(agpTestSuite)
    `when`(agpTestSuite.requiresUpdateTask).thenReturn(false)
    `when`(testSuiteBuilder.junitEngineSpec).thenReturn(junitEngineSpec)

    val variantServices = createVariantPropertiesApiServices()
    val mapProp = variantServices.mapPropertyOf(String::class.java, String::class.java, emptyMap())
    `when`(junitEngineSpec.inputProperties).thenReturn(mapProp)

    val testedVariantComponent =
      mock(VariantComponentInfo::class.java) as VariantComponentInfo<VariantBuilder, VariantDslInfo, VariantCreationConfig>
    val global = mock(GlobalTaskCreationConfig::class.java)
    val services = mock(TaskCreationServices::class.java)
    val artifacts = mock(ArtifactsImpl::class.java)
    val defaultConfig = mock(DefaultConfig::class.java)

    val manifestData = ManifestData(instrumentationRunner = null)
    val manifestDataProvider = mock(ManifestDataProvider::class.java)
    `when`(manifestDataProvider.manifestData).thenReturn(variantServices.provider { manifestData })

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
        manifestDataProviderBuilder = { manifestDataProvider },
      )

    val testApkSource = mock(TestSuiteSourceSet.TestApk::class.java)
    `when`(testApkSource.manifestFile).thenReturn(File("fake/path/AndroidManifest.xml"))

    val runnerProvider = testSuiteImpl.instrumentationRunner(testApkSource)
    assertThat(runnerProvider.get()).isEqualTo("androidx.test.runner.AndroidJUnitRunner")
  }

  @Test
  fun testTestSuiteApkCreationConfigComponentType() {
    val testSuite = mock(TestSuiteCreationConfig::class.java)
    val testedVariant = mock(ApplicationCreationConfig::class.java)
    `when`(testSuite.testedVariant).thenReturn(testedVariant)
    val sourceContainer = mock(TestSuiteSourceContainer::class.java)

    val creationConfig = TestSuiteApkCreationConfig(testSuite, sourceContainer)
    assertThat(creationConfig.componentType).isEqualTo(ComponentTypeImpl.TEST_APK)
  }

  @Test
  fun testTestSuiteSourcesDelegation() {
    val delegate = mock(InternalSources::class.java)
    val sourceContainer = mock(TestSuiteSourceContainer::class.java)
    val sourceSet = mock(TestApkTestSuiteSourceSet::class.java)
    `when`(sourceContainer.source).thenReturn(sourceSet)

    val mockJava = mock(FlatSourceDirectoriesImpl::class.java)
    val mockKotlin = mock(FlatSourceDirectoriesImpl::class.java)
    val mockResources = mock(FlatSourceDirectoriesImpl::class.java)
    `when`(sourceSet.java).thenReturn(mockJava)
    `when`(sourceSet.kotlin).thenReturn(mockKotlin)
    `when`(sourceSet.resources).thenReturn(mockResources)

    val testSuiteSources = TestSuiteSources(delegate, sourceContainer)

    assertThat(testSuiteSources.java).isSameInstanceAs(mockJava)
    assertThat(testSuiteSources.kotlin).isSameInstanceAs(mockKotlin)
    assertThat(testSuiteSources.resources).isSameInstanceAs(mockResources)
  }

  @Test
  fun testCodeCoverageEnabled() {
    val testSuiteBuilder = mock(TestSuiteBuilderImpl::class.java)
    val agpTestSuite = mock(AgpTestSuiteImpl::class.java)
    val junitEngineSpec = mock(JUnitEngineSpecForVariantBuilder::class.java)
    `when`(testSuiteBuilder.testSuite).thenReturn(agpTestSuite)
    `when`(agpTestSuite.requiresUpdateTask).thenReturn(false)
    `when`(testSuiteBuilder.junitEngineSpec).thenReturn(junitEngineSpec)
    `when`(testSuiteBuilder._enableCodeCoverage).thenReturn(true)

    val testedVariantComponent =
      mock(VariantComponentInfo::class.java) as VariantComponentInfo<VariantBuilder, VariantDslInfo, VariantCreationConfig>
    val global = mock(GlobalTaskCreationConfig::class.java)
    val variantServices = mock(VariantServices::class.java)
    val mapProperty = mock(MapProperty::class.java) as MapProperty<String, String>
    `when`(variantServices.mapPropertyOf(String::class.java, String::class.java, mapOf())).thenReturn(mapProperty)
    val booleanProvider = mock(Provider::class.java) as Provider<Boolean>
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
        manifestDataProviderBuilder = { mock(ManifestDataProvider::class.java) },
      )

    assertEquals(true, testSuiteImpl.codeCoverageEnabled)
  }
}
