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
import com.android.build.gradle.internal.fixtures.FakeArtifactCollection
import com.android.build.gradle.internal.fixtures.FakeBuildIdentifier
import com.android.build.gradle.internal.fixtures.FakeGradleProvider
import com.android.build.gradle.internal.fixtures.FakeProjectComponentIdentifier
import com.android.build.gradle.internal.fixtures.FakeResolvedArtifactResult
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
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.file.ProjectLayout
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

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

  @Test
  fun `test keep rule origins`() {
    Truth.assertThat(task).isNotNull()

    val (aaptFile, featureFile, generatedFile, projectFile) =
      listOf("AAPT", "feature", "generated", "project").map { temporaryFolder.newFile("${it}_rules.pro") }

    task.aaptProguardFiles.from(aaptFile)
    task.featureProguardFiles.from(featureFile)
    task.generatedProguardFile.from(generatedFile)
    task.keepRulesFiles.from(projectFile)

    task.buildId.set("myBuildId")
    task.localProjectPath.set(":myProject")
    task.ignoreFromInKeepRules.set(emptySet())
    task.ignoreFromAllExternalDependenciesInKeepRules.set(false)

    val mockLibraryKeepRules = mock<ArtifactCollection>()
    whenever(mockLibraryKeepRules.artifacts).thenReturn(emptySet())

    val keepRules = task.obtainKeepRules(mockLibraryKeepRules)

    Truth.assertThat(keepRules).hasSize(4)

    val aaptRule = keepRules.find { it.file == aaptFile.toPath() }
    Truth.assertThat(aaptRule).isInstanceOf(KeepRuleFile.GeneratedOrigin::class.java)
    Truth.assertThat((aaptRule as KeepRuleFile.GeneratedOrigin).origin).isEqualTo(ProguardConfigurableTask.AAPT2_RULES_ORIGIN)

    val featureRule = keepRules.find { it.file == featureFile.toPath() }
    Truth.assertThat(featureRule).isInstanceOf(KeepRuleFile.GeneratedOrigin::class.java)
    Truth.assertThat((featureRule as KeepRuleFile.GeneratedOrigin).origin).isEqualTo(ProguardConfigurableTask.FEATURE_RULES_ORIGIN)

    val generatedRule = keepRules.find { it.file == generatedFile.toPath() }
    Truth.assertThat(generatedRule).isInstanceOf(KeepRuleFile.GeneratedOrigin::class.java)
    Truth.assertThat((generatedRule as KeepRuleFile.GeneratedOrigin).origin).isEqualTo(ProguardConfigurableTask.GENERATED_RULES_ORIGIN)

    val projectRule = keepRules.find { it.file == projectFile.toPath() }
    Truth.assertThat(projectRule).isInstanceOf(KeepRuleFile.LocalProjectOrigin::class.java)
    val projectOrigin = projectRule as KeepRuleFile.LocalProjectOrigin
    Truth.assertThat(projectOrigin.buildId).isEqualTo("myBuildId")
    Truth.assertThat(projectOrigin.projectPath).isEqualTo(":myProject")
  }

  @Test
  fun `test keep rule origin from project dependency`() {
    Truth.assertThat(task).isNotNull()

    val projectRuleFile = temporaryFolder.newFile("lib_rules.pro")
    val buildId = "otherBuild"
    val projectPath = ":lib"

    val artifactCollection =
      FakeArtifactCollection(
        mutableSetOf(
          FakeResolvedArtifactResult(
            file = projectRuleFile,
            identifier = FakeProjectComponentIdentifier(projectPath = projectPath, buildIdentifier = FakeBuildIdentifier(buildId)),
          )
        )
      )

    task.configurationFiles.from(projectRuleFile)
    task.buildId.set("mainBuild")
    task.localProjectPath.set(":app")
    task.ignoreFromInKeepRules.set(emptySet())
    task.ignoreFromAllExternalDependenciesInKeepRules.set(false)

    val keepRules = task.obtainKeepRules(artifactCollection)

    Truth.assertThat(keepRules).hasSize(1)
    val projectRule = keepRules.single()
    Truth.assertThat(projectRule).isInstanceOf(KeepRuleFile.LocalProjectOrigin::class.java)
    val origin = projectRule as KeepRuleFile.LocalProjectOrigin
    Truth.assertThat(origin.buildId).isEqualTo(buildId)
    Truth.assertThat(origin.projectPath).isEqualTo(projectPath)
    Truth.assertThat(origin.file.toFile()).isEqualTo(projectRuleFile)
  }

  @Test
  fun `test obtainKeepRules filters out non-existent files`() {
    Truth.assertThat(task).isNotNull()

    val nonExistentFile = project.layout.projectDirectory.file("non-existent.pro").asFile

    task.aaptProguardFiles.from(nonExistentFile)
    task.featureProguardFiles.from(nonExistentFile)
    task.generatedProguardFile.from(nonExistentFile)
    task.keepRulesFiles.from(nonExistentFile)

    task.buildId.set("myBuildId")
    task.localProjectPath.set(":myProject")
    task.ignoreFromInKeepRules.set(emptySet())
    task.ignoreFromAllExternalDependenciesInKeepRules.set(false)

    val mockLibraryKeepRules = mock<ArtifactCollection>()
    whenever(mockLibraryKeepRules.artifacts).thenReturn(emptySet())

    val keepRules = task.obtainKeepRules(mockLibraryKeepRules)

    Truth.assertThat(keepRules).isEmpty()
  }
}
