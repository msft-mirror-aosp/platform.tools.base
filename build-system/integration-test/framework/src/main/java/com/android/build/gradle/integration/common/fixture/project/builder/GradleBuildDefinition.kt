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
import com.android.build.gradle.integration.common.fixture.project.AssetPackBundleDefinition
import com.android.build.gradle.integration.common.fixture.project.AssetPackDefinition
import com.android.build.gradle.integration.common.fixture.project.DependencySubstitutionsBuilder
import com.android.build.gradle.integration.common.fixture.project.FusedLibraryDefinition
import com.android.build.gradle.integration.common.fixture.project.GenericProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.JavaLibraryProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.KotlinMultiplatformDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_TEST_PATH
import com.android.build.gradle.integration.common.fixture.project.options.GradlePropertiesBuilder
import com.google.common.base.Strings

/**
 * Represents a Gradle Build that can be configured before being written on disk
 */
@GradleDefinitionDsl
interface GradleBuildDefinition {
    companion object {
        const val DEFAULT_BUILD_NAME = "project"

        val DEFAULT_COMPILE_SDK_VERSION: Int =
            Strings.emptyToNull(System.getenv("CUSTOM_COMPILE_SDK"))?.toInt()
                ?: com.android.build.gradle.integration.common.fixture.DEFAULT_COMPILE_SDK_VERSION
    }

    /**
     * The name of the build. This is the value set in the settings.gradle files as the logical
     * name of the build in gradle
     */
    val name: String

    /**
     * The type of files to use when generating gradle files.
     */
    var buildFileType: BuildFileType

    /**
     * Whether to use the old plugin style in order to get different classloaders. Default is false.
     *
     * This should only be used when trying to create situation where different projects have
     * different classloaders for AGP.
     *
     * Note that this is not enough to get different classloaders. Each project should also
     * have a different classpath. One can use local jars on each project using
     * [GradleProjectDefinition.buildscript] to achieve this.
     */
    var useOldPluginStyleForSeparateClassloaders: Boolean

    /**
     * Whether to modify the buildscript classpath to upgrade KGP from the version that AGP depends
     * on to the latest version ([com.android.testutils.TestUtils.KOTLIN_VERSION_FOR_TESTS]).
     *
     * This is used to test AGP against the latest version of KGP.
     *
     * The default is `false`.
     */
    var useLatestKgpVersion: Boolean

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
     * Configures a subProject with the Android Asset Pack Bundle plugin, creating it if needed.
     */
    fun assetPackBundle(
        path: String,
        createMinimumProject: Boolean = true,
        action: AssetPackBundleDefinition.() -> Unit
    ): AssetPackBundleDefinition

    /**
     * Configures a subProject with the Android Fused Library plugin, creating it if needed.
     */
    fun fusedLibrary(
        path: String,
        createMinimumProject: Boolean = true,
        action: FusedLibraryDefinition.() -> Unit
    ): FusedLibraryDefinition

    /**
     * Configures a subproject with the Java Library plugin, creating it if needed.
     */
    fun javaLibrary(
        path: String,
        action: JavaLibraryProjectDefinition.() -> Unit
    ): JavaLibraryProjectDefinition

    /**
     * Configures a subProject with the Kotlin multiplatform plugin, configured with the Android
     * plugin, creating it if needed.
     */
    fun androidKotlinMultiplatformLibrary(
        path: String,
        createMinimumProject: Boolean = true,
        action: KotlinMultiplatformDefinition.() -> Unit
    ) : KotlinMultiplatformDefinition

    /**
     * Configures a subProject with the Kotlin multiplatform plugin, creating it if needed.
     */
    fun kotlinMultiplatformLibrary(
        path: String,
        action: KotlinMultiplatformDefinition.() -> Unit
    ) : KotlinMultiplatformDefinition

    /**
     * Configures an existing project in a generic way.
     *
     * The project definition *must* have been created beforehand. The project can be of any
     * type, but the action is generic.
     */
    fun configure(
        path: String,
        action: GradleProjectDefinition.() -> Unit
    )

    /**
     * configures the Gradle properties for this build
     */
    fun gradleProperties(action: GradlePropertiesBuilder.() -> Unit)

    /** Configures dependency substitution rules via settings. */
    fun dependencySubstitution(action: DependencySubstitutionsBuilder.() -> Unit)
}
