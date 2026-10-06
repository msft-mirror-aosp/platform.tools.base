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

package com.android.build.gradle.internal.testsuites

import com.android.build.api.dsl.TestTaskContext
import com.android.build.gradle.internal.fixtures.FakeProviderFactory
import com.android.build.gradle.internal.services.DslServices
import com.android.build.gradle.internal.services.createDslServices
import com.android.build.gradle.internal.services.createProjectServices
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.ProjectOptions
import com.google.common.collect.ImmutableMap
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test as GradleTest
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScreenshotTestSuiteTaskConfiguratorTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var project: Project

  @Before
  fun setUp() {
    project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder("project")).build()
  }

  private fun createServices(useSourceReferenceDirectories: Boolean? = null): DslServices {
    val properties =
      if (useSourceReferenceDirectories != null) {
        ImmutableMap.of<String, Any>(
          BooleanOption.SCREENSHOT_TEST_USE_SOURCE_REFERENCE_DIRECTORIES.propertyName,
          useSourceReferenceDirectories,
        )
      } else {
        ImmutableMap.of()
      }
    val fakeProviderFactory = FakeProviderFactory(project.providers, properties)
    val projectOptions = ProjectOptions(fakeProviderFactory)
    val projectServices = createProjectServices(project = project, projectOptions = projectOptions)
    return createDslServices(projectServices = projectServices)
  }

  private fun createContext(
    isUpdate: Boolean,
    suiteName: String = "screenshotTest",
    targetName: String = "default",
    targetedVariant: String = "debug",
  ): TestTaskContext =
    object : TestTaskContext {
      override val targetName: String = targetName
      override val isUpdateTask: Boolean = isUpdate
      override val suiteName: String = suiteName
      override val targetedVariant: String = targetedVariant
      override val targetedDevices: Collection<String> = emptyList()
    }

  @Test
  fun `default configuration uses source reference directory for update and validate`() {
    val dslServices = createServices()
    val configurator = ScreenshotTestSuiteTaskConfigurator("screenshotTest")

    val updateTask = project.tasks.register("updateDebugScreenshotTestDefaultTestSuite", GradleTest::class.java).get()
    val validateTask = project.tasks.register("testDebugScreenshotTestDefaultTestSuite", GradleTest::class.java).get()

    configurator.configureTask(updateTask, createContext(isUpdate = true), dslServices, project.providers, threshold = 0.01f)
    configurator.configureTask(validateTask, createContext(isUpdate = false), dslServices, project.providers, threshold = 0.01f)

    val expectedRelativeRefPath = listOf("src", "screenshotTestDefaultDebug", "reference").joinToString(File.separator)
    val expectedAbsRefDir = project.projectDir.resolve("src/screenshotTestDefaultDebug/reference")

    val updateArgs = updateTask.jvmArgumentProviders.single().asArguments().toList()
    assertThat(updateArgs)
      .containsExactly(
        "-DPreviewScreenshotTestEngineInput.referenceImageDir=$expectedRelativeRefPath",
        "-DPreviewScreenshotTestEngineInput.projectRoot=${project.rootDir.absolutePath}",
        "-DPreviewScreenshotTestEngineInput.ImageDiffer.threshold=0.01",
      )
    assertThat(updateTask.systemProperties["PreviewScreenshotTestEngineInput.TestOption.recordingModeEnabled"]).isEqualTo("true")
    assertThat(updateTask.outputs.files.files).contains(expectedAbsRefDir)

    val validateArgs = validateTask.jvmArgumentProviders.single().asArguments().toList()
    assertThat(validateArgs)
      .containsExactly(
        "-DPreviewScreenshotTestEngineInput.referenceImageDir=$expectedRelativeRefPath",
        "-DPreviewScreenshotTestEngineInput.projectRoot=${project.rootDir.absolutePath}",
        "-DPreviewScreenshotTestEngineInput.ImageDiffer.threshold=0.01",
      )
    assertThat(validateTask.systemProperties["PreviewScreenshotTestEngineInput.TestOption.recordingModeEnabled"]).isEqualTo("false")
  }

  @Test
  fun `useSourceReferenceDirectories false uses build output reference directory for update and validate`() {
    val dslServices = createServices(useSourceReferenceDirectories = false)
    val configurator = ScreenshotTestSuiteTaskConfigurator("screenshotTest")

    val updateTask = project.tasks.register("updateDebugScreenshotTestDefaultTestSuite", GradleTest::class.java).get()
    val validateTask = project.tasks.register("testDebugScreenshotTestDefaultTestSuite", GradleTest::class.java).get()

    configurator.configureTask(updateTask, createContext(isUpdate = true), dslServices, project.providers, threshold = null)
    configurator.configureTask(validateTask, createContext(isUpdate = false), dslServices, project.providers, threshold = null)

    val expectedRelativeRefPath =
      listOf("build", "outputs", "screenshotTest-results", "screenshotTestDefaultDebug", "reference").joinToString(File.separator)
    val expectedAbsRefDir =
      project.layout.buildDirectory.dir("outputs/screenshotTest-results/screenshotTestDefaultDebug/reference").get().asFile

    val updateArgs = updateTask.jvmArgumentProviders.single().asArguments().toList()
    assertThat(updateArgs)
      .containsExactly(
        "-DPreviewScreenshotTestEngineInput.referenceImageDir=$expectedRelativeRefPath",
        "-DPreviewScreenshotTestEngineInput.projectRoot=${project.rootDir.absolutePath}",
      )
    assertThat(updateTask.outputs.files.files).contains(expectedAbsRefDir)

    val validateArgs = validateTask.jvmArgumentProviders.single().asArguments().toList()
    assertThat(validateArgs)
      .containsExactly(
        "-DPreviewScreenshotTestEngineInput.referenceImageDir=$expectedRelativeRefPath",
        "-DPreviewScreenshotTestEngineInput.projectRoot=${project.rootDir.absolutePath}",
      )
  }

  @Test
  fun `validate fails fast when useSourceReferenceDirectories is false and local reference directory is missing`() {
    val dslServices = createServices(useSourceReferenceDirectories = false)
    val configurator = ScreenshotTestSuiteTaskConfigurator("screenshotTest")

    val validateTask = project.tasks.register("testDebugScreenshotTestDefaultTestSuite", GradleTest::class.java).get()
    configurator.configureTask(validateTask, createContext(isUpdate = false), dslServices, project.providers, threshold = null)

    val exception =
      assertThrows(GradleException::class.java) {
        validateTask.actions.first().execute(validateTask)
      }
    assertThat(exception.message).contains("Local reference screenshot directory")
    assertThat(exception.message).contains("does not exist or is empty")
    assertThat(exception.message).contains("run the screenshot update task for variant 'debug' first")
    assertThat(exception.message).contains("android.screenshotTest.useSourceReferenceDirectories=false")
    assertThat(exception.message).contains("android.screenshotTest.useSourceReferenceDirectories=true")
  }

  @Test
  fun `validate fails fast when useSourceReferenceDirectories is false and local reference directory is empty`() {
    val dslServices = createServices(useSourceReferenceDirectories = false)
    val configurator = ScreenshotTestSuiteTaskConfigurator("screenshotTest")

    val localRefDir = project.layout.buildDirectory.dir("outputs/screenshotTest-results/screenshotTestDefaultDebug/reference").get().asFile
    // Create empty subdirectories with no files
    File(localRefDir, "pkg/name/ExampleTest").mkdirs()

    val validateTask = project.tasks.register("testDebugScreenshotTestDefaultTestSuite", GradleTest::class.java).get()
    configurator.configureTask(validateTask, createContext(isUpdate = false), dslServices, project.providers, threshold = null)

    val exception =
      assertThrows(GradleException::class.java) {
        validateTask.actions.first().execute(validateTask)
      }
    assertThat(exception.message).contains("does not exist or is empty")
    assertThat(exception.message).contains("run the screenshot update task for variant 'debug' first")
  }

  @Test
  fun `validate pre-check passes when useSourceReferenceDirectories is false and local reference directory has files`() {
    val dslServices = createServices(useSourceReferenceDirectories = false)
    val configurator = ScreenshotTestSuiteTaskConfigurator("screenshotTest")

    val localRefDir = project.layout.buildDirectory.dir("outputs/screenshotTest-results/screenshotTestDefaultDebug/reference").get().asFile
    val imageFile = File(localRefDir, "pkg/name/ExampleTest/preview_0.png")
    imageFile.parentFile.mkdirs()
    imageFile.writeText("fake-png")

    val validateTask = project.tasks.register("testDebugScreenshotTestDefaultTestSuite", GradleTest::class.java).get()
    configurator.configureTask(validateTask, createContext(isUpdate = false), dslServices, project.providers, threshold = null)

    // Executing the first action (our doFirst check) should not throw
    validateTask.actions.first().execute(validateTask)
  }

  @Test
  fun `custom suite target and variant names resolve reference directories accurately`() {
    val dslServices = createServices(useSourceReferenceDirectories = false)
    val configurator = ScreenshotTestSuiteTaskConfigurator("composeScreenshots")

    val updateTask = project.tasks.register("updateFreeReleaseComposeScreenshotsWearTestSuite", GradleTest::class.java).get()
    val validateTask = project.tasks.register("testFreeReleaseComposeScreenshotsWearTestSuite", GradleTest::class.java).get()

    val contextUpdate =
      createContext(isUpdate = true, suiteName = "composeScreenshots", targetName = "wear", targetedVariant = "freeRelease")
    val contextValidate =
      createContext(isUpdate = false, suiteName = "composeScreenshots", targetName = "wear", targetedVariant = "freeRelease")

    configurator.configureTask(updateTask, contextUpdate, dslServices, project.providers, threshold = null)
    configurator.configureTask(validateTask, contextValidate, dslServices, project.providers, threshold = null)

    val expectedRelativeRefPath =
      listOf("build", "outputs", "screenshotTest-results", "composeScreenshotsWearFreeRelease", "reference").joinToString(File.separator)
    assertThat(updateTask.jvmArgumentProviders.single().asArguments().toList())
      .contains("-DPreviewScreenshotTestEngineInput.referenceImageDir=$expectedRelativeRefPath")
    assertThat(validateTask.jvmArgumentProviders.single().asArguments().toList())
      .contains("-DPreviewScreenshotTestEngineInput.referenceImageDir=$expectedRelativeRefPath")

    val exception =
      assertThrows(GradleException::class.java) {
        validateTask.actions.first().execute(validateTask)
      }
    assertThat(exception.message).contains("run the screenshot update task for variant 'freeRelease' first")
  }

  @Test
  fun `custom buildDirectory outside project directory resolves relative reference path when useSourceReferenceDirectories is false`() {
    val externalBuildDir = temporaryFolder.newFolder("external-build")
    project.layout.buildDirectory.set(externalBuildDir)

    val dslServices = createServices(useSourceReferenceDirectories = false)
    val configurator = ScreenshotTestSuiteTaskConfigurator("screenshotTest")

    val updateTask = project.tasks.register("updateDebugScreenshotTestDefaultTestSuite", GradleTest::class.java).get()
    val validateTask = project.tasks.register("testDebugScreenshotTestDefaultTestSuite", GradleTest::class.java).get()

    configurator.configureTask(updateTask, createContext(isUpdate = true), dslServices, project.providers, threshold = null)
    configurator.configureTask(validateTask, createContext(isUpdate = false), dslServices, project.providers, threshold = null)

    val expectedRelativeRefPath =
      listOf("..", "external-build", "outputs", "screenshotTest-results", "screenshotTestDefaultDebug", "reference")
        .joinToString(File.separator)
    val expectedAbsRefDir = externalBuildDir.resolve("outputs/screenshotTest-results/screenshotTestDefaultDebug/reference")

    assertThat(updateTask.jvmArgumentProviders.single().asArguments().toList())
      .contains("-DPreviewScreenshotTestEngineInput.referenceImageDir=$expectedRelativeRefPath")
    assertThat(updateTask.outputs.files.files).contains(expectedAbsRefDir)

    assertThat(validateTask.jvmArgumentProviders.single().asArguments().toList())
      .contains("-DPreviewScreenshotTestEngineInput.referenceImageDir=$expectedRelativeRefPath")
  }
}
