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

package com.android.build.gradle.integration.common.fixture.project.builder

import com.android.Version
import com.android.build.api.dsl.Lint
import com.android.build.gradle.integration.common.fixture.project.builder.kotlin.KotlinExtension
import com.android.build.gradle.internal.privaysandboxsdk.PrivacySandboxSdkConstants.androidxPrivacySandboxLibraryPluginVersion
import com.android.build.gradle.internal.utils.ANDROID_BUILT_IN_KOTLIN_PLUGIN_ID
import com.android.build.gradle.internal.utils.KOTLIN_ANDROID_PLUGIN_ID
import com.android.testutils.TestUtils

sealed class PluginType(
    open val id: String,
    val isAndroid: Boolean = false,
    val isSettings: Boolean = false,
    open val artifact: String? = null,
    open val version: String? = null,
    open val hasMarker: Boolean = true,
) {

    // Core Java plugins
    object JAVA_LIBRARY: PluginType("java-library")
    object JAVA: PluginType("java")
    object JAVA_PLATFORM: PluginType("java-platform")
    object APPLICATION: PluginType("application")
    object JAVA_TEST_FIXTURES: PluginType("java-test-fixtures")
    object MAVEN_PUBLISH: PluginType("maven-publish")
    object JAVA_GRADLE_PLUGIN: PluginType("java-gradle-plugin")
    // --------------
    // Kotlin plugins
    @Deprecated("Consider using the built-in support with AGP 9.0 default behavior")
    object KOTLIN_JVM: PluginTypeWithExtension<KotlinExtension>(
        id = "org.jetbrains.kotlin.jvm",
        artifact = "org.jetbrains.kotlin:kotlin-gradle-plugin",
        version = TestUtils.KOTLIN_VERSION_FOR_TESTS,
        extensionType = KotlinExtension::class.java,
        extensionName = "kotlin"
    )
    object KOTLIN_ANDROID: PluginType(
        id = KOTLIN_ANDROID_PLUGIN_ID,
        isAndroid = true,
        artifact = "org.jetbrains.kotlin:kotlin-gradle-plugin",
        version = TestUtils.KOTLIN_VERSION_FOR_TESTS
    )
    object KAPT: PluginType(
        id = "org.jetbrains.kotlin.kapt",
        version = TestUtils.KOTLIN_VERSION_FOR_TESTS
    )
    object KSP: PluginType(
        id = "com.google.devtools.ksp",
        version = TestUtils.KSP_VERSION_FOR_TESTS
    )
    object KOTLIN_MPP: PluginType(
        id = "org.jetbrains.kotlin.multiplatform",
        artifact = "org.jetbrains.kotlin:kotlin-gradle-plugin",
        version = TestUtils.KOTLIN_VERSION_FOR_TESTS
    )
    object COMPOSE_COMPILER_PLUGIN: PluginType(
        id = com.android.build.gradle.internal.utils.COMPOSE_COMPILER_PLUGIN_ID,
        version = TestUtils.KOTLIN_VERSION_FOR_TESTS
    )
    // -----------
    // AGP Plugins
    object ANDROID_APP: AgpPlugin("com.android.application")
    object ANDROID_LIB: AgpPlugin("com.android.library")
    object ANDROID_TEST: AgpPlugin("com.android.test")
    object ANDROID_DYNAMIC_FEATURE: AgpPlugin("com.android.dynamic-feature")
    object FUSED_LIBRARY: AgpPlugin("com.android.fused-library")
    object PRIVACY_SANDBOX_SDK: AgpPlugin("com.android.privacy-sandbox-sdk")
    object ANDROID_ASSET_PACK: AgpPlugin("com.android.asset-pack")
    object ANDROID_AI_PACK: AgpPlugin("com.android.ai-pack")
    object ANDROID_ASSET_PACK_BUNDLE: AgpPlugin("com.android.asset-pack-bundle")
    object ANDROID_KMP_LIBRARY: AgpPlugin("com.android.kotlin.multiplatform.library")
    @Deprecated("Unnecessary with AGP 9.0 default behavior (built-in kotlin enabled)")
    object ANDROID_BUILT_IN_KOTLIN: AgpPlugin(ANDROID_BUILT_IN_KOTLIN_PLUGIN_ID)
    object LINT: PluginTypeWithExtension<Lint>(
        id = "com.android.lint",
        isAndroid = false,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION,
        extensionType = Lint::class.java,
        extensionName = "lint"
    )

    object ANDROID_SETTINGS: PluginType(
        id = "com.android.settings",
        isAndroid = true,
        isSettings = true,
        artifact = "com.android.tools.build:gradle-settings",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION
    )

    // -----------
    // AndroidX plugins
    object ANDROIDX_PRIVACY_SANDBOX_LIBRARY: PluginType(
        id = "androidx.privacysandbox.library",
        isAndroid = true,
        artifact = "androidx.privacysandbox.plugins:plugins-privacysandbox-library",
        version = androidxPrivacySandboxLibraryPluginVersion,
    )

    /**
     * A custom Plugin type when we need to apply a plugin that is not already defined,
     */
    data class Custom(
        override val id: String,
        override val version: String? = null,
        override val artifact: String? = null,
        override val hasMarker: Boolean = true,
    ) : PluginType(
        id,
        version = version,
        artifact = artifact,
        hasMarker = hasMarker
    )

    /**
     * A plugin that is associated with an extension which can be configured in
     * [GradleProjectDefinition.applyPlugin]
     */
    open class PluginTypeWithExtension<T>(
        id: String,
        isAndroid: Boolean = false,
        isSettings: Boolean = false,
        artifact: String? = null,
        version: String? = null,
        hasMarker: Boolean = true,
        /** extension type to allow usage via [PluginBuilder] */
        val extensionType: Class<T>,
        val extensionName: String,
    ): PluginType(id, isAndroid, isSettings, artifact, version, hasMarker)

    // ---------------
    // internal plugin implementations

    // Agp Plugins with specified artifact and version.
    abstract class AgpPlugin(
        id: String,
        isAndroid: Boolean = true
    ): PluginType(
        id = id,
        isAndroid = isAndroid,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION,
    )
}

