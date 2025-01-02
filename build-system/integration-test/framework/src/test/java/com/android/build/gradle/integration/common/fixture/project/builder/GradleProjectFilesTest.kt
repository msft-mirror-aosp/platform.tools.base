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

import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.NoSuchFileException
import java.nio.file.Path

@RunWith(Parameterized::class)
class GradleProjectFilesTest(private val checker: ImplementationChecker) {

    /**
     * Checker interface to abstract some test action to support the different implementations
     * of [GradleProjectFiles]
     */
    interface ImplementationChecker {
        fun getInstance(location: Path): GradleProjectFiles
        fun checkEmpty(instance: GradleProjectFiles)
        fun checkContent(instance: GradleProjectFiles, nameToContent: List<Pair<String, String>>)
        fun checkContent(instance: GradleProjectFiles, vararg nameToContent: Pair<String, String>) {
            checkContent(instance, nameToContent.toList())
        }
    }

    /**
     * checker for [DelayedGradleProjectFiles]
     */
    private class DelayedChecker: ImplementationChecker {
        override fun getInstance(location: Path): GradleProjectFiles = DelayedGradleProjectFiles()

        override fun checkEmpty(instance: GradleProjectFiles) {
            instance as? DelayedGradleProjectFiles ?: throw RuntimeException("Wrong instance type")
            Truth.assertThat(instance.sourceFiles).isEmpty()
        }

        override fun checkContent(
            instance: GradleProjectFiles,
            nameToContent: List<Pair<String, String>>
        ) {
            instance as? DelayedGradleProjectFiles ?: throw RuntimeException("Wrong instance type")
            Truth.assertThat(
                instance.sourceFiles.map { it.key to it.value }
            ).containsExactlyElementsIn(nameToContent)
        }
    }

    /**
     * checker for [DirectGradleProjectFiles]
     */
    private class DirectChecker: ImplementationChecker {
        override fun getInstance(location: Path): GradleProjectFiles = DirectGradleProjectFiles(location)

        override fun checkEmpty(instance: GradleProjectFiles) {
            instance as? DirectGradleProjectFiles ?: throw RuntimeException("Wrong instance type")
            val file = instance.location.toFile()
            Truth.assertWithMessage("${instance.location} is empty").that(file.list()!!).isEmpty()
        }

        override fun checkContent(
            instance: GradleProjectFiles,
            nameToContent: List<Pair<String, String>>
        ) {
            instance as? DirectGradleProjectFiles ?: throw RuntimeException("Wrong instance type")
            val file = instance.location.toFile()
            val nameToContentActual = file.listFiles()!!.map { it ->
                it.name to it.readText()
            }
            Truth.assertWithMessage("content of ${instance.location}")
                .that(nameToContentActual)
                .containsExactlyElementsIn(nameToContent)
        }
    }

    companion object {
        @Parameterized.Parameters(name = "impl = {0}")
        @JvmStatic
        fun data(): List<ImplementationChecker> {
            return listOf(
                DelayedChecker(),
                DirectChecker()
            )
        }
    }

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @get:Rule
    val exceptionRule: ExpectedException = ExpectedException.none()

    @Test
    fun add() {
        val files = getInstance()
        files.add("foo", "bar")
        files.add("foo2", "bar2")
        checker.checkContent(files, "foo" to "bar", "foo2" to "bar2")
    }

    @Test
    fun remove() {
        val files = getInstance()
        files.add("foo", "bar")
        files.remove("foo")
        checker.checkEmpty(files)
    }

    @Test
    fun append() {
        val files = getInstance()
        files.add("foo", "bar")
        files.update("foo").append("bar")
        checker.checkContent(files, "foo" to "barbar")
    }

    @Test
    fun replaceWith() {
        val files = getInstance()
        files.add("foo", "bar")
        files.update("foo").replaceWith("baz")
        checker.checkContent(files, "foo" to "baz")
    }

    @Test
    fun searchAndReplace() {
        val files = getInstance()
        files.add("foo", "some text with some content")
        files.update("foo").searchAndReplace("some", "my")
        checker.checkContent(files, "foo" to "my text with my content")
    }

    @Test
    fun searchAndReplaceAndAppend() {
        val files = getInstance()
        files.add("foo", "some text with some content")
        files.update("foo")
            .searchAndReplace("some", "my")
            .append(" with more content again")
        checker.checkContent(files, "foo" to "my text with my content with more content again")
    }

    @Test
    fun transform() {
        val files = getInstance()
        files.add("foo", "some text with some content")
        files.update("foo").transform {
            "/*$it*/"
        }
        checker.checkContent(files, "foo" to "/*some text with some content*/")
    }

    @Test
    fun updateAction() {
        val files = getInstance()
        files.add("foo", "some text with some content")
        files.update("foo") {
            searchAndReplace("some", "my")
        }
        checker.checkContent(files, "foo" to "my text with my content")
    }

    @Test
    fun updateActionWithLogic() {
        val files = getInstance()
        files.update("foo") {
            if (!exists) {
                replaceWith("some text with some content")
            } else {
                throw RuntimeException("should not have content")
            }
        }
        checker.checkContent(files, "foo" to "some text with some content")

        files.update("foo") {
            if (!exists) {
                throw RuntimeException("should have content")
            } else {
                searchAndReplace("some", "my")
            }
        }

        checker.checkContent(files, "foo" to "my text with my content")
    }

    @Test
    fun removeMissing() {
        val files = getInstance()
        exceptionRule.expect(NoSuchFileException::class.java)
        files.remove("foo")
    }

    @Test
    fun searchAndReplaceMissing() {
        val files = getInstance()
        exceptionRule.expect(RuntimeException::class.java)
        files.update("foo").searchAndReplace("a","b")
    }

    @Test
    fun replaceMissing() {
        val files = getInstance()
        files.update("foo").replaceWith("bar")
        checker.checkContent(files, "foo" to "bar")
    }

    @Test
    fun appendMissing() {
        val files = getInstance()
        files.update("foo").append("bar")
        checker.checkContent(files, "foo" to "bar")
    }

    private fun getInstance() = checker.getInstance(temporaryFolder.newFolder().toPath())
}
