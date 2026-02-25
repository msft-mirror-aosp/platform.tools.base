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

    val taskCreationServices = Mockito.mock(TaskCreationServices::class.java)

    val projectOptions = ProjectOptions(project.providers)
    Mockito.`when`(taskCreationServices.projectOptions).thenReturn(projectOptions)

    Mockito.`when`(taskCreationServices.provider<Any>(any())).then {
      @Suppress("UNCHECKED_CAST") val callable = it.arguments[0] as () -> Any
      project.provider(callable)
    }

    val serviceProvider = project.gradle.sharedServices.registerIfAbsent("lintClassLoader", LintClassLoaderBuildService::class.java) {}

    val versionKeyProvider = lintTool.deriveVersionKey(taskCreationServices, serviceProvider)
    val versionKey = versionKeyProvider.get()

    val defaultLintVersion = getLintMavenArtifactVersion(null, null)
    assertThat(versionKey).startsWith("${defaultLintVersion}_")

    val hashPart = versionKey.substringAfter("${defaultLintVersion}_")
    assertThat(hashPart.length).isEqualTo(32)
  }

  @Test
  fun testDeriveVersionKeyWithOverride() {
    val jarFile = temporaryFolder.newFile("bar.jar")
    jarFile.writeText("other content")
    lintTool.classpath.from(jarFile)

    val taskCreationServices = Mockito.mock(TaskCreationServices::class.java)

    val mockProjectOptions = Mockito.mock(ProjectOptions::class.java)
    Mockito.`when`(mockProjectOptions.get(StringOption.LINT_VERSION_OVERRIDE)).thenReturn("31.0.0-alpha01")
    Mockito.`when`(taskCreationServices.projectOptions).thenReturn(mockProjectOptions)
    Mockito.`when`(taskCreationServices.provider<Any>(any())).then {
      @Suppress("UNCHECKED_CAST") val callable = it.arguments[0] as () -> Any
      project.provider(callable)
    }

    val serviceProvider = project.gradle.sharedServices.registerIfAbsent("lintClassLoader2", LintClassLoaderBuildService::class.java) {}

    val versionKeyProvider = lintTool.deriveVersionKey(taskCreationServices, serviceProvider)
    val versionKey = versionKeyProvider.get()

    // 31 (overridden version) + 23 (hardcoded in getLintMavenArtifactVersion)
    val expectedVersion = "54.0.0-alpha01"
    val keyPart = "${expectedVersion}_"
    assertThat(versionKey).startsWith(keyPart)
    // murmur128 has length of 23 symbols
    assertThat(versionKey.drop(keyPart.length).length).isEqualTo(32)
  }
}
