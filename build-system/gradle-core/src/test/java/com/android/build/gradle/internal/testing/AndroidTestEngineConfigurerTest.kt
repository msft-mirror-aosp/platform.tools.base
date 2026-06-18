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

package com.android.build.gradle.internal.testing

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.api.testsuites.TestEngineInputProperty
import com.android.build.api.testsuites.TestSuiteExecutionClient.Companion.DEFAULT_ENV_VARIABLE
import com.android.build.gradle.internal.BuildToolsExecutableInput
import com.android.build.gradle.internal.component.DeviceTestCreationConfig
import com.android.build.gradle.internal.component.InstrumentedTestCreationConfig
import com.android.build.gradle.internal.component.VariantCreationConfig
import com.android.build.gradle.internal.services.createProjectServices
import com.android.build.gradle.internal.services.createTaskCreationServices
import com.android.build.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.android.build.gradle.tasks.LegacyReportingTestSuiteTestTask
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.kotlin.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.whenever

class AndroidTestEngineConfigurerTest {

  @get:Rule val tempFolderRule = TemporaryFolder()

  private lateinit var project: Project
  private lateinit var task: LegacyReportingTestSuiteTestTask
  private lateinit var spyTask: LegacyReportingTestSuiteTestTask
  private val mockBuildTools: BuildToolsExecutableInput = mock()
  private val creationConfig: InstrumentedTestCreationConfig = mock(defaultAnswer = RETURNS_DEEP_STUBS)
  private val globalConfig: GlobalTaskCreationConfig = mock(defaultAnswer = RETURNS_DEEP_STUBS)
  private val testData: TestData = mock(defaultAnswer = RETURNS_DEEP_STUBS)

  @Before
  fun setup() {
    project = ProjectBuilder.builder().withProjectDir(tempFolderRule.root).build()
    project.plugins.apply("java")
    task = project.tasks.register("testTask", LegacyReportingTestSuiteTestTask::class.java).get()
    spyTask = spy(task)

    // Stub buildTools
    whenever(spyTask.buildTools).thenReturn(mockBuildTools)
    val aapt2File = tempFolderRule.newFile("aapt2")
    val aapt2Provider = project.objects.fileProperty().fileValue(aapt2File)
    whenever(mockBuildTools.aapt2ExecutableProvider()).thenReturn(aapt2Provider)

    val services = createTaskCreationServices(createProjectServices(project = project))
    whenever(creationConfig.services).thenReturn(services)
    whenever(creationConfig.global).thenReturn(globalConfig)
    whenever(globalConfig.services).thenReturn(services)

    whenever(testData.applicationId).thenReturn(project.providers.provider { "com.example.app" })
    whenever(testData.instrumentationRunner).thenReturn(project.providers.provider { "androidx.test.runner.AndroidJUnitRunner" })
    whenever(testData.instrumentationTargetPackageId).thenReturn(project.providers.provider { "com.example.app.target" })
    whenever(testData.instrumentationRunnerArguments).thenReturn(project.providers.provider { mapOf("arg1" to "value1") })
    whenever(testData.testCoverageEnabled).thenReturn(project.providers.provider { false })

    whenever(creationConfig.name).thenReturn("debug")
    whenever(creationConfig.isForceAotCompilation).thenReturn(false)
  }

