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

package com.android.build.gradle.integration.common.fixture.testprojects

import com.android.Version
import com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox.androidxPrivacySandboxLibraryPluginVersion
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
    object JAVA_LIBRARY: PluginType(
        id = "java-library",
    )
    object JAVA: PluginType(
        id = "java",
    )
    object JAVA_PLATFORM: PluginType(
        id = "java-platform",
    )
    object APPLICATION: PluginType(
        id = "application",
    )
    object KOTLIN_JVM: PluginType(
        id = "org.jetbrains.kotlin.jvm",
        version = TestUtils.KOTLIN_VERSION_FOR_TESTS
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
        version = TestUtils.KOTLIN_VERSION_FOR_TESTS
    )
    object ANDROID_APP: PluginType(
        id = "com.android.application",
        isAndroid = true,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION
    )
    object ANDROID_LIB: PluginType(
        id = "com.android.library",
        isAndroid = true,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION
    )
    object ANDROID_TEST: PluginType(
        id = "com.android.test",
        isAndroid = true,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION
    )
    object ANDROID_DYNAMIC_FEATURE: PluginType(
        id = "com.android.dynamic-feature",
        isAndroid = true,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION
    )
    object FUSED_LIBRARY: PluginType(
        id = "com.android.fused-library",
        isAndroid = true,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION
    )
    object PRIVACY_SANDBOX_SDK: PluginType(
        id = "com.android.privacy-sandbox-sdk",
        isAndroid = true,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION
    )
    object ANDROIDX_PRIVACY_SANDBOX_LIBRARY: PluginType(
        id = "androidx.privacysandbox.library",
        isAndroid = true,
        artifact = "androidx.privacysandbox.plugins:plugins-privacysandbox-library",
        version = androidxPrivacySandboxLibraryPluginVersion,
    )
    object ANDROID_ASSET_PACK: PluginType(
        id = "com.android.asset-pack",
        isAndroid = true,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION,
    )
    object ANDROID_AI_PACK: PluginType(
        id = "com.android.ai-pack",
        isAndroid = true,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION,
    )
    object ANDROID_ASSET_PACK_BUNDLE: PluginType(
        id = "com.android.asset-pack-bundle",
        isAndroid = true,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION,
    )
    object ANDROID_SETTINGS: PluginType(
        id = "com.android.settings",
        isAndroid = true,
        isSettings = true,
        artifact = "com.android.tools.build:gradle-settings",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION
    )
    object JAVA_TEST_FIXTURES: PluginType(
        id = "java-test-fixtures",
    )
    object MAVEN_PUBLISH: PluginType(
        id= "maven-publish",
    )
    object JAVA_GRADLE_PLUGIN: PluginType(
        id="java-gradle-plugin"
    )
    object ANDROID_BUILT_IN_KOTLIN: PluginType(
        id = ANDROID_BUILT_IN_KOTLIN_PLUGIN_ID,
        isAndroid = true,
        artifact = "com.android.tools.build:gradle",
        version = Version.ANDROID_GRADLE_PLUGIN_VERSION
    )
    object COMPOSE_COMPILER_PLUGIN: PluginType(
        id = com.android.build.gradle.internal.utils.COMPOSE_COMPILER_PLUGIN_ID,
        version = TestUtils.KOTLIN_VERSION_FOR_TESTS
    )

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
}

internal fun Iterable<PluginType>.containsAndroid() = any { it.isAndroid }
