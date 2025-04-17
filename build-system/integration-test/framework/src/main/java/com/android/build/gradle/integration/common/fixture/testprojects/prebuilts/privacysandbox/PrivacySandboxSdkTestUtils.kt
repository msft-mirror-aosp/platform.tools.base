/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.privacysandbox

import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.utils.SdkHelper
import com.android.sdklib.BuildToolInfo
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project

private val aidlPath = SdkHelper.getBuildTool(BuildToolInfo.PathId.AIDL).absolutePath
        .replace("""\""", """\\""")
const val androidxPrivacySandboxActivityVersion = "1.0.0-alpha01"
const val androidxPrivacySandboxVersion = "1.0.0-alpha10"
const val androidxPrivacySandboxSdkRuntimeVersion = "1.0.0-alpha13"
const val androidxPrivacySandboxLibraryPluginVersion = "1.0.0-alpha02"

fun GradleBuildDefinition.privacySandboxSdkLibraryProject(
    path: String,
    action: AndroidProjectDefinition<LibraryExtension>.() -> Unit
) {
    androidLibrary(path, createMinimumProject = false) {
        applyPlugin(PluginType.KOTLIN_ANDROID, version = KOTLIN_VERSION_FOR_PRIVACY_SANDBOX_TESTS)
        applyPlugin(PluginType.KSP, version = KSP_VERSION_FOR_PRIVACY_SANDBOX_TESTS)
        android {
            compileSdk = DEFAULT_COMPILE_SDK_VERSION
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
            }
        }
        legacyKotlin {
            jvmTarget = "1.8"
        }
        dependencies {
            implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.10")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.3")
            implementation("androidx.privacysandbox.tools:tools:$androidxPrivacySandboxVersion")
            implementation("androidx.privacysandbox.sdkruntime:sdkruntime-core:$androidxPrivacySandboxSdkRuntimeVersion")
            implementation("androidx.privacysandbox.sdkruntime:sdkruntime-client:$androidxPrivacySandboxSdkRuntimeVersion")

            ksp("androidx.privacysandbox.tools:tools-apicompiler:$androidxPrivacySandboxVersion")
            ksp("androidx.annotation:annotation:1.6.0")
        }
        pluginCallbacks += KspAndAidlSetupCallback::class.java
        // Have an empty manifest as a regression test of b/237279793
        files.setupMinimumManifest()
        action()
    }
}

class KspAndAidlSetupCallback: LibraryComponentCallback {
    override fun handleExtension(
        project: Project,
        androidComponents: LibraryAndroidComponentsExtension
    ) {
        // use finalize DSL to make sure KSP has been applied
        androidComponents.finalizeDsl { it ->
            val ksp = project.extensions.getByType(KspExtension::class.java)
            ksp.arg(
                "aidl_compiler_path",
                androidComponents.sdkComponents.aidl.get().executable.get().asFile.absolutePath
            )
        }
    }
}