  @Test
  fun testConfigure() {
    // Mock default artifacts for creationConfig
    val testApkDirectory = project.objects.directoryProperty().fileValue(tempFolderRule.newFolder("test_apks"))
    whenever(creationConfig.artifacts.get(SingleArtifact.APK)).thenReturn(testApkDirectory)

    configureAndroidTestEngine(spyTask, creationConfig, testData, "mySubFolder")

    // Verify some properties
    assertThat(spyTask.engineInputProperties.get()).containsEntry(TestEngineInputProperty.TESTED_APPLICATION_ID, "com.example.app")
    assertThat(spyTask.engineInputProperties.get())
      .containsEntry("android-test.instrumentation-runner-class", "androidx.test.runner.AndroidJUnitRunner")
    assertThat(spyTask.engineInputProperties.get()).containsEntry("android-test.test-package-id", "com.example.app")
    assertThat(spyTask.engineInputProperties.get())
      .containsEntry("android-test.instrumentation-target-package-id", "com.example.app.target")
    assertThat(spyTask.engineInputProperties.get()).containsEntry("android-test.instrumentation-args", "arg1=value1")

    // Verify file paths
    val buildDir = project.layout.buildDirectory.get().asFile.absolutePath
    assertThat(spyTask.engineInputPropertiesFiles.get().asFile.absolutePath)
      .isEqualTo("$buildDir/intermediates/androidTest/debug/mySubFolder/junit_inputs.txt".replace('/', File.separatorChar))
    // Verify environment
    assertThat(spyTask.environment).containsEntry(DEFAULT_ENV_VARIABLE, spyTask.engineInputPropertiesFiles.get().asFile.absolutePath)

    // Verify Classpath
    val classpath = spyTask.classpath as ConfigurableFileCollection
    assertThat(classpath.from).isNotEmpty()
    val configuration = classpath.from.first() as Configuration
    assertThat(configuration.dependencies.map { it.name }).contains("android-test-engine")

    // Verify JUnit Platform options
    val options = spyTask.options as JUnitPlatformOptions
    assertThat(options.includeEngines).contains("android-test-engine")

    // Verify engine input parameters (only TEST_APKS should be present for this setup)
    val parameters = spyTask.engineInputParameters.get()
    assertThat(parameters.map { it.type }).containsExactly(AgpTestSuiteInputParameters.TEST_APKS)
    assertThat(spyTask.animationsDisabled.get()).isFalse()
  }

  @Test
  fun testConfigureWithDeviceTestCreationConfig() {
    val deviceTestCreationConfig: DeviceTestCreationConfig = mock(defaultAnswer = RETURNS_DEEP_STUBS)
    val mainVariant: VariantCreationConfig = mock(defaultAnswer = RETURNS_DEEP_STUBS)

    val services = createTaskCreationServices(createProjectServices(project = project))
    whenever(deviceTestCreationConfig.services).thenReturn(services)
    whenever(deviceTestCreationConfig.global).thenReturn(globalConfig)
    whenever(globalConfig.services).thenReturn(services)

    whenever(deviceTestCreationConfig.mainVariant).thenReturn(mainVariant)
    val componentType = mock<com.android.builder.core.ComponentType>()
    whenever(mainVariant.componentType).thenReturn(componentType)
    whenever(componentType.isAar).thenReturn(false)
    whenever(mainVariant.name).thenReturn("debugMain")

    val testedApkDirectory = project.objects.directoryProperty().fileValue(tempFolderRule.newFolder("tested_apks"))
    whenever(mainVariant.artifacts.get(SingleArtifact.APK)).thenReturn(testedApkDirectory)

    val testApkDirectory = project.objects.directoryProperty().fileValue(tempFolderRule.newFolder("test_apks"))
    whenever(deviceTestCreationConfig.artifacts.get(SingleArtifact.APK)).thenReturn(testApkDirectory)

    whenever(deviceTestCreationConfig.name).thenReturn("debugAndroidTest")
    whenever(deviceTestCreationConfig.isForceAotCompilation).thenReturn(true)

    configureAndroidTestEngine(spyTask, deviceTestCreationConfig, testData, "mySubFolder")

    // Verify engine input parameters (both TESTED_APKS and TEST_APKS should be present)
    val parameters = spyTask.engineInputParameters.get()
    assertThat(parameters.map { it.type }).containsExactly(AgpTestSuiteInputParameters.TESTED_APKS, AgpTestSuiteInputParameters.TEST_APKS)

    // Verify values of parameters
    val testedApksParam = parameters.first { it.type == AgpTestSuiteInputParameters.TESTED_APKS }
    assertThat(testedApksParam.fileCollection.files.single().absolutePath).isEqualTo(testedApkDirectory.get().asFile.absolutePath)

    val testApksParam = parameters.first { it.type == AgpTestSuiteInputParameters.TEST_APKS }
    assertThat(testApksParam.fileCollection.files.single().absolutePath).isEqualTo(testApkDirectory.get().asFile.absolutePath)

    // Verify other properties that depend on creationConfig
    assertThat(spyTask.engineInputProperties.get()).containsEntry("android-test.force-aot-compilation", "true")
    val buildDir = project.layout.buildDirectory.get().asFile.absolutePath
    assertThat(spyTask.engineInputPropertiesFiles.get().asFile.absolutePath)
      .isEqualTo("$buildDir/intermediates/androidTest/debugAndroidTest/mySubFolder/junit_inputs.txt".replace('/', File.separatorChar))
  }
}
