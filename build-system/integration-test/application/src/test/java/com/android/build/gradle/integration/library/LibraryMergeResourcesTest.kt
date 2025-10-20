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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class LibraryMergeResourcesTest {

    @get:Rule
    val project: GradleRule = GradleRule.from {
        androidLibrary {}
    }

    /**
     * Regression test for b/355397971
     */
    @Test
    fun `merge res task not executed when includeAndroidResources disabled`() {
        val build = project.build {
            androidLibrary {
                android {
                    testOptions.unitTests.isIncludeAndroidResources = false
                }
            }
        }

        var result = build.executor.run("clean", ":lib:compileDebugSources")
        assertThat(result.didWorkTasks).contains(":lib:packageDebugResources")
        assertThat(result.didWorkTasks).doesNotContain(":lib:mergeDebugResources")

        result = build.executor.run("clean", ":lib:compileDebugUnitTestSources")
        assertThat(result.didWorkTasks).doesNotContain(":lib:mergeDebugUnitTestResources")
        assertThat(result.didWorkTasks).doesNotContain(":lib:packageDebugUnitTestForUnitTest")
    }

    /**
     * Regression test for b/355397971
     */
    @Test
    fun `merge res task executed when includeAndroidResources enabled`() {
        val build = project.build {
            androidLibrary {
                android {
                    testOptions.unitTests.isIncludeAndroidResources = true
                }
            }
        }

        var result = build.executor.run("clean", ":lib:compileDebugSources")
        assertThat(result.didWorkTasks).contains(":lib:packageDebugResources")
        assertThat(result.didWorkTasks).doesNotContain(":lib:mergeDebugResources")

        result = build.executor.run("clean", ":lib:compileDebugUnitTestSources")
        assertThat(result.didWorkTasks).contains(":lib:mergeDebugUnitTestResources")
        assertThat(result.didWorkTasks).contains(":lib:packageDebugUnitTestForUnitTest")

    }

    @Test
    fun `test trailing text in xml`() {
        val build = project.build {
            androidLibrary {
                files {
                    add(
                        "src/main/res/layout/trailing_content_layout.xml",
                        """<?xml version="1.0" encoding="utf-8"?>
            <FrameLayout>content</FrameLayout> trailing content
            """
                    )
                    add(
                        "src/main/res/layout/trailing_crlf_layout.xml",
                        """<?xml version="1.0" encoding="utf-8"?>
            <FrameLayout>content</FrameLayout>
            """.trimIndent() + "\r\n"
                    )
                    // Check valid XML layout does not trigger warning (regression test for b/453573619)
                    add(
                        "src/main/res/values/layout/valid_layout.xml",
                        """<?xml version="1.0" encoding="utf-8"?>
                <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                    android:orientation="vertical"
                    android:layout_width="match_parent"
                    android:layout_height="match_parent"/>""".trimIndent()
                    )
                }
            }
        }

        build.executor.run("clean", ":lib:parseDebugLocalResources").also {
            // trailing_content_layout.xml
            it.assertOutputContains(
                "trailing_content_layout.xml contains trailing content. Trailing is stripped during XML parsing."
            )
            it.assertOutputContains(
                """Trailing content was: ' trailing content
        '"""
            )
            // trailing_crlf_layout.xml
            it.assertOutputDoesNotContain("trailing_crlf_layout.xml contains trailing content.")
            // valid_layout.xml
            it.assertOutputDoesNotContain("valid_layout.xml contains trailing content.")
        }

        build.androidLibrary().files.add("src/main/res/values/colors.xml",
            """<?xml version="1.0" encoding="utf-8"?>
            <resources>
                <color
                    name="color_name"
                    >hex_color</color>
            </resources> trailing content
            """)

        // Error when trailing content in values resource
        build.executor.expectFailure().run("clean", ":lib:parseDebugLocalResources").also {
            it.assertFailureMessage().contains("colors.xml:6:26: Error: Content is not allowed in trailing section.")
        }
        build.androidLibrary().files.remove("src/main/res/values/colors.xml")

        // Error when empty layout
        build.androidLibrary().files.add("src/main/res/layout/empty_layout.xml", "content")
        build.executor.expectFailure().run("clean", ":lib:parseDebugLocalResources").also {
            it.assertFailureMessage().contains("Content is not allowed in prolog.")
        }
    }
}
