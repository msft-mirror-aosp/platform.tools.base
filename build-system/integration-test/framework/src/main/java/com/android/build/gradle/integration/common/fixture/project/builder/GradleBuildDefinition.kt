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

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.dsl.TestExtension
import com.android.build.gradle.integration.common.fixture.project.AiPackDefinition
import com.android.build.gradle.integration.common.fixture.project.AssetPackDefinition
import com.android.build.gradle.integration.common.fixture.project.GenericProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.PrivacySandboxSdkDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_TEST_PATH
import com.android.build.gradle.integration.common.fixture.project.options.GradlePropertiesBuilder
import com.android.build.gradle.integration.common.fixture.testprojects.BuildFileType

/**
 * Represents a Gradle Build that can be configured before being written on disk
 */
interface GradleBuildDefinition {
    companion object {
        const val DEFAULT_BUILD_NAME = "project"
    }

    /**
     * The name of the build. This impacts both the logical name and the folder in which the build
     * is created
     */
    var name: String

    /**
     * The root folder name. This only impacts the folder and not the logical name.
     * If you wish to change the logical name only, use [name]
     */
    var rootFolderName: String

    /**
     * The type of files to use when generating gradle files.
     */
    var buildFileType: BuildFileType

    fun settings(action: GradleSettingsDefinition.() -> Unit)

    fun includedBuild(name: String, action: GradleBuildDefinition.() -> Unit): GradleBuildDefinition

    /**
     * Configures the root project. This cannot be an Android Project.
     */
    fun rootProject(action: GenericProjectDefinition.() -> Unit)

    /**
     * Configures a subProject, creating it if needed.
     */
    fun genericProject(path: String, action: GenericProjectDefinition.() -> Unit): GenericProjectDefinition

    /**
     * Configures a subProject with the Android Application plugin, creating it if needed.
     *
     * if the project is already created, `createMinimumProject` has no effect
     *
     * @param path the Gradle path of the project
     * @param createMinimumProject whether to create a minimum project (namespace, compileSdk, manifest)
     */
    fun androidApplication(
        path: String = DEFAULT_APP_PATH,
        createMinimumProject: Boolean = true,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ): AndroidProjectDefinition<ApplicationExtension>

    /**
     * Configures a subProject with the Android Application plugin, creating it if needed
     *
     * This also creates some basic content: activity (java), manifest, layout
     */
    fun androidJavaApplication(
        path: String = DEFAULT_APP_PATH,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ): AndroidProjectDefinition<ApplicationExtension>

    /**
     * Configures a subProject with the Android Application plugin, creating it if needed
     *
     * This also creates some basic content: activity (kotlin, manifest, layout
     */
    fun androidKotlinApplication(
        path: String = DEFAULT_APP_PATH,
        action: AndroidProjectDefinition<ApplicationExtension>.() -> Unit
    ): AndroidProjectDefinition<ApplicationExtension>

    /**
     * Configures a subProject with the Android Library plugin, creating it if needed.
     *
     * if the project is already created, `createMinimumProject` has no effect
     *
     * @param path the Gradle path of the project
     * @param createMinimumProject whether to create a minimum project (namespace, compileSdk, manifest)
     */
    fun androidLibrary(
        path: String = DEFAULT_LIB_PATH,
        createMinimumProject: Boolean = true,
        action: AndroidProjectDefinition<LibraryExtension>.() -> Unit
    ): AndroidProjectDefinition<LibraryExtension>

    /**
     * Configures a subProject with the Android Dynamic Feature plugin, creating it if needed.
     *
     * if the project is already created, `createMinimumProject` has no effect
     *
     * @param path the Gradle path of the project
     * @param createMinimumProject whether to create a minimum project (namespace, compileSdk, manifest)
     */
    fun androidFeature(
        path: String = DEFAULT_FEATURE_PATH,
        createMinimumProject: Boolean = true,
        action: AndroidProjectDefinition<DynamicFeatureExtension>.() -> Unit
    ): AndroidProjectDefinition<DynamicFeatureExtension>

    /**
     * Configures a subProject with the Android Test plugin, creating it if needed.
     *
     * if the project is already created, `createMinimumProject` has no effect
     *
     * @param path the Gradle path of the project
     * @param createMinimumProject whether to create a minimum project (namespace, compileSdk, manifest)
     */
    fun androidTest(
        path: String = DEFAULT_TEST_PATH,
        createMinimumProject: Boolean = true,
        action: AndroidProjectDefinition<TestExtension>.() -> Unit
    ): AndroidProjectDefinition<TestExtension>

    /**
     * Configures a subProject with the Android Privacy Sandbox SDK plugin, creating it if needed.
     *
     * if the project is already created, `createMinimumProject` has no effect
     *
     * @param path the Gradle path of the project
     * @param createMinimumProject whether to create a minimum project (namespace, compileSdk, manifest)
     */
    fun privacySandboxSdk(
        path: String,
        createMinimumProject: Boolean = true,
        action: PrivacySandboxSdkDefinition.() -> Unit
    ): PrivacySandboxSdkDefinition

    /**
     * Configures a subProject with the Android AI Pack plugin, creating it if needed.
     */
    fun aiPack(
        path: String,
        action: AiPackDefinition.() -> Unit
    ): AiPackDefinition

    /**
     * Configures a subProject with the Android Asset Pack plugin, creating it if needed.
     */
    fun assetPack(
        path: String,
        action: AssetPackDefinition.() -> Unit
    ): AssetPackDefinition

    /**
     * configures the Gradle properties for this build
     */
    fun gradleProperties(action: GradlePropertiesBuilder.() -> Unit)
}
