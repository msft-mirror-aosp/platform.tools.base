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

package com.android.build.gradle.integration.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

import com.android.build.api.artifact.SingleArtifact

/** Modern GradleRule-based test for the new Variants API, assuring no coverage drop. Semantic copy of [VariantsApiTest]. */
@RunWith(Parameterized::class)
class NewVariantsApiTest(val plugin: String) {

    companion object {
        @Parameterized.Parameters(name = "plugin_{0}")
        @JvmStatic
        fun data(): Collection<Array<Any>> {
            return listOf(
                arrayOf("com.android.application"),
                arrayOf("com.android.library")
            )
        }
    }

    class AppCallback : ApplicationComponentCallback {
        override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
            androidComponents.beforeVariants { variantBuilder ->
                variantBuilder.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
            }
            androidComponents.onVariants { variant ->
                checkNotNull(variant.name)
                checkNotNull(variant.flavorName)
                checkNotNull(variant.buildType)

                // Assert new API replacements for assemble/processManifest/processResources
                checkNotNull(variant.artifacts.get(SingleArtifact.APK))
                checkNotNull(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                checkNotNull(variant.sources.res?.all)

                if (variant.buildType == "debug") {
                    val androidTest = checkNotNull(variant.androidTest)
                    checkNotNull(androidTest.name)
                    checkNotNull(androidTest.artifacts.get(SingleArtifact.APK))
                    checkNotNull(androidTest.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    checkNotNull(androidTest.sources.res?.all)
                } else {
                    check(variant.androidTest == null)
                }

                check(variant.hostTests.size == 1) { "hostTests size is ${variant.hostTests.size}! keys are: ${variant.hostTests.keys}" }
                variant.hostTests.values.forEach { hostTest ->
                    checkNotNull(hostTest.name)
                    checkNotNull(hostTest.sources.java?.all)
                }
            }
        }
    }

    class LibCallback : LibraryComponentCallback {
        override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
            androidComponents.beforeVariants { variantBuilder ->
                variantBuilder.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enable = true
            }
            androidComponents.onVariants { variant ->
                checkNotNull(variant.name)
                checkNotNull(variant.flavorName)
                checkNotNull(variant.buildType)

                // Assert new API replacements for assemble/processManifest/processResources
                checkNotNull(variant.artifacts.get(SingleArtifact.AAR))
                checkNotNull(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                checkNotNull(variant.sources.res?.all)

                if (variant.buildType == "debug") {
                    val androidTest = checkNotNull(variant.androidTest)
                    checkNotNull(androidTest.name)
                    checkNotNull(androidTest.artifacts.get(SingleArtifact.APK))
                    checkNotNull(androidTest.artifacts.get(SingleArtifact.MERGED_MANIFEST))
                    checkNotNull(androidTest.sources.res?.all)
                } else {
                    check(variant.androidTest == null)
                }

                check(variant.hostTests.size == 1) { "hostTests size is ${variant.hostTests.size}! keys are: ${variant.hostTests.keys}" }
                variant.hostTests.values.forEach { hostTest ->
                    checkNotNull(hostTest.name)
                    checkNotNull(hostTest.sources.java?.all)
                }
            }
        }
    }

    private fun createGradleRule() =
        GradleRule.configure().from {
            if (plugin == "com.android.application") {
                androidApplication {
                    pluginCallbacks += AppCallback::class.java
                }
            } else {
                androidLibrary {
                    pluginCallbacks += LibCallback::class.java
                }
            }
        }

    @get:Rule
    val rule = createGradleRule()

    @Test
    fun buildScriptRuns() {
        val build = rule.build {}
        build.executor.run("clean")
    }
}
