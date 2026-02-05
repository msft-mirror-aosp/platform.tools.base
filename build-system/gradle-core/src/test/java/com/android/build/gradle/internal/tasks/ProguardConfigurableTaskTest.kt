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

package com.android.build.gradle.internal.tasks

import com.android.build.gradle.ProguardFiles
import com.android.build.gradle.internal.fixtures.FakeGradleProvider
import com.android.builder.core.ComponentTypeImpl
import com.android.builder.dexing.KeepRuleFile
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import javax.inject.Inject
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.assertFailsWith
import org.gradle.api.Project
import org.gradle.api.file.ProjectLayout
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

internal class ProguardConfigurableTaskTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  lateinit var project: Project
  lateinit var task: ProguardConfigurableTask

  abstract class ProguardTestTask @Inject constructor(projectLayout: ProjectLayout) : ProguardConfigurableTask(projectLayout) {
    override fun doTaskAction() {}
  }

  @Before
  fun setup() {
    project = ProjectBuilder.builder().withProjectDir(temporaryFolder.root).build()
    task = project.tasks.register("proguardConfigurationTask", ProguardTestTask::class.java, project.layout).get()
  }

  @Test
  fun testEmptyProguardFilesReconciliation() {
    Truth.assertThat(task).isNotNull()
    val fileCollection = listOf<KeepRuleFile>()
    val folder = temporaryFolder.newFolder("proguard_files")
    task.componentType.set(ComponentTypeImpl.BASE_APK)
    Truth.assertThat(
        task.reconcileDefaultProguardFile(
          fileCollection,
          FakeGradleProvider(project.layout.projectDirectory.dir(folder.absolutePath)),
          false,
        )
      )
      .isEmpty()
  }

  @Test
  fun testNonBaseModuleProguardFilesReconciliation() {
    Truth.assertThat(task).isNotNull()

    val folder = temporaryFolder.newFolder("proguard_files").toPath()
    val file1 = folder.resolve("android.txt")
    file1.writeText("foo")
    val keepRules = listOf(KeepRuleFile.WithoutOrigin(file1))
    task.componentType.set(ComponentTypeImpl.JAVA_LIBRARY)
    val result =
      task.reconcileDefaultProguardFile(
        keepRules,
        FakeGradleProvider(project.layout.projectDirectory.dir(folder.toAbsolutePath().toString())),
        false,
      )
    assertThat(result).isEqualTo(keepRules)
  }

  @Test
  fun testSubstitution() {
    Truth.assertThat(task).isNotNull()
    val srcFolder = temporaryFolder.newFolder("proguard_files").toPath()
    val finalDefaultFolder = temporaryFolder.newFolder("default_proguard_files")

    val defaultFile = ProguardFiles.getDefaultProguardFile(ProguardFiles.ProguardFile.OPTIMIZE.fileName, project.layout.buildDirectory)

    val file1 = srcFolder.resolve("user1.txt").also { it.writeText("") }
    val file2 = srcFolder.resolve("user1.txt").also { it.writeText("") }

    val keepRules = listOf(file1, file2, defaultFile.toPath()).map { KeepRuleFile.WithoutOrigin(it) }

    task.componentType.set(ComponentTypeImpl.BASE_APK)
    val result =
      task.reconcileDefaultProguardFile(
        keepRules,
        FakeGradleProvider(project.layout.projectDirectory.dir(finalDefaultFolder.absolutePath)),
        false,
      )
    Truth.assertThat(result).hasSize(3)
    val substitutedFile = result.find { it.file.name == defaultFile.name }
    Truth.assertThat(substitutedFile).isNotNull()
    assertThat(substitutedFile!!.file.parent).isEqualTo(finalDefaultFolder.toPath())
  }

  @Test
  fun testFilesWithDefaultFileNameInSourceFoldersAreNotSubstituted() {
    Truth.assertThat(task).isNotNull()
    val srcFolder = temporaryFolder.newFolder("proguard_files").toPath()
    val finalDefaultFolder = temporaryFolder.newFolder("default_proguard_files")

    val defaultFile = ProguardFiles.getDefaultProguardFile(ProguardFiles.ProguardFile.OPTIMIZE.fileName, project.layout.buildDirectory)
    val file1 = srcFolder.resolve("user1.txt").also { it.writeText("") }
    val file2 = srcFolder.resolve("user2.txt").also { it.writeText("") }
    val file3 = srcFolder.resolve(defaultFile.name).also { it.writeText("") }

    val keepRules = listOf(file1, file2, file3).map { KeepRuleFile.WithoutOrigin(it) }
    task.componentType.set(ComponentTypeImpl.BASE_APK)
    val result =
      task.reconcileDefaultProguardFile(
        keepRules,
        FakeGradleProvider(project.layout.projectDirectory.dir(finalDefaultFolder.absolutePath)),
        false,
      )
    Truth.assertThat(result).hasSize(3)
    val substitutedFile = result.find { it.file.name.equals(defaultFile.name) }
    Truth.assertThat(substitutedFile).isNotNull()
    assertThat(substitutedFile!!.file.parent).isEqualTo(srcFolder)
  }

  @Test
  fun `test files which do not exist are filtered out`() {
    Truth.assertThat(task).isNotNull()
    val srcFolder = temporaryFolder.newFolder("proguard_files").toPath()
    val finalDefaultFolder = temporaryFolder.newFolder("default_proguard_files")
    val file1 = srcFolder.resolve("user1.txt").also { it.writeText("foo") }
    val file2 = srcFolder.resolve("user2.txt") // Does not exist

    val keepRules = listOf(KeepRuleFile.WithoutOrigin(file1), KeepRuleFile.WithoutOrigin(file2))

    task.componentType.set(ComponentTypeImpl.BASE_APK)
    val result =
      task.reconcileDefaultProguardFile(
        keepRules,
        FakeGradleProvider(project.layout.projectDirectory.dir(finalDefaultFolder.absolutePath)),
        false,
      )
    Truth.assertThat(result).hasSize(1)
    assertThat(result.single().file == file1).isNotNull()
  }

  @Test
  fun `test missing file throws runtime exception`() {
    Truth.assertThat(task).isNotNull()
    val srcFolder = temporaryFolder.newFolder("proguard_files").toPath()
    val finalDefaultFolder = temporaryFolder.newFolder("default_proguard_files")

    val file1 = srcFolder.resolve("user1.txt") // Does not exist
    val keepRules = listOf(KeepRuleFile.WithoutOrigin(file1))

    task.componentType.set(ComponentTypeImpl.BASE_APK)
    assertFailsWith<RuntimeException> {
      task.reconcileDefaultProguardFile(
        keepRules,
        FakeGradleProvider(project.layout.projectDirectory.dir(finalDefaultFolder.absolutePath)),
        true,
      )
    }
  }

  @Test
  fun `test directory throws runtime exception`() {
    Truth.assertThat(task).isNotNull()

    val finalDefaultFolder = temporaryFolder.newFolder("default_proguard_files").toPath()
    val keepRules = listOf(KeepRuleFile.WithoutOrigin(finalDefaultFolder))

    task.componentType.set(ComponentTypeImpl.BASE_APK)
    assertFailsWith<RuntimeException> {
      task.reconcileDefaultProguardFile(
        keepRules,
        FakeGradleProvider(project.layout.projectDirectory.dir(finalDefaultFolder.toAbsolutePath().toString())),
        true,
      )
    }
  }
}
