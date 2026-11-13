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

package com.android.build.gradle.internal.dexing

import com.android.build.gradle.internal.fixtures.FakeProviderFactory
import com.android.build.gradle.internal.r8.R8FromMaven
import com.android.build.gradle.internal.services.R8ClassloaderBuildService
import com.android.build.gradle.internal.services.TaskCreationServices
import com.android.build.gradle.internal.services.createProjectServices
import com.android.build.gradle.internal.services.createTaskCreationServices
import com.android.build.gradle.options.ProjectOptions
import com.android.build.gradle.options.StringOption
import com.android.builder.dexing.R8Version
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unit tests for [IsolatedR8TaskInputs], [IsolatedR8TransformParameters] and their [configureIsolatedR8] helpers. */
class IsolatedR8InputsTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  abstract class FakeIsolatedR8Task : DefaultTask(), IsolatedR8TaskInputs

  private lateinit var project: Project
  private lateinit var r8Jar: File

  @Before
  fun setUp() {
    project = ProjectBuilder.builder().withProjectDir(temporaryFolder.newFolder()).build()
    r8Jar = temporaryFolder.newFile("r8.jar")
  }

  private fun createServices(r8VersionOverride: String?): TaskCreationServices {
    val options = r8VersionOverride?.let { mapOf(StringOption.R8_VERSION_OVERRIDE.propertyName to it) } ?: emptyMap()
    val projectOptions = ProjectOptions(FakeProviderFactory(FakeProviderFactory.factory, options))
    val services = createTaskCreationServices(createProjectServices(project, projectOptions = projectOptions))
    val r8FromMaven = R8FromMaven(project.files(r8Jar), R8Version.VERSION_AGP_WAS_SHIPPED_WITH)
    return object : TaskCreationServices by services {
      override val r8FromMaven: R8FromMaven = r8FromMaven
    }
  }

  @Test
  fun taskInputsWithoutOverride_useR8FromMaven() {
    val task = project.tasks.register("fakeTask", FakeIsolatedR8Task::class.java).get()

    task.configureIsolatedR8(project, createServices(r8VersionOverride = null))

    assertThat(task.r8Classpath.files).containsExactly(r8Jar)
    assertThat(task.r8ClassloaderBuildService.get()).isInstanceOf(R8ClassloaderBuildService::class.java)
    assertThrows(IllegalStateException::class.java) { task.r8Classpath.from(temporaryFolder.newFile("other.jar")) }
    assertThrows(IllegalStateException::class.java) { task.r8ClassloaderBuildService.set(null as R8ClassloaderBuildService?) }
  }

  @Test
  fun taskInputsWithOverride_useR8FromMaven() {
    val task = project.tasks.register("fakeTask", FakeIsolatedR8Task::class.java).get()

    task.configureIsolatedR8(project, createServices(r8VersionOverride = R8Version.VERSION_AGP_WAS_SHIPPED_WITH))

    assertThat(task.r8Classpath.files).containsExactly(r8Jar)
    assertThat(task.r8ClassloaderBuildService.get()).isInstanceOf(R8ClassloaderBuildService::class.java)
    assertThrows(IllegalStateException::class.java) { task.r8Classpath.from(temporaryFolder.newFile("other.jar")) }
  }

  @Test
  fun transformParameters_setFromGivenClasspathAndService() {
    val service = R8ClassloaderBuildService.RegistrationAction(project).execute()

    val withoutClasspath = project.objects.newInstance(IsolatedR8TransformParameters::class.java)
    withoutClasspath.configureIsolatedR8(r8Classpath = project.files(), r8ClassloaderBuildService = service)
    assertThat(withoutClasspath.r8Classpath.files).isEmpty()
    assertThat(withoutClasspath.r8ClassloaderBuildService.get()).isSameInstanceAs(service.get())

    val fromMaven = project.objects.newInstance(IsolatedR8TransformParameters::class.java)
    fromMaven.configureIsolatedR8(r8Classpath = project.files(r8Jar), r8ClassloaderBuildService = service)
    assertThat(fromMaven.r8Classpath.files).containsExactly(r8Jar)
    assertThat(fromMaven.r8ClassloaderBuildService.get()).isSameInstanceAs(service.get())
  }
}
