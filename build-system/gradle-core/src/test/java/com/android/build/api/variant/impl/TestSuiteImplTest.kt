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
import com.android.build.api.variant.Component
import com.android.build.api.variant.InternalSources
import com.android.build.api.variant.TestSuiteSourceSet
import com.android.build.api.variant.VariantBuilder
import com.android.build.gradle.internal.api.HostJarTestSuiteSourceSet
import com.android.build.gradle.internal.api.TestApkTestSuiteSourceSet
import com.android.build.gradle.internal.component.ApplicationCreationConfig
import com.android.build.gradle.internal.component.TestSuiteCreationConfig
import com.android.build.gradle.internal.component.VariantCreationConfig
import com.android.build.gradle.internal.core.dsl.VariantDslInfo
import com.android.build.gradle.internal.dsl.AgpTestSuiteImpl
import com.android.build.gradle.internal.dsl.BackupAgpTestSuiteImpl
import com.android.build.gradle.internal.dsl.TestSuiteHostJarSpecImpl
import com.android.build.gradle.internal.fixtures.ProjectFactory
import com.android.build.gradle.internal.manifest.ManifestData
import com.android.build.gradle.internal.manifest.ManifestDataProvider
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.MutableTaskContainer
import com.android.build.gradle.internal.services.BuiltInKaptSupportMode
import com.android.build.gradle.internal.services.TaskCreationServices
import com.android.build.gradle.internal.services.VariantServices
import com.android.build.gradle.internal.services.createDslServices
import com.android.build.gradle.internal.services.createVariantPropertiesApiServices
import com.android.build.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.android.build.gradle.internal.testsuites.impl.JUnitEngineSpecForVariantBuilder
import com.android.build.gradle.internal.testsuites.impl.JavaCompileCreationConfigForTestSuite
import com.android.build.gradle.internal.testsuites.impl.TestSuiteApkCreationConfig
import com.android.build.gradle.internal.testsuites.impl.TestSuiteBuilderImpl
import com.android.build.gradle.internal.testsuites.impl.TestSuiteHostJarCreationConfig
import com.android.build.gradle.internal.testsuites.impl.TestSuiteHostJarSources
import com.android.build.gradle.internal.testsuites.impl.TestSuiteSources
import com.android.build.gradle.internal.variant.VariantComponentInfo
import com.android.builder.core.ComponentTypeImpl
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.file.Directory
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Provider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.mockito.Mockito.withSettings
import org.mockito.kotlin.any

class TestSuiteImplTest {

