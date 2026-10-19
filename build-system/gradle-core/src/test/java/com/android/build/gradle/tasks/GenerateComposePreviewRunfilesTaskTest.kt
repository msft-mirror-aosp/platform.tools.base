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

package com.android.build.gradle.tasks

import com.android.build.gradle.internal.component.ComponentCreationConfig
import com.android.build.gradle.internal.fixtures.FakeNoOpAnalyticsService
import com.android.build.gradle.internal.profile.AnalyticsService
import com.android.build.gradle.internal.services.getBuildServiceName
import com.android.build.gradle.internal.utils.setDisallowChanges
import com.android.builder.core.ComponentTypeImpl
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when` as whenever

class GenerateComposePreviewRunfilesTaskTest {

  @get:Rule var temporaryFolder = TemporaryFolder()

  private lateinit var project: Project
  private lateinit var task: GenerateComposePreviewRunfilesTask

  @Before
  fun setUp() {
    project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder("project")).build()
    val taskProvider = project.tasks.register("generateDebugComposePreviewRunfiles", GenerateComposePreviewRunfilesTask::class.java)
    task = taskProvider.get()
    val analyticsService =
      project.gradle.sharedServices.registerIfAbsent(
        getBuildServiceName(AnalyticsService::class.java),
        FakeNoOpAnalyticsService::class.java,
      )
    task.analyticsService.setDisallowChanges(analyticsService)
    task.variantName = "debug"
    task.projectPath.set(":")
  }

  private fun jsonPath(file: File): String = file.absolutePath.replace("\\", "\\\\")

  @Test
  fun testComposePreviewRunfilesDataJsonSerialization() {
    val data =
      ComposePreviewRunfilesData(
        packageName = "com.example.app",
        resourceApk = "/path/to/resources.ap_",
        projectClasspath = listOf("/path/to/R.jar", "/path/to/classes"),
        classpath = listOf("/path/to/dep1.jar", "/path/to/dep2.jar"),
      )

    val json = data.toJson()
    assertThat(json).contains("\"packageName\": \"com.example.app\"")
    assertThat(json).doesNotContain("\"package\":")
    assertThat(json).contains("\"resourceApk\": \"/path/to/resources.ap_\"")
    assertThat(json).contains("\"/path/to/R.jar\"")
    assertThat(json).contains("\"/path/to/classes\"")
    assertThat(json).contains("\"/path/to/dep1.jar\"")
    assertThat(json).contains("\"/path/to/dep2.jar\"")
  }

  @Test
  fun testTaskExecutionGeneratesManifest() {
    val classesDir = temporaryFolder.newFolder("classes")
    val projectJar = temporaryFolder.newFile("feature.jar")
    val rJar = temporaryFolder.newFile("R.jar")
    val runtimeJar = temporaryFolder.newFile("dep.jar")
    val resApk = temporaryFolder.newFile("app.ap_")
    val outputFile = temporaryFolder.root.resolve("preview_runfiles.json")

    task.packageName.set("com.test.preview")
    task.projectClasses.from(classesDir, projectJar)
    task.rJar.set(project.layout.projectDirectory.file(rJar.absolutePath))
    task.runtimeClasspath.from(runtimeJar)
    task.resourceApk.set(project.layout.projectDirectory.file(resApk.absolutePath))
    task.setComposePreviewManifestFileOption(outputFile.absolutePath)

    task.taskAction()

    assertThat(outputFile.exists()).isTrue()
    val content = outputFile.readText()
    assertThat(content).contains("\"packageName\": \"com.test.preview\"")
    assertThat(content).contains(jsonPath(rJar))
    assertThat(content).contains(jsonPath(classesDir))
    assertThat(content).contains(jsonPath(projectJar))
    assertThat(content).contains(jsonPath(runtimeJar))
    assertThat(content).contains(jsonPath(resApk))

    val pointerDir = File(project.rootDir, ".gradle/compose_preview")
    assertThat(pointerDir.exists()).isFalse()
  }

  @Test
  fun testTaskExecutionWithoutComposePreviewManifestFileThrows() {
    val rJar = temporaryFolder.newFile("sub_R.jar")
    val resApk = temporaryFolder.newFile("sub_app.ap_")

    task.packageName.set("com.test.sub")
    task.rJar.set(project.layout.projectDirectory.file(rJar.absolutePath))
    task.resourceApk.set(project.layout.projectDirectory.file(resApk.absolutePath))

    val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) { task.taskAction() }
    assertThat(error).hasMessageThat().contains("composePreviewManifestFile")
  }

  @Test
  fun testTaskExecutionWithoutResourceApkThrows() {
    val rJar = temporaryFolder.newFile("R.jar")
    val outputFile = temporaryFolder.root.resolve("preview_runfiles.json")

    task.packageName.set("com.test.nores")
    task.rJar.set(project.layout.projectDirectory.file(rJar.absolutePath))
    task.setComposePreviewManifestFileOption(outputFile.absolutePath)

    val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) { task.taskAction() }
    assertThat(error).isNotNull()
  }

  @Test
  fun testTaskExecutionWithoutRJarThrows() {
    val resApk = temporaryFolder.newFile("app.ap_")
    val outputFile = temporaryFolder.root.resolve("preview_runfiles.json")

    task.packageName.set("com.test.norjar")
    task.resourceApk.set(project.layout.projectDirectory.file(resApk.absolutePath))
    task.setComposePreviewManifestFileOption(outputFile.absolutePath)

    val error = org.junit.Assert.assertThrows(IllegalStateException::class.java) { task.taskAction() }
    assertThat(error).isNotNull()
  }

  @Test
  fun testComputePackageNameForApplicationVariantUsesApplicationId() {
    val creationConfig = mock(ComponentCreationConfig::class.java)
    whenever(creationConfig.componentType).thenReturn(ComponentTypeImpl.BASE_APK)
    whenever(creationConfig.applicationId).thenReturn(project.provider { "com.example.custom.appid" })
    whenever(creationConfig.namespace).thenReturn(project.provider { "com.example.libnamespace" })

    val result = GenerateComposePreviewRunfilesTask.computePackageName(creationConfig)
    assertThat(result.get()).isEqualTo("com.example.custom.appid")
  }

  @Test
  fun testComputePackageNameForApplicationVariantFallsBackToNamespaceWhenApplicationIdUnset() {
    val creationConfig = mock(ComponentCreationConfig::class.java)
    whenever(creationConfig.componentType).thenReturn(ComponentTypeImpl.BASE_APK)
    val unsetAppId = project.objects.property(String::class.java)
    whenever(creationConfig.applicationId).thenReturn(unsetAppId)
    whenever(creationConfig.namespace).thenReturn(project.provider { "com.example.fallback.namespace" })

    val result = GenerateComposePreviewRunfilesTask.computePackageName(creationConfig)
    assertThat(result.get()).isEqualTo("com.example.fallback.namespace")
  }

  @Test
  fun testComputePackageNameForLibraryVariantUsesNamespace() {
    val creationConfig = mock(ComponentCreationConfig::class.java)
    whenever(creationConfig.componentType).thenReturn(ComponentTypeImpl.LIBRARY)
    whenever(creationConfig.namespace).thenReturn(project.provider { "com.example.library" })

    val result = GenerateComposePreviewRunfilesTask.computePackageName(creationConfig)
    assertThat(result.get()).isEqualTo("com.example.library")
  }
}
