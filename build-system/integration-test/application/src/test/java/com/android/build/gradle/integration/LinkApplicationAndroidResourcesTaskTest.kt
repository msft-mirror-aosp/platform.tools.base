/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.api.artifact.SingleArtifact.RUNTIME_SYMBOL_LIST
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test

class LinkApplicationAndroidResourcesTaskTest {
    @get:Rule
    val rule = GradleRule.from {
        androidApplication(":app") {
            android {
                namespace = "com.example.app"
                defaultConfig {
                    applicationId = "com.example.app"
                    minSdk = 24
                }
                dependencies {
                    implementation("androidx.navigation:navigation-fragment:2.5.2")
                    implementation(project(":lib"))
                }
            }
        }.files {
            add("src/main/res/layout/content_main.xml",
                """
                    <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                            xmlns:app="http://schemas.android.com/apk/res-auto"
                            android:layout_width="match_parent" android:layout_height="match_parent">
                            <androidx.fragment.app.FragmentContainerView
                                 android:id="@+id/nav_host_fragment_content_main"
                                 android:name="androidx.navigation.fragment.NavHostFragment"
                                 app:navGraph="@navigation/nav_graph" />
                     </FrameLayout>
                """.trimIndent())
            add("src/main/res/navigation/nav_graph.xml",
                """
                    <navigation xmlns:android="http://schemas.android.com/apk/res/android"
                     android:id="@+id/nav_graph">
                    </navigation>
                """.trimIndent())

        }

        androidLibrary(":lib") {
            android {
                namespace = "com.example.lib"
                defaultConfig {
                    minSdk = 24
                }
                dependencies {
                    implementation("androidx.navigation:navigation-fragment:2.5.2")
                }
            }
        }.files {
            add("src/main/res/layout/content_lib.xml",
                """
                    <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                            xmlns:app="http://schemas.android.com/apk/res-auto"
                            android:layout_width="match_parent" android:layout_height="match_parent">
                            <androidx.fragment.app.FragmentContainerView
                                 android:id="@+id/nav_host_fragment_content_main"
                                 android:name="androidx.navigation.fragment.NavHostFragment"
                                 app:navGraph="@navigation/lib_nav_graph" />
                     </FrameLayout>
                """.trimIndent())
            add("src/main/res/navigation/lib_nav_graph.xml",
                """
                    <navigation xmlns:android="http://schemas.android.com/apk/res/android"
                     android:id="@+id/lib_nav_graph">
                    </navigation>
                """.trimIndent())
        }
    }

    @Test
    fun testLinkNavigationXml() {
        val build = rule.build
        val app = build.androidApplication()
        build.executor.run("clean", ":app:processDebugResources")
        val rTxt = app
            .resolve(RUNTIME_SYMBOL_LIST)
            .resolve("debug/processDebugResources/R.txt")

        assertThat(rTxt).exists()
        assertThat(rTxt).contains("int id nav_graph")
        assertThat(rTxt).contains("int id lib_nav_graph")

        // Regression test for b/443587266
        build.executor.run("verifyReleaseResources")
    }

}
