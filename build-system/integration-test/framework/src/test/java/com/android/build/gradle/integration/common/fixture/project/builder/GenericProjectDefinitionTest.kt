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
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.TemporaryFolder
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

class GenericProjectDefinitionTest {

    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    @get:Rule
    val expected: ExpectedException = ExpectedException.none()

    @Test
    fun testAddFile() {
        val project = GenericProjectDefinitionImpl("name")

        project.files.add("foo.txt", "some content")

        val location = temporaryFolder.newFolder().toPath()
        project.writeSubProject(
            location = location,
            buildFileOnly = false,
            allPlugins = mapOf(),
            buildWriter = { GroovyBuildWriter() },
            customPluginMap = mapOf(),
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
            buildFileOnly = false,
            allPlugins = mapOf(),
            buildWriter = { GroovyBuildWriter() },
            customPluginMap = mapOf(),
        )

        val fooFile = location.resolve("foo.txt")
        Truth.assertThat(fooFile.isRegularFile()).isFalse()
    }

    @Test
    fun testChangeFile() {
        val project = GenericProjectDefinitionImpl("name")

        project.files {
            add("foo.txt", "some content")
            update("foo.txt") {
                it?.replace("some", "more") ?: "error"
            }
        }

        val location = temporaryFolder.newFolder().toPath()
        project.writeSubProject(
            location = location,
            buildFileOnly = false,
            allPlugins = mapOf(),
            buildWriter = { GroovyBuildWriter() },
            customPluginMap = mapOf(),
        )

        val fooFile = location.resolve("foo.txt")
        Truth.assertThat(fooFile.isRegularFile()).isTrue()
        Truth.assertThat(fooFile.readText()).isEqualTo("more content")
    }

    @Test
    fun addExistingFile() {
        val project = GenericProjectDefinitionImpl("name")

        project.files.add("foo.txt", "foo")

        expected.expect(RuntimeException::class.java)
        project.files.add("foo.txt", "bar")
    }

    @Test
    fun removeMissingFile() {
        val project = GenericProjectDefinitionImpl("name")

        expected.expect(RuntimeException::class.java)
        project.files.remove("foo.txt")
    }

    @Test
    fun updateMissingFile() {
        val project = GenericProjectDefinitionImpl("name")

        project.files.update("foo.txt") {
            "new content"
        }

        val location = temporaryFolder.newFolder().toPath()
        project.writeSubProject(
            location = location,
            buildFileOnly = false,
            allPlugins = mapOf(),
            buildWriter = { GroovyBuildWriter() },
            customPluginMap = mapOf(),
        )

        val fooFile = location.resolve("foo.txt")
        Truth.assertThat(fooFile.isRegularFile()).isTrue()
        Truth.assertThat(fooFile.readText()).isEqualTo("new content")
    }
}

