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

package com.android.build.gradle.integration.common.fixture.project.builder

import com.android.build.gradle.integration.common.fixture.project.GenericProjectDefinitionImpl
import com.android.testutils.TestInputsGenerator
import com.android.testutils.generateAarWithContent
import com.google.common.collect.ImmutableList
import com.google.common.truth.Truth
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.TemporaryFolder

class GenericProjectDefinitionTest {

  @get:Rule val temporaryFolder: TemporaryFolder = TemporaryFolder()

  @get:Rule val expected: ExpectedException = ExpectedException.none()

  @Test
  fun testAddFile() {
    val project = GenericProjectDefinitionImpl("name")

    project.files.add("foo.txt", "some content")

    val location = temporaryFolder.newFolder().toPath()
    project.writeSubProject(
      location = location,
      allPlugins = mapOf(),
      customPluginMap = mapOf(),
      useOldPluginStyle = false,
      projectRepositories = listOf(),
      buildWriter = GroovyBuildWriter(),
    )

    val fooFile = location.resolve("foo.txt")
    Truth.assertThat(fooFile.isRegularFile()).isTrue()
    Truth.assertThat(fooFile.readText()).isEqualTo("some content")
  }

  @Test
  fun testRemoveFile() {
    val project = GenericProjectDefinitionImpl("name")

    project.files {
      add("foo.txt", "some content")
      remove("foo.txt")
    }

    val location = temporaryFolder.newFolder().toPath()
    project.writeSubProject(
      location = location,
      allPlugins = mapOf(),
      customPluginMap = mapOf(),
      useOldPluginStyle = false,
      projectRepositories = listOf(),
      buildWriter = GroovyBuildWriter(),
    )

    val fooFile = location.resolve("foo.txt")
    Truth.assertThat(fooFile.isRegularFile()).isFalse()
  }

  @Test
  fun testChangeFile() {
    val project = GenericProjectDefinitionImpl("name")

    project.files {
      add("foo.txt", "some content")
      update("foo.txt").searchAndReplace("some", "more")
    }

    val location = temporaryFolder.newFolder().toPath()
    project.writeSubProject(
      location = location,
      allPlugins = mapOf(),
      customPluginMap = mapOf(),
      useOldPluginStyle = false,
      projectRepositories = listOf(),
      buildWriter = GroovyBuildWriter(),
    )

    val fooFile = location.resolve("foo.txt")
    Truth.assertThat(fooFile.isRegularFile()).isTrue()
    Truth.assertThat(fooFile.readText()).isEqualTo("more content")
  }

  @Test
  fun wrap() {
    val project = GenericProjectDefinitionImpl("name")
    project.wrap(
      generateAarWithContent(
        packageName = "com.example.aar",
        mainJar = TestInputsGenerator.jarWithEmptyClasses(ImmutableList.of("com/example/aar/AarClass")),
        resources = mapOf("values/strings.xml" to """<resources><string name="aar_string">Aar String</string></resources>""".toByteArray()),
      ),
      "lib.aar",
    )

    val location = temporaryFolder.newFolder().toPath()
    project.writeSubProject(
      location = location,
      allPlugins = mapOf(),
      customPluginMap = mapOf(),
      useOldPluginStyle = false,
      projectRepositories = listOf(),
      buildWriter = GroovyBuildWriter(),
    )

    val buildFile = location.resolve("build.gradle")
    Truth.assertThat(buildFile.isRegularFile()).isTrue()
    Truth.assertThat(buildFile.readText())
      .isEqualTo(
        // language=groovy
        """
        plugins {
        }

        configurations.create('default')
        artifacts.add('default', file('lib.aar'))

        """
          .trimIndent()
      )
  }
}
