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

package com.android.build.gradle.integration.common.fixture.dsl

import com.android.build.api.dsl.BaseFlavor
import com.android.build.api.dsl.ExternalNativeBuildFlags
import com.android.build.api.dsl.JavaCompileOptions
import com.android.build.api.dsl.MinSdkSpec
import com.android.build.api.dsl.Ndk
import com.android.build.api.dsl.Optimization
import com.android.build.api.dsl.ProductFlavor
import com.android.build.api.dsl.Shaders
import com.android.build.api.dsl.VectorDrawables
import org.gradle.api.plugins.ExtensionContainer
import java.io.File

/**
 * Implemented manually due to the conflict between [setDimension] and [dimension] that breaks
 * the normal Java Proxy feature (class is considered broken)
 */
@Suppress("UNCHECKED_CAST", "OVERRIDE_DEPRECATION")
open class UnusableProductFlavorProxy(): ProductFlavor {

    override var dimension: String?
        get() = throwUnusableError("ProductFlavor.dimension")
        set(value) {
            throwUnusableError("ProductFlavor.dimension")
        }

    override val matchingFallbacks: MutableList<String>
        get() = throwUnusableError("ProductFlavor.matchingFallbacks")

    override fun setMatchingFallbacks(vararg fallbacks: String) {
        throwUnusableError("ProductFlavor.setMatchingFallbacks")
    }

    override fun setMatchingFallbacks(fallbacks: List<String>) {
        throwUnusableError("ProductFlavor.setMatchingFallbacks")
    }

    override fun getName(): String {
        throwUnusableError("ProductFlavor.getName")
    }

    override var testApplicationId: String?
        get() = throwUnusableError("ProductFlavor.testApplicationId")
        set(value) {
            throwUnusableError("ProductFlavor.testApplicationId")
        }
    override var minSdk: Int?
        get() = throwUnusableError("ProductFlavor.minSdk")
        set(value) {
            throwUnusableError("ProductFlavor.minSdk")
        }

    override fun minSdk(action: MinSdkSpec.() -> Unit) {
        throwUnusableError("ProductFlavor.minSdk")
    }

    override fun setMinSdkVersion(minSdkVersion: Int) {
        throwUnusableError("ProductFlavor.setMinSdkVersion")
    }

    override fun setMinSdkVersion(minSdkVersion: String?) {
        throwUnusableError("ProductFlavor.setMinSdkVersion")
    }

    override fun minSdkVersion(minSdkVersion: Int) {
        throwUnusableError("ProductFlavor.minSdkVersion")
    }

    override fun minSdkVersion(minSdkVersion: String?) {
        throwUnusableError("ProductFlavor.minSdkVersion")
    }

    override var minSdkPreview: String?
        get() = throwUnusableError("ProductFlavor.minSdkPreview")
        set(value) {
            throwUnusableError("ProductFlavor.minSdkPreview")
        }
    override var renderscriptTargetApi: Int?
        get() = throwUnusableError("ProductFlavor.renderscriptTargetApi")
        set(value) {
            throwUnusableError("ProductFlavor.renderscriptTargetApi")
        }
    override var renderscriptSupportModeEnabled: Boolean?
        get() = throwUnusableError("ProductFlavor.renderscriptSupportModeEnabled")
        set(value) {
            throwUnusableError("ProductFlavor.renderscriptSupportModeEnabled")
        }
    override var renderscriptSupportModeBlasEnabled: Boolean?
        get() = throwUnusableError("ProductFlavor.renderscriptSupportModeBlasEnabled")
        set(value) {
            throwUnusableError("ProductFlavor.renderscriptSupportModeBlasEnabled")
        }
    override var renderscriptNdkModeEnabled: Boolean?
        get() = throwUnusableError("ProductFlavor.renderscriptNdkModeEnabled")
        set(value) {
            throwUnusableError("ProductFlavor.renderscriptNdkModeEnabled")
        }
    override var testInstrumentationRunner: String?
        get() = throwUnusableError("ProductFlavor.testInstrumentationRunner")
        set(value) {
            throwUnusableError("ProductFlavor.testInstrumentationRunner")
        }
    override val testInstrumentationRunnerArguments: MutableMap<String, String>
        get() = throwUnusableError("ProductFlavor.testInstrumentationRunnerArguments")

    override fun testInstrumentationRunnerArgument(key: String, value: String) {
        throwUnusableError("ProductFlavor.testInstrumentationRunnerArgument")
    }

    override fun setTestInstrumentationRunnerArguments(testInstrumentationRunnerArguments: MutableMap<String, String>): Any? {
        throwUnusableError("ProductFlavor.setTestInstrumentationRunnerArguments")
    }

    override fun testInstrumentationRunnerArguments(args: Map<String, String>) {
        throwUnusableError("ProductFlavor.testInstrumentationRunnerArguments")    }

    override var testHandleProfiling: Boolean?
        get() = throwUnusableError("ProductFlavor.testHandleProfiling")
        set(value) {
            throwUnusableError("ProductFlavor.testHandleProfiling")
        }

    override fun setTestHandleProfiling(testHandleProfiling: Boolean): Any? {
        throwUnusableError("ProductFlavor.setTestHandleProfiling")
    }

