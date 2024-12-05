/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.build.gradle.integration.dependencies

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThatApk
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.TestInputsGenerator
import com.android.tools.profgen.Apk
import org.junit.Rule
import org.junit.Test

class AndroidTestClasspathTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withMavenRepository {
            jar("com.test:lib:1.0").setEmptyClasses("com/test/MyClass")
        }.from {
        androidApplication(":app") {
            android {
                namespace = "com.test.app"
            }

            dependencies {
                implementation("com.test:lib:1.0")
                implementation(project(":lib"))
                androidTestImplementation("com.test:lib:1.0")
            }

            files {
                HelloWorldAndroid.setupJava(this)
                add("src/androidTest/java/test/DataTest.java",
                    // language=java
                    """
                        package test;
                        public class DataTest extends Data {}
                    """.trimIndent())
            }

        }
        androidLibrary("lib") {
            android {
                namespace = "com.test.lib"
            }

            group = "com.test"
            version = "99.0"

            files {
                add("src/main/java/test/Data.java",
                    // language=java
                    """
                        package test;
                        public class Data {}
                    """.trimIndent())
            }
        }
    }

    @Test
    fun testAndroidTestClasspathContainsProjectDep() {
        val build = rule.build

        val failure = build.executor.expectFailure().run(":app:assembleDebugAndroidTest")

        failure.stderr.use {
            ScannerSubject.assertThat(it).contains(
                "Unable to align dependencies in configurations 'debugRuntimeClasspath' and 'debugAndroidTestRuntimeClasspath', as both require 'project :lib'.\n"
            )
        }

        val app = build.androidApplication(":app")
        app.reconfigure(buildFileOnly = true) {
            dependencies {
                androidTestImplementation(project(":lib"))
            }
        }

        build.executor.run(":app:assembleDebug", ":app:assembleDebugAndroidTest")

        app.assertApk(ApkSelector.DEBUG) {
            containsClass("Ltest/Data;")
        }

        app.assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
            containsClass("Ltest/DataTest;")
            doesNotContainClass("Ltest/Data;")
            doesNotContainClass("Lcom/test/MyClass;")
        }
    }
}