  @Test
  fun testAndroidResourcesIncluded() {
    val dslServices = createDslServices()
    val variantServices = createVariantPropertiesApiServices()
    val junitEngineSpec = mock(JUnitEngineSpecForVariantBuilder::class.java)
    val mapProp = variantServices.mapPropertyOf(String::class.java, String::class.java, emptyMap())
    `when`(junitEngineSpec.inputProperties).thenReturn(mapProp)

    val testedVariantComponent =
      mock(VariantComponentInfo::class.java) as VariantComponentInfo<VariantBuilder, VariantDslInfo, VariantCreationConfig>
    val global = mock(GlobalTaskCreationConfig::class.java)
    val services = mock(TaskCreationServices::class.java)
    val artifacts = mock(ArtifactsImpl::class.java)
    val defaultConfig = mock(DefaultConfig::class.java)

    fun createTestSuiteImpl(suite: AgpTestSuiteImpl): TestSuiteImpl {
      val testSuiteBuilder = mock(TestSuiteBuilderImpl::class.java)
      `when`(testSuiteBuilder.name).thenReturn(suite.name)
      `when`(testSuiteBuilder.testSuite).thenReturn(suite)
      `when`(testSuiteBuilder.junitEngineSpec).thenReturn(junitEngineSpec)
      return TestSuiteImpl(
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
    }

    // When no TestSuiteHostJarSpecImpl container exists, TestSuiteImpl.androidResourcesIncluded falls back to
    // AgpTestSuiteImpl.androidResourcesIncluded.
    val suiteWithDefaultTrue = dslServices.newInstance(AgpTestSuiteImpl::class.java, "screenshot", dslServices, true)
    assertThat(createTestSuiteImpl(suiteWithDefaultTrue).androidResourcesIncluded).isTrue()

    val customSuite = dslServices.newInstance(AgpTestSuiteImpl::class.java, "unit", dslServices, false)
    assertThat(createTestSuiteImpl(customSuite).androidResourcesIncluded).isFalse()

    // Initial hostJar {} seeds enableAndroidResources from AgpTestSuiteImpl.androidResourcesIncluded (false).
    customSuite.hostJar {}
    val hostJarSpec = customSuite.getSourceContainers().filterIsInstance<TestSuiteHostJarSpecImpl>().single()
    assertThat(hostJarSpec.enableAndroidResources).isFalse()
    assertThat(createTestSuiteImpl(customSuite).androidResourcesIncluded).isFalse()

    // Configuring enableAndroidResources = true and then invoking a second hostJar {} block preserves enableAndroidResources == true.
    customSuite.hostJar { enableAndroidResources = true }
    customSuite.hostJar {}
    assertThat(hostJarSpec.enableAndroidResources).isTrue()
    assertThat(createTestSuiteImpl(customSuite).androidResourcesIncluded).isTrue()
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
  fun testInstrumentationRunnerForBackupTestSuite() {
    val testSuiteBuilder = mock(TestSuiteBuilderImpl::class.java)
    val backupAgpTestSuite = mock(BackupAgpTestSuiteImpl::class.java)
    val junitEngineSpec = mock(JUnitEngineSpecForVariantBuilder::class.java)
    `when`(testSuiteBuilder.testSuite).thenReturn(backupAgpTestSuite)
    `when`(backupAgpTestSuite.requiresUpdateTask).thenReturn(false)
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
    assertThat(runnerProvider.get()).isEqualTo("androidx.test.backup.BackupRestoreTestRunner")
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
  fun testTestSuiteApkCreationConfigBuiltInKotlinAndKapt() {
    val testSuite = mock(TestSuiteCreationConfig::class.java)
    val testedVariant = mock(ApplicationCreationConfig::class.java)
    `when`(testSuite.testedVariant).thenReturn(testedVariant)

    val sourceContainer = mock(TestSuiteSourceContainer::class.java)
    val sourceContainerArtifacts = mock(ArtifactsImpl::class.java)
    `when`(sourceContainer.artifacts).thenReturn(sourceContainerArtifacts)

    @Suppress("UNCHECKED_CAST") val suiteKotlincDir = mock(Provider::class.java) as Provider<Directory>
    @Suppress("UNCHECKED_CAST") val mainKotlincDir = mock(Provider::class.java) as Provider<Directory>
    @Suppress("UNCHECKED_CAST") val mainKaptClassesDir = mock(Provider::class.java) as Provider<Directory>

    `when`(sourceContainerArtifacts.get(InternalArtifactType.BUILT_IN_KOTLINC)).thenReturn(suiteKotlincDir)
    `when`(testedVariant.getBuiltInKotlincOutput()).thenReturn(mainKotlincDir)
    `when`(testedVariant.useBuiltInKotlinSupport).thenReturn(true)
    `when`(testedVariant.builtInKaptSupportMode).thenReturn(BuiltInKaptSupportMode.Supported.BuiltInKaptPluginApplied)
    `when`(testedVariant.useBuiltInKaptSupport).thenReturn(true)
    `when`(testedVariant.getBuiltInKaptArtifact(InternalArtifactType.BUILT_IN_KAPT_CLASSES_DIR)).thenReturn(mainKaptClassesDir)

    val creationConfig = TestSuiteApkCreationConfig(testSuite, sourceContainer)

    // Resolves BUILT_IN_KOTLINC from the test suite's own sourceContainer.artifacts rather than testedVariant
    assertThat(creationConfig.getBuiltInKotlincOutput()).isSameInstanceAs(suiteKotlincDir)

    // Built-in Kapt is disabled on test suites even if enabled on testedVariant
    assertThat(creationConfig.builtInKaptSupportMode).isEqualTo(BuiltInKaptSupportMode.NotSupported)
    assertThat(creationConfig.useBuiltInKaptSupport).isFalse()
    assertThat(creationConfig.getBuiltInKaptArtifact(InternalArtifactType.BUILT_IN_KAPT_CLASSES_DIR)).isNull()

    // When built-in Kotlin support is false, getBuiltInKotlincOutput() returns null
    `when`(testedVariant.useBuiltInKotlinSupport).thenReturn(false)
    assertThat(creationConfig.getBuiltInKotlincOutput()).isNull()
  }

  @Test
  fun testTestSuiteHostJarCreationConfigBuiltInKotlinAndKapt() {
    val testSuite = mock(TestSuiteCreationConfig::class.java)
    val testedVariant = mock(ApplicationCreationConfig::class.java, withSettings().extraInterfaces(Component::class.java))
    `when`(testSuite.testedVariant).thenReturn(testedVariant)

    val sourceContainer = mock(TestSuiteSourceContainer::class.java)
    val sourceContainerArtifacts = mock(ArtifactsImpl::class.java)
    `when`(sourceContainer.artifacts).thenReturn(sourceContainerArtifacts)

    @Suppress("UNCHECKED_CAST") val suiteKotlincDir = mock(Provider::class.java) as Provider<Directory>
    @Suppress("UNCHECKED_CAST") val mainKotlincDir = mock(Provider::class.java) as Provider<Directory>
    @Suppress("UNCHECKED_CAST") val mainKaptClassesDir = mock(Provider::class.java) as Provider<Directory>

    `when`(sourceContainerArtifacts.get(InternalArtifactType.BUILT_IN_KOTLINC)).thenReturn(suiteKotlincDir)
    `when`(testedVariant.getBuiltInKotlincOutput()).thenReturn(mainKotlincDir)
    `when`(testedVariant.useBuiltInKotlinSupport).thenReturn(true)
    `when`(testedVariant.builtInKaptSupportMode).thenReturn(BuiltInKaptSupportMode.Supported.BuiltInKaptPluginApplied)
    `when`(testedVariant.useBuiltInKaptSupport).thenReturn(true)
    `when`(testedVariant.getBuiltInKaptArtifact(InternalArtifactType.BUILT_IN_KAPT_CLASSES_DIR)).thenReturn(mainKaptClassesDir)

    val creationConfig = TestSuiteHostJarCreationConfig(testSuite, sourceContainer)

    // Resolves BUILT_IN_KOTLINC from the test suite's own sourceContainer.artifacts rather than testedVariant
    assertThat(creationConfig.getBuiltInKotlincOutput()).isSameInstanceAs(suiteKotlincDir)

    // Built-in Kapt is disabled on test suites even if enabled on testedVariant
    assertThat(creationConfig.builtInKaptSupportMode).isEqualTo(BuiltInKaptSupportMode.NotSupported)
    assertThat(creationConfig.useBuiltInKaptSupport).isFalse()
    assertThat(creationConfig.getBuiltInKaptArtifact(InternalArtifactType.BUILT_IN_KAPT_CLASSES_DIR)).isNull()

    // When built-in Kotlin support is false, getBuiltInKotlincOutput() returns null
    `when`(testedVariant.useBuiltInKotlinSupport).thenReturn(false)
    assertThat(creationConfig.getBuiltInKotlincOutput()).isNull()
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

  @Test
  fun testTestSuiteHostJarCreationConfigAndroidResources() {
    val testSuite = mock(TestSuiteCreationConfig::class.java)
    val testedVariant = mock(ApplicationCreationConfig::class.java, withSettings().extraInterfaces(Component::class.java))
    `when`(testSuite.testedVariant).thenReturn(testedVariant)

    val buildFeatures = mock(com.android.build.gradle.internal.scope.BuildFeatureValues::class.java)
    `when`(testedVariant.buildFeatures).thenReturn(buildFeatures)
    `when`(buildFeatures.androidResources).thenReturn(true)
    `when`(testedVariant.componentType).thenReturn(ComponentTypeImpl.BASE_APK)

    val baseResConfig = mock(com.android.build.gradle.internal.component.features.AndroidResourcesCreationConfig::class.java)
    `when`(testedVariant.androidResourcesCreationConfig).thenReturn(baseResConfig)

    val sourceContainer = mock(TestSuiteSourceContainer::class.java)
    val sourceContainerArtifacts = mock(ArtifactsImpl::class.java)
    `when`(sourceContainer.artifacts).thenReturn(sourceContainerArtifacts)

    @Suppress("UNCHECKED_CAST") val suiteRClassJar = mock(Provider::class.java) as Provider<org.gradle.api.file.RegularFile>
    `when`(sourceContainerArtifacts.get(InternalArtifactType.COMPILE_AND_RUNTIME_R_CLASS_JAR)).thenReturn(suiteRClassJar)

    `when`(testSuite.androidResourcesIncluded).thenReturn(true)
    val creationConfigWithRes = TestSuiteHostJarCreationConfig(testSuite, sourceContainer)
    assertThat(creationConfigWithRes.androidResourcesCreationConfig?.compiledRClassArtifact).isSameInstanceAs(suiteRClassJar)

    `when`(testSuite.androidResourcesIncluded).thenReturn(false)
    val creationConfigWithoutRes = TestSuiteHostJarCreationConfig(testSuite, sourceContainer)
    assertThat(creationConfigWithoutRes.androidResourcesCreationConfig).isSameInstanceAs(baseResConfig)
  }

  @Test
  fun testTestSuiteHostJarSourcesManifest() {
    val delegate = mock(InternalSources::class.java)
    val sourceContainer = mock(TestSuiteSourceContainer::class.java)
    val sourceSet = mock(HostJarTestSuiteSourceSet::class.java)
    `when`(sourceContainer.source).thenReturn(sourceSet)
    `when`(sourceContainer.project).thenReturn(ProjectFactory.project)

    val variantServices = createVariantPropertiesApiServices()
    val testedMainManifest = File("main/AndroidManifest.xml")
    val testedOverlay = File("debug/AndroidManifest.xml")
    `when`(delegate.manifestFile).thenReturn(testedMainManifest)
    `when`(delegate.manifestOverlayFiles).thenReturn(variantServices.provider { listOf(testedOverlay) })

    val suiteManifest = File("suite/AndroidManifest.xml")
    `when`(sourceSet.manifestFileCandidate).thenReturn(suiteManifest)

    val hostJarSources = TestSuiteHostJarSources(delegate, sourceContainer)
    assertThat(hostJarSources.manifestFile).isEqualTo(suiteManifest)
    assertThat(hostJarSources.manifestOverlayFiles.get()).isEmpty()
  }

  @Test
  fun testJavaCompileCreationConfigForTestSuiteBuiltInKotlincOutput() {
    val testSuite = mock(TestSuiteCreationConfig::class.java)
    val testedVariant = mock(ApplicationCreationConfig::class.java, withSettings().extraInterfaces(Component::class.java))
    `when`(testSuite.testedVariant).thenReturn(testedVariant)

    val sourceContainer = mock(TestSuiteSourceContainer::class.java)
    val sourceContainerArtifacts = mock(ArtifactsImpl::class.java)
    `when`(sourceContainer.artifacts).thenReturn(sourceContainerArtifacts)

    @Suppress("UNCHECKED_CAST") val suiteKotlincDir = mock(Provider::class.java) as Provider<Directory>
    `when`(sourceContainerArtifacts.get(InternalArtifactType.BUILT_IN_KOTLINC)).thenReturn(suiteKotlincDir)
    `when`(testedVariant.useBuiltInKotlinSupport).thenReturn(true)

    val sourceSet = mock(HostJarTestSuiteSourceSet::class.java)
    val services = mock(TaskCreationServices::class.java)
    val taskContainer = mock(MutableTaskContainer::class.java)
    val javaCompileConfig = JavaCompileCreationConfigForTestSuite(sourceContainer, sourceSet, testedVariant, services, taskContainer)

    assertThat(javaCompileConfig.builtInKotlincOutput).isNull()

    val hostJarConfig = TestSuiteHostJarCreationConfig(testSuite, sourceContainer)
    `when`(sourceContainer.creationConfig).thenReturn(hostJarConfig)
    assertThat(javaCompileConfig.builtInKotlincOutput).isSameInstanceAs(suiteKotlincDir)

    `when`(testedVariant.useBuiltInKotlinSupport).thenReturn(false)
    assertThat(javaCompileConfig.builtInKotlincOutput).isNull()
  }
}