    override var testFunctionalTest: Boolean?
        get() = throwUnusableError("ProductFlavor.testFunctionalTest")
        set(value) {
            throwUnusableError("ProductFlavor.testFunctionalTest")
        }

    override fun setTestFunctionalTest(testFunctionalTest: Boolean): Any? {
        throwUnusableError("ProductFlavor.setTestFunctionalTest")
    }

    override val resourceConfigurations: MutableSet<String>
        get() = throwUnusableError("ProductFlavor.resourceConfigurations")

    override fun resConfigs(config: Collection<String>) {
        throwUnusableError("ProductFlavor.resConfigs")
    }

    override fun resConfigs(vararg config: String) {
        throwUnusableError("ProductFlavor.resConfigs")
    }

    override fun resConfig(config: String) {
        throwUnusableError("ProductFlavor.resConfig")
    }

    override val vectorDrawables: VectorDrawables
        get() = throwUnusableError("ProductFlavor.vectorDrawables")

    override fun vectorDrawables(action: VectorDrawables.() -> Unit) {
        throwUnusableError("ProductFlavor.vectorDrawables")
    }

    override var wearAppUnbundled: Boolean?
        get() = throwUnusableError("ProductFlavor.wearAppUnbundled")
        set(value) {
            throwUnusableError("ProductFlavor.wearAppUnbundled")
        }

    override fun missingDimensionStrategy(dimension: String, requestedValue: String) {
        throwUnusableError("ProductFlavor.missingDimensionStrategy")
    }

    override fun missingDimensionStrategy(dimension: String, vararg requestedValues: String) {
        throwUnusableError("ProductFlavor.missingDimensionStrategy")
    }

    override fun missingDimensionStrategy(dimension: String, requestedValues: List<String>) {
        throwUnusableError("ProductFlavor.missingDimensionStrategy")
    }

    override fun initWith(that: BaseFlavor) {
        throwUnusableError("ProductFlavor.initWith")
    }

    override var multiDexKeepProguard: File?
        get() = throwUnusableError("ProductFlavor.multiDexKeepProguard")
        set(value) {
            throwUnusableError("ProductFlavor.multiDexKeepProguard")
        }
    override var multiDexKeepFile: File?
        get() = throwUnusableError("ProductFlavor.multiDexKeepFile")
        set(value) {
            throwUnusableError("ProductFlavor.multiDexKeepFile")
        }
    override val ndk: Ndk
        get() = throwUnusableError("ProductFlavor.ndk")

    override fun ndk(action: Ndk.() -> Unit) {
        throwUnusableError("ProductFlavor.ndk")
    }

    override val proguardFiles: MutableList<File>
        get() = throwUnusableError("ProductFlavor.proguardFiles")


    override fun proguardFile(proguardFile: Any): Any {
        throwUnusableError("ProductFlavor.proguardFile")
    }

    override fun proguardFiles(vararg files: Any): Any {
        throwUnusableError("ProductFlavor.proguardFiles")
    }

    override fun setProguardFiles(proguardFileIterable: Iterable<*>): Any {
        throwUnusableError("ProductFlavor.setProguardFiles")
    }

    override val testProguardFiles: MutableList<File>
        get() = throwUnusableError("ProductFlavor.testProguardFiles")


    override fun testProguardFile(proguardFile: Any): Any {
        throwUnusableError("ProductFlavor.testProguardFile")
    }

    override fun testProguardFiles(vararg proguardFiles: Any): Any {
        throwUnusableError("ProductFlavor.testProguardFiles")
    }

    override val manifestPlaceholders: MutableMap<String, Any>
        get() = throwUnusableError("ProductFlavor.manifestPlaceholders")

    override fun addManifestPlaceholders(manifestPlaceholders: Map<String, Any>) {
        throwUnusableError("ProductFlavor.addManifestPlaceholders")
    }

    override fun setManifestPlaceholders(manifestPlaceholders: Map<String, Any>): Void? {
        throwUnusableError("ProductFlavor.setManifestPlaceholders")
    }

    override val javaCompileOptions: JavaCompileOptions
        get() = throwUnusableError("ProductFlavor.javaCompileOptions")

    override fun javaCompileOptions(action: JavaCompileOptions.() -> Unit) {
        throwUnusableError("ProductFlavor.minSdk")
    }

    override val shaders: Shaders
        get() = throwUnusableError("ProductFlavor.shaders")

    override fun shaders(action: Shaders.() -> Unit) {
        throwUnusableError("ProductFlavor.shaders")
    }

    override val externalNativeBuild: ExternalNativeBuildFlags
        get() = throwUnusableError("ProductFlavor.externalNativeBuild")

    override fun externalNativeBuild(action: ExternalNativeBuildFlags.() -> Unit) {
        throwUnusableError("ProductFlavor.externalNativeBuild")
    }

    override fun buildConfigField(type: String, name: String, value: String) {
        throwUnusableError("ProductFlavor.buildConfigField")
    }

    override fun resValue(type: String, name: String, value: String) {
        throwUnusableError("ProductFlavor.resValue")
    }

    override val optimization: Optimization
        get() = throwUnusableError("ProductFlavor.optimization")

    override fun optimization(action: Optimization.() -> Unit) {
        throwUnusableError("ProductFlavor.optimization")
    }

    override fun getExtensions(): ExtensionContainer {
        throwUnusableError("ProductFlavor.getExtensions")
    }
}
