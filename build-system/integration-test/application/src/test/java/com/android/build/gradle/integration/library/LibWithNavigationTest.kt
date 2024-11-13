/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.AarSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.integration.common.truth.GradleTaskSubject.assertThat
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

private const val LIBRARY_PATH = ":library"

/** Tests for library module with navigation. */
class LibWithNavigationTest {

    @get:Rule
    val rule = GradleRule.from {
        androidLibrary(LIBRARY_PATH) {

            files {
                HelloWorldAndroid.setupJava(this)
                update("src/main/AndroidManifest.xml") {
                    """
                        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                            <application android:name="library">
                                <activity android:name=".MainActivity">
                                    <nav-graph android:value="@navigation/nav1" />
                                </activity>
                             </application>
                        </manifest>""".trimIndent()
                }
            }

        }
    }

    /**
     * Test that we can build a release AAR when there are <nav-graph> tags in the library manifest.
     * Regression test for Issue 140856013.
     */
    @Test
    fun testAssembleReleaseWithNavGraphTagInManifest() {
        val build = rule.build
        val library = build.androidLibrary(LIBRARY_PATH)

        build.executor.run("clean", "$LIBRARY_PATH:assembleRelease")
        library.assertAar(AarSelector.RELEASE) {
            manifestFile().contains("<nav-graph android:value=\"@navigation/nav1\" />")
        }
    }

    /**
     * Test that ExtractDeepLinksTask is/isn't created when buildFeatures.androidResources is/isn't set.
     */
    @Test
    fun testDisablingAndroidResourcesDisablesExtractDeepLinksTask() {
        val build = rule.build
        val library = build.androidLibrary(LIBRARY_PATH)

        val taskName = "extractDeepLinksDebug"
        val fullTaskName = "$LIBRARY_PATH:$taskName"


        build.executor.run(fullTaskName).apply {
            assertThat(getTask(fullTaskName)).didWork()
        }

        library.reconfigure(buildFileOnly = true) {
            android {
                buildFeatures {
                    androidResources = false
                }
            }
        }

        build.executor.expectFailure().run(fullTaskName).exception.apply {
            // The outermost GradleConnectionException does not contain the needed info, but the
            // message of the next exception down the stack contains a complete stacktrace
            assertThat(this)
                .hasCauseThat()
                .hasMessageThat()
                .contains("Cannot locate tasks that match '$LIBRARY_PATH:$taskName'")
        }
    }
}
