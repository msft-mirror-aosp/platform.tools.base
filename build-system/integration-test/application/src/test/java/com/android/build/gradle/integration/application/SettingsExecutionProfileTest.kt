/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.integration.common.fixture.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.DEFAULT_MIN_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.GradleSettingsDefinition
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.options.StringOption
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class SettingsExecutionProfileTest {

    @get:Rule
    val rule = GradleRule.from {
        settings {
            applyPlugin(PluginType.ANDROID_SETTINGS)
        }
        androidLibrary(createMinimumProject = false) {
            android.namespace = "com.example.lib"
        }
        androidApplication(createMinimumProject = false) {
            android.namespace = "com.example.app"
            files.setupMinimumManifest()
        }
    }

    data class Profile(
        val name: String,
        val r8JvmOptions: List<String>,
        val r8RunInSeparateProcess: Boolean
    )

    private val defaultProfiles: List<Profile> = listOf(
        Profile("low", listOf("-Xms200m", "-Xmx200m"), false),
        Profile("high", listOf("-Xms800m", "-Xmx800m"), true),
        Profile("pizza", listOf("-Xms801m", "-Xmx801m", "-XX:+HeapDumpOnOutOfMemoryError"), false),
    )

    private fun GradleSettingsDefinition.addSettingsBlock(
        minSdk: Int = DEFAULT_MIN_SDK_VERSION,
        compileSdk: Int = DEFAULT_COMPILE_SDK_VERSION,
        targetSdk: Int = compileSdk,
        execProfile: String?,
        profileList: List<Profile> = defaultProfiles
    ) {
        android {
            this.compileSdk = compileSdk
            this.minSdk = minSdk
            this.targetSdk = targetSdk
            execution {
                profiles {
                    profileList.forEach { profile ->
                        create(profile.name) {
                            it.r8 {
                                jvmOptions += profile.r8JvmOptions
                                runInSeparateProcess = profile.r8RunInSeparateProcess
                            }
                        }
                    }
                }
                execProfile?.let {
                    defaultProfile = it
                }
            }
        }
    }

    private fun ApplicationExtension.withShrinker() {
        buildTypes {
            named("debug") {
                it.isMinifyEnabled = true
            }
        }
    }

    @Test
    fun testInvalidProfile() {
        val build = rule.build {
            settings {
                addSettingsBlock(execProfile = "invalid")
            }
            androidApplication {
                android.withShrinker()
            }
        }

        val result = build.executor.expectFailure().run("assembleDebug")

        result.stderr.use {
            ScannerSubject.assertThat(it).contains("Selected profile 'invalid' does not exist")
        }
    }

    @Test
    fun testProfileOverride() {
        val build = rule.build {
            settings {
                // First try to build with invalid profile
                addSettingsBlock(execProfile = "invalid")
            }
            androidApplication {
                android.withShrinker()
            }
        }

        var result = build.executor.expectFailure().run("assembleDebug")

        result.stderr.use {
            ScannerSubject.assertThat(it).contains("Selected profile 'invalid' does not exist")
        }

        // Make sure it builds when overriding the profile
        result = build.executor
            .with(StringOption.EXECUTION_PROFILE_SELECTION, "low")
            .run("clean", "assembleDebug")

        result.stdout.use {
            ScannerSubject.assertThat(it)
                    .contains("Using execution profile from android.settings.executionProfile 'low'")
        }
    }

    @Test
    fun testProfileAutoSelection() {
        val build = rule.build {
            settings {
                // Building with no profiles and no selection should go to default
                addSettingsBlock(execProfile = null, profileList = listOf())
            }
            androidApplication {
                android.withShrinker()
            }
        }

        build.executor.run("assembleDebug")

        // Adding one profile should auto-select it, so it should also work
        build.reconfigureSettings {
            android {
                execution.profiles {
                    create("profileOne") {
                        it.r8.runInSeparateProcess = false
                    }
                }
            }
        }
        var result = build.executor.run("clean", "assembleDebug")

        result.stdout.use {
            ScannerSubject.assertThat(it).contains("Using only execution profile 'profileOne'")
        }

        // Adding another profile with no selection should fail
        build.reconfigureSettings {
            android {
                execution.profiles {
                    create("profileTwo") { }
                }
            }
        }
        result = build.executor.expectFailure().run("clean", "assembleDebug")

        result.stderr.use {
            ScannerSubject.assertThat(it)
                    .contains("Found 2 execution profiles [profileOne, profileTwo], but no profile was selected.\n")
        }

        // Selecting a profile through override should work
        build.executor
                .with(StringOption.EXECUTION_PROFILE_SELECTION, "profileOne")
                .run("clean", "assembleDebug")

        // So should adding the profile selection to the settings file
        build.reconfigureSettings {
            android {
                execution.defaultProfile = "profileTwo"
            }
        }
        build.executor.run("clean", "assembleDebug")
    }

    // regression test for b/258704137
    @Test
    fun testJvmOptionsAreUsed() {
        val build = rule.build {
            settings {
                addSettingsBlock(
                    execProfile = "mid",
                    profileList = listOf(
                        Profile("mid", listOf(":pizza/foo"), true)
                    )
                )
            }
            androidApplication {
                android.withShrinker()
            }
        }

        val result = build.executor.expectFailure().run("clean", "minifyDebugWithR8")

        // If the jvm args used, r8 will be unable to create the separate process
        // with invalid arguments
        result.stderr.use {
            ScannerSubject.assertThat(it).contains("Error: Could not find or load main class :pizza.foo")
        }
    }

    @Test
    fun checkCompileMinAndTargetAreSet() {
        val compileSdk = DEFAULT_COMPILE_SDK_VERSION
        val targetSdk = DEFAULT_COMPILE_SDK_VERSION - 1
        val minSdk = DEFAULT_COMPILE_SDK_VERSION - 2

        val build = rule.build {
            settings {
                addSettingsBlock(compileSdk = compileSdk,
                    targetSdk = targetSdk,
                    minSdk = minSdk,
                    execProfile = null,
                    profileList = listOf())
            }
        }

        // First check app
        val modelInfo = build.modelBuilder
            .fetchModels()
            .container
            .getProject(DEFAULT_APP_PATH)

        val androidDsl = modelInfo.androidDsl ?: error("failed to fetch android DSL model")
        assertThat(androidDsl.compileTarget)
                .named("androidDsl.compileTarget")
                .isEqualTo("android-$compileSdk")
        assertThat(androidDsl.defaultConfig.targetSdkVersion?.apiLevel)
                .named("androidDsl.defaultConfig.targetSdkVersion.apiLevel")
                .isEqualTo(targetSdk)
        assertThat(androidDsl.lintOptions?.targetSdk?.apiLevel)
            .named("androidDsl.lintOptions.targetSdk.apiLevel")
            .isEqualTo(null)
        val androidProject = modelInfo.androidProject ?: error("Failed to fetch android project")
        assertThat(androidProject.variants).isNotEmpty()
        for (variant in androidProject.variants) {
            assertThat(variant.mainArtifact.minSdkVersion.apiLevel)
                    .named("variant %s mainArtifact.minSdkVersion.apiLevel", variant.name)
                    .isEqualTo(minSdk)
        }

        // Then check library
        val libModelInfo = build.modelBuilder
            .fetchModels()
            .container
            .getProject(DEFAULT_LIB_PATH)
        val libAndroidDsl = libModelInfo.androidDsl ?: error("failed to fetch android DSL model")
        assertThat(libAndroidDsl.compileTarget)
            .named("libAndroidDsl.compileTarget")
            .isEqualTo("android-$compileSdk")
        assertThat(libAndroidDsl.defaultConfig.targetSdkVersion)
            .named("libAndroidDsl.defaultConfig.targetSdkVersion")
            .isEqualTo(null)
        assertThat(libAndroidDsl.lintOptions?.targetSdk?.apiLevel)
            .named("libAndroidDsl.lintOptions.targetSdk.apiLevel")
            .isEqualTo(targetSdk)
        val libAndroidProject = modelInfo.androidProject ?: error("Failed to fetch android project")
        assertThat(libAndroidProject.variants).isNotEmpty()
        for (variant in libAndroidProject.variants) {
            assertThat(variant.mainArtifact.minSdkVersion.apiLevel)
                .named("variant %s mainArtifact.minSdkVersion.apiLevel", variant.name)
                .isEqualTo(minSdk)
        }
    }
}
