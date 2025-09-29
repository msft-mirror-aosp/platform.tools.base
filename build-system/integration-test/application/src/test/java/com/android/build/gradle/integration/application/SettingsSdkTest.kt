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

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.AndroidKotlinMultiplatformLibraryComponentCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class SettingsSdkTest {

    @get:Rule
    val rule = GradleRule.from {
        settings {
            applyPlugin(PluginType.ANDROID_SETTINGS)
        }
        // do not create minimum, otherwise the sdk version will be "overridden"
        androidLibrary(createMinimumProject = false) {
            android.namespace = "com.example.lib"
        }
        androidApplication(createMinimumProject = false) {
            android.namespace = "com.example.app"
            files.setupMinimumManifest()
        }
        androidKotlinMultiplatformLibrary(":library", createMinimumProject = false) {
            android {
                namespace = "com.mylibrary.foo"
            }
        }
    }

    @Test
    fun checkNewSdkDsl() {
        val build = rule.build {
            settings {
                android {
                    compileSdk {
                        version = release(COMPILE_SDK_VERSION) {
                            minorApiLevel = COMPILE_SDK_MINOR_VERSION
                        }
                    }
                    targetSdk {
                        version = release(TARGET_SDK_VERSION)
                    }
                    minSdk {
                        version = release(MIN_SDK_VERSION)
                    }
                }
            }
            androidLibrary {
                pluginCallbacks += LibSharedCheck::class.java
                pluginCallbacks += LibMinorVersionCheck::class.java
            }
            androidKotlinMultiplatformLibrary(":library") {
                pluginCallbacks += KmpLibVersionCheck::class.java
            }
        }
        build.executor
            .withFailOnWarning(false) // b/455891987
            .run(":help")
        checkDslSetUpInModel(build, "$COMPILE_SDK_VERSION.$COMPILE_SDK_MINOR_VERSION")
    }

    @Test
    fun checkOldSdkDsl() {
        val build = rule.build {
            settings {
                android {
                    this.compileSdk = COMPILE_SDK_VERSION
                    this.minSdk = MIN_SDK_VERSION
                    this.targetSdk = TARGET_SDK_VERSION
                }
            }
            androidLibrary {
                pluginCallbacks += LibSharedCheck::class.java
            }
        }
        checkDslSetUpInModel(build, COMPILE_SDK_VERSION.toString())
    }

    fun checkDslSetUpInModel(build: GradleBuild, compileSdkVersion: String) {
        // First check app
        val modelInfo = build.modelBuilder
            // sdk with minor api level might not exist in our test set up
            .ignoreSyncIssues()
            .withFailOnWarning(false) // b/455891987
            .fetchModels()
            .container
            .getProject(DEFAULT_APP_PATH)

        val androidDsl = modelInfo.androidDsl ?: error("failed to fetch android DSL model")
        assertThat(androidDsl.compileTarget)
            .named("androidDsl.compileTarget")
            .isEqualTo("android-$compileSdkVersion")
        assertThat(androidDsl.defaultConfig?.targetSdkVersion?.apiLevel)
            .named("androidDsl.defaultConfig.targetSdkVersion.apiLevel")
            .isEqualTo(TARGET_SDK_VERSION)
        assertThat(androidDsl.lintOptions?.targetSdk?.apiLevel)
            .named("androidDsl.lintOptions.targetSdk.apiLevel")
            .isEqualTo(null)
        val androidProject = modelInfo.androidProject ?: error("Failed to fetch android project")
        assertThat(androidProject.variants).isNotEmpty()
        for (variant in androidProject.variants) {
            assertThat(variant.mainArtifact.minSdkVersion.apiLevel)
                .named("variant %s mainArtifact.minSdkVersion.apiLevel", variant.name)
                .isEqualTo(MIN_SDK_VERSION)
        }

        // Then check library
        val libModelInfo = build.modelBuilder
            .ignoreSyncIssues()
            .withFailOnWarning(false) // b/455891987
            .fetchModels()
            .container
            .getProject(DEFAULT_LIB_PATH)
        val libAndroidDsl = libModelInfo.androidDsl ?: error("failed to fetch android DSL model")
        assertThat(libAndroidDsl.compileTarget)
            .named("libAndroidDsl.compileTarget")
            .isEqualTo("android-$compileSdkVersion")
        assertThat(libAndroidDsl.defaultConfig.targetSdkVersion)
            .named("libAndroidDsl.defaultConfig.targetSdkVersion")
            .isEqualTo(null)
        assertThat(libAndroidDsl.lintOptions?.targetSdk?.apiLevel)
            .named("libAndroidDsl.lintOptions.targetSdk.apiLevel")
            .isEqualTo(TARGET_SDK_VERSION)
        val libAndroidProject = modelInfo.androidProject ?: error("Failed to fetch android project")
        assertThat(libAndroidProject.variants).isNotEmpty()
        for (variant in libAndroidProject.variants) {
            assertThat(variant.mainArtifact.minSdkVersion.apiLevel)
                .named("variant %s mainArtifact.minSdkVersion.apiLevel", variant.name)
                .isEqualTo(MIN_SDK_VERSION)
        }
    }

    class LibSharedCheck: LibraryComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: LibraryAndroidComponentsExtension
        ) {
            androidComponents.finalizeDsl { extension ->
                check(extension.compileSdk == COMPILE_SDK_VERSION) {
                    "compileSdk should be ${DEFAULT_COMPILE_SDK_VERSION}"
                }
                check(extension.defaultConfig.minSdk == MIN_SDK_VERSION) {
                    "minSdk should be ${MIN_SDK_VERSION}"
                }
                check(extension.testOptions.targetSdk == TARGET_SDK_VERSION) {
                    "targetSdk should be ${TARGET_SDK_VERSION}"
                }
            }
        }
    }

    class LibMinorVersionCheck: LibraryComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: LibraryAndroidComponentsExtension
        ) {
            androidComponents.finalizeDsl { extension ->
                check(extension.compileSdkMinor == COMPILE_SDK_MINOR_VERSION) {
                    "compileSdkMinor should be ${COMPILE_SDK_MINOR_VERSION}"
                }
            }
        }
    }

    class KmpLibVersionCheck : AndroidKotlinMultiplatformLibraryComponentCallback {
        override fun handleExtension(
            project: Project,
            extension: KotlinMultiplatformAndroidComponentsExtension
        ) {
            extension.finalizeDsl { extension ->
                check(extension.compileSdk == COMPILE_SDK_VERSION) {
                    "compileSdk should be $COMPILE_SDK_VERSION"
                }
                check(extension.minSdk == MIN_SDK_VERSION) {
                    "compileSdkExtension should be $MIN_SDK_VERSION"
                }
                extension.compileSdk {
                    check(version?.minorApiLevel == COMPILE_SDK_MINOR_VERSION) {
                        "compileSdkMinor should be ${COMPILE_SDK_MINOR_VERSION}"
                    }
                }
            }
        }
    }

    companion object {
        val COMPILE_SDK_VERSION = DEFAULT_COMPILE_SDK_VERSION
        val TARGET_SDK_VERSION = DEFAULT_COMPILE_SDK_VERSION - 1
        val MIN_SDK_VERSION = DEFAULT_COMPILE_SDK_VERSION - 2
        const val COMPILE_SDK_MINOR_VERSION = 1
    }
}
