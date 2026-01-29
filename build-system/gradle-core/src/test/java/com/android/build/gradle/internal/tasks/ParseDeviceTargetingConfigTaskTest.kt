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

package com.android.build.gradle.internal.tasks

import com.android.build.api.dsl.Bundle
import com.android.build.gradle.internal.component.VariantCreationConfig
import com.android.build.gradle.internal.dsl.BundleOptions
import com.android.build.gradle.internal.dsl.decorator.androidPluginDslDecorator
import com.android.build.gradle.internal.fixtures.FakeGradleProperty
import com.android.build.gradle.internal.fixtures.FakeNoOpAnalyticsService
import com.android.build.gradle.internal.fixtures.FakeProviderFactory
import com.android.build.gradle.internal.profile.AnalyticsService
import com.android.build.gradle.internal.scope.MutableTaskContainer
import com.android.build.gradle.internal.services.DslServices
import com.android.build.gradle.internal.services.TaskCreationServices
import com.android.build.gradle.internal.services.createDslServices
import com.android.build.gradle.internal.services.createProjectServices
import com.android.build.gradle.internal.services.createTaskCreationServices
import com.android.build.gradle.internal.services.getBuildServiceName
import com.android.build.gradle.internal.tasks.factory.GlobalTaskCreationConfig
import com.android.build.gradle.options.ProjectOptions
import com.android.builder.core.ComponentType
import com.android.bundle.DeviceGroup
import com.android.bundle.DeviceGroupConfig
import com.android.bundle.DeviceRam
import com.android.bundle.DeviceSelector
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import com.google.common.collect.ImmutableMap
import com.google.common.truth.Truth.assertThat
import java.io.FileInputStream
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.TaskProvider
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

private const val SAMPLE_CONFIG_XML =
  """
    <config:device-targeting-config
      xmlns:config="http://schemas.android.com/apk/config">

      <config:device-group name="highRam">
        <config:device-selector ram-min-bytes="500"/>
      </config:device-group>

    </config:device-targeting-config>
    """

class ParseDeviceTargetingConfigTaskTest {

  private val SAMPLE_CONFIG_PROTO =
    DeviceGroupConfig.newBuilder()
      .addDeviceGroups(
        DeviceGroup.newBuilder()
          .setName("highRam")
          .addDeviceSelectors(DeviceSelector.newBuilder().setDeviceRam(DeviceRam.newBuilder().setMinBytes(500L)))
      )
      .build()

  @get:Rule val testFolder = TemporaryFolder()

  lateinit var project: Project

  // Under test
  lateinit var task: ParseDeviceTargetingConfigTask

  private val gradleProperties = ImmutableMap.of<String, Any>()

  private lateinit var taskCreationServices: TaskCreationServices
  private lateinit var dslServices: DslServices

  @Before
  fun setup() {
    project = ProjectBuilder.builder().withProjectDir(testFolder.newFolder()).build()
    task = project.tasks.create("test", ParseDeviceTargetingConfigTask::class.java)
    project.gradle.sharedServices.registerIfAbsent(getBuildServiceName(AnalyticsService::class.java), AnalyticsService::class.java) {}

    val projectServices =
      createProjectServices(
        project = project,
        projectOptions = ProjectOptions(FakeProviderFactory(FakeProviderFactory.factory, gradleProperties)),
      )
    taskCreationServices = createTaskCreationServices(projectServices)
    dslServices = createDslServices(projectServices)
  }

  @Test
  fun testParseConfig() {
    val configXml = project.projectDir.resolve("device_targeting_config.xml")
    FileUtils.writeToFile(configXml, SAMPLE_CONFIG_XML)

    val configProto = testFolder.root.resolve("expected_output")
    // Under test
    object : ParseDeviceTargetingConfigTask.ParseDeviceTargetingConfigRunnable() {
        override fun getParameters(): ParseDeviceTargetingConfigTask.Params {
          return object : ParseDeviceTargetingConfigTask.Params() {
            override val deviceTargetingConfigXml = project.objects.fileProperty().fileValue(configXml)
            override val deviceTargetingConfigProto = project.objects.fileProperty().fileValue(configProto)
            override val projectPath = FakeGradleProperty("projectName")
            override val taskOwner = FakeGradleProperty("taskOwner")
            override val workerKey = FakeGradleProperty("workerKey")
            override val analyticsService: Property<AnalyticsService> = FakeGradleProperty(FakeNoOpAnalyticsService())
          }
        }
      }
      .execute()

    assertThat(configProto).exists()
    val config = DeviceGroupConfig.parseFrom(FileInputStream(configProto))
    assertThat(config).isEqualTo(SAMPLE_CONFIG_PROTO)
  }

  private interface BundleWrapper {
    val bundle: Bundle
  }

  @Test
  fun testConfigureTask() {
    val configXml = project.projectDir.resolve("test_config.xml")
    FileUtils.writeToFile(configXml, SAMPLE_CONFIG_XML)

    val bundleOptions =
      androidPluginDslDecorator
        .decorate(BundleWrapper::class.java)
        .getDeclaredConstructor(DslServices::class.java)
        .newInstance(dslServices)
        .bundle as BundleOptions
    bundleOptions.deviceTargetingConfig.set(configXml)
    val componentProperties = createScopeFromBundleOptions(bundleOptions)

    val taskAction = ParseDeviceTargetingConfigTask.CreationAction(componentProperties)
    taskAction.preConfigure(task.name)

    // Under test
    taskAction.configure(task)

    assertThat(task.deviceTargetingConfigXml.isPresent).isTrue()
    val configFile = task.deviceTargetingConfigXml.asFile.get()
    assertThat(configFile).exists()
    assertThat(configFile).contains(SAMPLE_CONFIG_XML)
  }

  private fun createScopeFromBundleOptions(bundleOptions: BundleOptions): VariantCreationConfig {
    val componentProperties = mock<VariantCreationConfig>()
    val componentType = mock<ComponentType>()
    val globalConfig = mock<GlobalTaskCreationConfig>()
    val taskContainer = mock<MutableTaskContainer>()
    val preBuildTask = mock<TaskProvider<*>>()

    whenever(componentProperties.services).thenReturn(taskCreationServices)
    whenever(componentProperties.componentType).thenReturn(componentType)
    whenever(componentProperties.name).thenReturn("variant")
    whenever(componentProperties.taskContainer).thenReturn(taskContainer)
    whenever(componentProperties.global).thenReturn(globalConfig)
    whenever(globalConfig.bundleOptions).thenReturn(bundleOptions)
    whenever(taskContainer.preBuildTask).thenReturn(preBuildTask)

    return componentProperties
  }
}
