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

package com.android.build.gradle.integration.library

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

class LibraryMergeResourcesTest {
    @get:Rule
    var project: GradleTestProject = GradleTestProject.builder()
        .fromTestApp(HelloWorldApp.forPlugin("com.android.library"))
        .create()

    /**
     * Regression test for b/355397971
     */
    @Test
    fun `merge res task not executed when includeAndroidResources disabled`() {
        TestFileUtils.appendToFile(
            project.buildFile,
            """
            android.testOptions.unitTests.includeAndroidResources = false
            """.trimIndent()
        )

        var result = project.executor().run("clean", ":compileDebugSources")
        Truth.assertThat(result.didWorkTasks).contains(":packageDebugResources")
        Truth.assertThat(result.didWorkTasks).doesNotContain(":mergeDebugResources")

        result = project.executor().run("clean", ":compileDebugUnitTestSources")
        Truth.assertThat(result.didWorkTasks).doesNotContain(":mergeDebugUnitTestResources")
        Truth.assertThat(result.didWorkTasks).doesNotContain(":packageDebugUnitTestForUnitTest")
    }

    /**
     * Regression test for b/355397971
     */
    @Test
    fun `merge res task executed when includeAndroidResources enabled`() {
        TestFileUtils.appendToFile(
            project.buildFile,
            """
            android.testOptions.unitTests.includeAndroidResources = true
            """.trimIndent()
        )

        var result = project.executor().run("clean", ":compileDebugSources")
        Truth.assertThat(result.didWorkTasks).contains(":packageDebugResources")
        Truth.assertThat(result.didWorkTasks).doesNotContain(":mergeDebugResources")

        result = project.executor().run("clean", ":compileDebugUnitTestSources")
        Truth.assertThat(result.didWorkTasks).contains(":mergeDebugUnitTestResources")
        Truth.assertThat(result.didWorkTasks).contains(":packageDebugUnitTestForUnitTest")

    }

    @Test
    fun `test trailing text in xml`() {
        val layoutWithXmlTrailingContent =
            project.mainResDir.resolve("layout/trailing_content_layout.xml").also {
                it.writeText(
                    """<?xml version="1.0" encoding="utf-8"?>
            <FrameLayout>content</FrameLayout> trailing content
            """
                )
            }
        val execution = project.executor().run("clean", ":parseDebugLocalResources")
        execution.assertOutputDoesNotContain(
            """main.xml contains trailing content."""
        )
        execution.assertOutputContains(
            "trailing_content_layout.xml contains trailing content. Trailing is stripped during XML parsing."
        )
        execution.assertOutputContains(
            """Trailing content was: ' trailing content
        '"""
        )
        layoutWithXmlTrailingContent.delete()
        val valuesColorsWithXmlTrailingContent =
            project.mainResDir.resolve("layout/colors.xml").also {
                it.writeText(
                    """<?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color
                    name="color_name"
                    >hex_color</color>
            </resources> trailing content
            """
                )
            }
        project.executor().run("clean", ":parseDebugLocalResources")
        valuesColorsWithXmlTrailingContent.delete()

        val noValidXmlLayout = project.mainResDir.resolve("layout/empty_layout.xml").also {
            it.writeText("content")
        }
        val failure = project.executor().expectFailure().run("clean", ":parseDebugLocalResources")
        failure.assertFailureMessage().contains("Content is not allowed in prolog.")
        noValidXmlLayout.delete()
    }
}
