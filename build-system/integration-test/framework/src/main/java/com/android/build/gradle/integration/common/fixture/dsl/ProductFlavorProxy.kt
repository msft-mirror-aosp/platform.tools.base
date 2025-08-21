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
open class ProductFlavorProxy(
    protected val dslRecorder: DslRecorder
): ProductFlavor {

    override var dimension: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("dimension", value)
        }

    override val matchingFallbacks: MutableList<String>
        get() = ListProxy<String>(dslRecorder.createChainedRecorder("matchingFallbacks"))

    override fun setMatchingFallbacks(vararg fallbacks: String) {
        dslRecorder.call("setMatchingFallbacks", listOf(fallbacks), isVarArgs = true)
    }

    override fun setMatchingFallbacks(fallbacks: List<String>) {
        dslRecorder.call("setMatchingFallbacks", fallbacks, isVarArgs = false)
    }

    override fun getName(): String {
        throw RuntimeException("Not yet supported")
    }

    override var testApplicationId: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("testApplicationId", value)
        }
    override var minSdk: Int?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("minSdk", value)
        }

    override fun minSdk(action: MinSdkSpec.() -> Unit) {
        dslRecorder.runNestedBlock(
            name = "minSdk",
            parameters = listOf(),
            instanceProvider = { DslProxy.createProxy(MinSdkSpec::class.java, it) }
        ) {
            action(this)
        }
    }

    override fun setMinSdkVersion(minSdkVersion: Int) {
        dslRecorder.call("setMinSdkVersion", listOf(minSdkVersion), isVarArgs = false)
    }

    override fun setMinSdkVersion(minSdkVersion: String?) {
        dslRecorder.call("setMinSdkVersion", listOf(minSdkVersion), isVarArgs = false)
    }

    override fun minSdkVersion(minSdkVersion: Int) {
        dslRecorder.call("minSdkVersion", listOf(minSdkVersion), isVarArgs = false)
    }

    override fun minSdkVersion(minSdkVersion: String?) {
        dslRecorder.call("minSdkVersion", listOf(minSdkVersion), isVarArgs = false)
    }

    override var minSdkPreview: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("minSdkVersion", value)
        }
    override var renderscriptTargetApi: Int?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("renderscriptTargetApi", value)
        }
    override var renderscriptSupportModeEnabled: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("renderscriptSupportModeEnabled", value)
        }
    override var renderscriptSupportModeBlasEnabled: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("renderscriptSupportModeBlasEnabled", value)
        }
    override var renderscriptNdkModeEnabled: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("renderscriptNdkModeEnabled", value)
        }
    override var testInstrumentationRunner: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("testInstrumentationRunner", value)
        }
    override val testInstrumentationRunnerArguments: MutableMap<String, String>
        get() = MapProxy<String, String>(dslRecorder.createChainedRecorder("testInstrumentationRunnerArguments"))

    override fun testInstrumentationRunnerArgument(key: String, value: String) {
        dslRecorder.call(
            "testInstrumentationRunnerArgument",
            listOf(key, value),
            isVarArgs = false
        )
    }

    override fun setTestInstrumentationRunnerArguments(testInstrumentationRunnerArguments: MutableMap<String, String>): Any? {
        dslRecorder.call(
            "setTestInstrumentationRunnerArguments",
            listOf(testInstrumentationRunnerArguments),
            isVarArgs = false
        )
        return this
    }

    override fun testInstrumentationRunnerArguments(args: Map<String, String>) {
        dslRecorder.call(
            "testInstrumentationRunnerArguments",
            listOf(args),
            isVarArgs = false
        )
    }

    override var testHandleProfiling: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.setBoolean("testHandleProfiling", value, usingIsNotation = false)
        }

    override fun setTestHandleProfiling(testHandleProfiling: Boolean): Any? {
        dslRecorder.call("setTestHandleProfiling", listOf(testHandleProfiling), isVarArgs = false)
        return this
    }

    override var testFunctionalTest: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.setBoolean("testFunctionalTest", value, usingIsNotation = false)
        }

    override fun setTestFunctionalTest(testFunctionalTest: Boolean): Any? {
        dslRecorder.call("setTestFunctionalTest", listOf(testFunctionalTest), isVarArgs = false)
        return this
    }

    override val resourceConfigurations: MutableSet<String>
        get() = SetProxy<String>(dslRecorder.createChainedRecorder("resourceConfigurations"))

    override fun resConfigs(config: Collection<String>) {
        dslRecorder.call("resConfigs", listOf(resConfigs()), isVarArgs = false)
    }

    override fun resConfigs(vararg config: String) {
        dslRecorder.call("resConfigs", listOf(config), isVarArgs = true)
    }

    override fun resConfig(config: String) {
        dslRecorder.call("resConfigs", listOf(config), isVarArgs = false)
    }

    override val vectorDrawables: VectorDrawables
        get() = DslProxy.createProxy(
            VectorDrawables::class.java,
            dslRecorder.createChainedRecorder("vectorDrawables")
        )

    override fun vectorDrawables(action: VectorDrawables.() -> Unit) {
        dslRecorder.runNestedBlock(
            name = "vectorDrawables",
            parameters = listOf(),
            instanceProvider = { DslProxy.createProxy(VectorDrawables::class.java, it) }
        ) {
            action(this)
        }
    }

    override var wearAppUnbundled: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.setBoolean("wearAppUnbundled", value, usingIsNotation = false)
        }

    override fun missingDimensionStrategy(dimension: String, requestedValue: String) {
        dslRecorder.call(
            "missingDimensionStrategy",
            listOf(dimension, requestedValue),
            isVarArgs = false
        )
    }

    override fun missingDimensionStrategy(dimension: String, vararg requestedValues: String) {
        dslRecorder.call(
            "missingDimensionStrategy",
            listOf(dimension, requestedValues),
            isVarArgs = true
        )
    }

    override fun missingDimensionStrategy(dimension: String, requestedValues: List<String>) {
        dslRecorder.call(
            "missingDimensionStrategy",
            listOf(dimension, requestedValues),
            isVarArgs = false
        )
    }

    override fun initWith(that: BaseFlavor) {
        throw RuntimeException("Not yet supported")
    }

    override var multiDexKeepProguard: File?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("multiDexKeepProguard", value)
        }
    override var multiDexKeepFile: File?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            dslRecorder.set("multiDexKeepFile", value)
        }
    override val ndk: Ndk
        get() = DslProxy.createProxy(Ndk::class.java, dslRecorder.createChainedRecorder("ndk"))

    override fun ndk(action: Ndk.() -> Unit) {
        dslRecorder.runNestedBlock(
            name = "ndk",
            parameters = listOf(),
            instanceProvider = { DslProxy.createProxy(Ndk::class.java, it) }
        ) {
            action(this)
        }
    }

    override val proguardFiles: MutableList<File>
        get() = ListProxy<File>(dslRecorder.createChainedRecorder("proguardFiles"))

    override fun proguardFile(proguardFile: Any): Any {
        dslRecorder.call("proguardFile", listOf(proguardFile), isVarArgs = false)
        return this
    }

    override fun proguardFiles(vararg files: Any): Any {
        dslRecorder.call("proguardFiles", listOf(files), isVarArgs = true)
        return this
    }

    override fun setProguardFiles(proguardFileIterable: Iterable<*>): Any {
        dslRecorder.call("setProguardFiles", listOf(proguardFileIterable), isVarArgs = false)
        return this
    }

    override val testProguardFiles: MutableList<File>
        get() = ListProxy<File>(dslRecorder.createChainedRecorder("testProguardFiles"))

    override fun testProguardFile(proguardFile: Any): Any {
        dslRecorder.call("testProguardFile", listOf(proguardFile), isVarArgs = false)
        return this
    }

    override fun testProguardFiles(vararg proguardFiles: Any): Any {
        dslRecorder.call("testProguardFiles", listOf(proguardFiles), isVarArgs = true)
        return this
    }

    override val manifestPlaceholders: MutableMap<String, Any>
        get() = MapProxy<String, Any>(dslRecorder.createChainedRecorder("manifestPlaceholders"))

    override fun addManifestPlaceholders(manifestPlaceholders: Map<String, Any>) {
        dslRecorder.call("addManifestPlaceholders", listOf(manifestPlaceholders), isVarArgs = false)
    }

    override fun setManifestPlaceholders(manifestPlaceholders: Map<String, Any>): Void? {
        dslRecorder.call("setManifestPlaceholders", listOf(manifestPlaceholders), isVarArgs = false)
        return null
    }

    override val javaCompileOptions: JavaCompileOptions
        get() = DslProxy.createProxy(
            JavaCompileOptions::class.java,
            dslRecorder.createChainedRecorder("javaCompileOptions")
        )

    override fun javaCompileOptions(action: JavaCompileOptions.() -> Unit) {
        dslRecorder.runNestedBlock(
            name = "javaCompileOptions",
            parameters = listOf(),
            instanceProvider = { DslProxy.createProxy(JavaCompileOptions::class.java, it) }
        ) {
            action(this)
        }
    }

    override val shaders: Shaders
        get() = DslProxy.createProxy(
            Shaders::class.java,
            dslRecorder.createChainedRecorder("shaders")
        )

    override fun shaders(action: Shaders.() -> Unit) {
        dslRecorder.runNestedBlock(
            name = "shaders",
            parameters = listOf(),
            instanceProvider = { DslProxy.createProxy(Shaders::class.java, it) }
        ) {
            action(this)
        }
    }

    override val externalNativeBuild: ExternalNativeBuildFlags
        get() = DslProxy.createProxy(
            ExternalNativeBuildFlags::class.java,
            dslRecorder.createChainedRecorder("externalNativeBuild")
        )

    override fun externalNativeBuild(action: ExternalNativeBuildFlags.() -> Unit) {
        dslRecorder.runNestedBlock(
            name = "externalNativeBuild",
            parameters = listOf(),
            instanceProvider = { DslProxy.createProxy(ExternalNativeBuildFlags::class.java, it) }
        ) {
            action(this)
        }
    }

    override fun buildConfigField(type: String, name: String, value: String) {
        dslRecorder.call("buildConfigField", listOf(type, name, value), isVarArgs = false)
    }

    override fun resValue(type: String, name: String, value: String) {
        dslRecorder.call("resValue", listOf(type, name, value), isVarArgs = false)
    }

    override val optimization: Optimization
        get() = DslProxy.createProxy(
            Optimization::class.java,
            dslRecorder.createChainedRecorder("optimization")
        )

    override fun optimization(action: Optimization.() -> Unit) {
        dslRecorder.runNestedBlock(
            name = "optimization",
            parameters = listOf(),
            instanceProvider = { DslProxy.createProxy(Optimization::class.java, it) }
        ) {
            action(this)
        }
    }

    override fun getExtensions(): ExtensionContainer {
        throw RuntimeException("Not yet supported")
    }
}
