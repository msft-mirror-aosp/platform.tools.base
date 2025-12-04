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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test
import java.io.File

class RegisterExternalAptJavaOutputTest {

    @get:Rule
    val rule =
        GradleRule.configure()
            .disableBrokenNewDslOptOutChecks()
            .from {
                androidApplication {
                    pluginCallbacks += MyAppLegacyCallBack::class.java
                }
                gradleProperties {
                    add(BooleanOption.USE_NEW_DSL, false)
                }
            }

    /**
     * Regression test for http://b/135780031. Test correctness if we configure Java compile task
     * before invoking Variant API.
     */
    @Test
    fun testAddingGenSourcesAfterJavaCompileConfigured() {
        rule.build.executor.run("assembleDebug")
        rule.build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().subPackage("test").contains("Data")
        }
    }

    class MyAppLegacyCallBack: LegacyApplicationCallback {
        override fun handleExtension(
            project: Project,
            extension: BaseAppModuleExtension
        ) {
            extension.applicationVariants.all { variant ->
                val genSrcDir = File(project.projectDir, "externally_generated")
                val testSrc = File(genSrcDir, "test/Data.java")
                testSrc.parentFile.mkdirs()
                testSrc.writeText("package test;\n public class Data {}")

                variant.getJavaCompileProvider().get()
                variant.registerExternalAptJavaOutput(project.fileTree(genSrcDir))
            }
        }
    }
}
