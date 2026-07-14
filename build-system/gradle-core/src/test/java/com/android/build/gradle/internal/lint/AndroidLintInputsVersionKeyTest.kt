/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.internal.lint

import com.android.build.gradle.internal.services.LintClassLoaderBuildService
import com.android.build.gradle.internal.services.TaskCreationServices
import com.android.build.gradle.options.ProjectOptions
import com.android.build.gradle.options.StringOption
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import org.mockito.kotlin.any

class AndroidLintInputsVersionKeyTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var project: Project
  private lateinit var lintTool: LintTool

  @Before
  fun setup() {
    project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
    lintTool = project.objects.newInstance(LintTool::class.java)
  }

  @Test
  fun testDeriveVersionKey() {
    val jarFile = temporaryFolder.newFile("foo.jar")
    jarFile.writeText("some content")
    lintTool.classpath.from(jarFile)

    val projectOptions = ProjectOptions(project.providers)
    val taskCreationServices = mockTaskCreationServices(projectOptions)
    val serviceProvider = registerLintClassLoaderService()

    val versionKeyProvider = lintTool.deriveVersionKey(taskCreationServices, serviceProvider)
    val versionKey = versionKeyProvider.get()

    val defaultLintVersion = getLintMavenArtifactVersion(null, null)
    verifyVersionKey(versionKey, "${defaultLintVersion}_")

    // check that same with the same file name - hash will be the same
    val jarFile2 = temporaryFolder.newFolder("abc").resolve("foo.jar")
    jarFile2.writeText("some content")
    lintTool.classpath.setFrom(lintTool.classpath.minus(jarFile))
    lintTool.classpath.from(jarFile2)

    val versionKeyProvider2 = lintTool.deriveVersionKey(taskCreationServices, serviceProvider)
    val versionKey2 = versionKeyProvider2.get()
    assertThat(versionKey2).isEqualTo(versionKey)
  }

  @Test
  fun testDeriveVersionKeyWithOverride() {
    createJarAndAddToClasspath("bar.jar", "other content")

    val mockProjectOptions = Mockito.mock(ProjectOptions::class.java)
    Mockito.`when`(mockProjectOptions[StringOption.LINT_VERSION_OVERRIDE]).thenReturn("31.0.0-alpha01")
    val taskCreationServices = mockTaskCreationServices(mockProjectOptions)
    val serviceProvider = registerLintClassLoaderService()

    val versionKeyProvider = lintTool.deriveVersionKey(taskCreationServices, serviceProvider)
    val versionKey = versionKeyProvider.get()

    // 31 (overridden version) + 23 (hardcoded in getLintMavenArtifactVersion)
    val expectedVersion = "54.0.0-alpha01"
    verifyVersionKey(versionKey, "${expectedVersion}_")
  }

  @Test
  fun testDeriveVersionKeyWithDevOverride() {
    val jarFile = temporaryFolder.newFile("bar.jar")
    jarFile.writeText("other content")
    lintTool.classpath.from(jarFile)

    val mockProjectOptions = Mockito.mock(ProjectOptions::class.java)
    // dev version means we create hash based on jar content
    Mockito.`when`(mockProjectOptions[StringOption.LINT_VERSION_OVERRIDE]).thenReturn("31.0.0-dev")
    val taskCreationServices = mockTaskCreationServices(mockProjectOptions)
    val serviceProvider = registerLintClassLoaderService()

    val versionKeyProvider = lintTool.deriveVersionKey(taskCreationServices, serviceProvider)
    val versionKey = versionKeyProvider.get()

    // 31 (overridden version) + 23 (hardcoded in getLintMavenArtifactVersion)
    val expectedVersion = "54.0.0-dev"
    val expectedPrefix = "${expectedVersion}_"

    assertThat(versionKey).startsWith(expectedPrefix)
    val hashPart = versionKey.substringAfter(expectedPrefix)
    assertThat(hashPart.length).isEqualTo(32)

    // Now update content and then check that hash is updated
    jarFile.writeText("some other content")
    serviceProvider.get().clearCache()
    val versionKeyProvider2 = lintTool.deriveVersionKey(taskCreationServices, serviceProvider)
    val versionKey2 = versionKeyProvider2.get()
    assertThat(versionKey2).startsWith(expectedPrefix)
    val newHashPart = versionKey2.substringAfter(expectedPrefix)
    assertThat(newHashPart).isNotEqualTo(hashPart)
  }

  private fun createJarAndAddToClasspath(fileName: String, content: String) {
    val jarFile = temporaryFolder.newFile(fileName)
    jarFile.writeText(content)
    lintTool.classpath.from(jarFile)
  }

  private fun mockTaskCreationServices(projectOptions: ProjectOptions): TaskCreationServices {
    val taskCreationServices = Mockito.mock(TaskCreationServices::class.java)
    Mockito.`when`(taskCreationServices.projectOptions).thenReturn(projectOptions)
    Mockito.`when`(taskCreationServices.provider<Any>(any())).then {
      @Suppress("UNCHECKED_CAST") val callable = it.arguments[0] as () -> Any
      project.provider(callable)
    }
    return taskCreationServices
  }

  private fun registerLintClassLoaderService() =
    project.gradle.sharedServices.registerIfAbsent("lintClassLoader", LintClassLoaderBuildService::class.java) {}

  private fun verifyVersionKey(versionKey: String, expectedPrefix: String) {
    assertThat(versionKey).startsWith(expectedPrefix)
    val hashPart = versionKey.substringAfter(expectedPrefix)
    assertThat(hashPart.length).isEqualTo(32)
  }
}
