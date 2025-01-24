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
    protected val contentHolder: DslContentHolder
): ProductFlavor {

    override var dimension: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("dimension", value)
        }

    override fun setDimension(dimension: String?): Void? {
        contentHolder.set("dimension", dimension)
        return null
    }

    override val matchingFallbacks: MutableList<String>
        get() = ListProxy<String>(contentHolder.createChainedContentHolder("matchingFallbacks"))

    override fun setMatchingFallbacks(vararg fallbacks: String) {
        contentHolder.call("setMatchingFallbacks", listOf(fallbacks), isVarArgs = true)
    }

    override fun setMatchingFallbacks(fallbacks: List<String>) {
        contentHolder.call("setMatchingFallbacks", fallbacks, isVarArgs = false)
    }

    override fun getName(): String {
        throw RuntimeException("Not yet supported")
    }

    override var testApplicationId: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("testApplicationId", value)
        }
    override var minSdk: Int?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("minSdk", value)
        }

    override fun setMinSdkVersion(minSdkVersion: Int) {
        contentHolder.call("setMinSdkVersion", listOf(minSdkVersion), isVarArgs = false)
    }

    override fun setMinSdkVersion(minSdkVersion: String?) {
        contentHolder.call("setMinSdkVersion", listOf(minSdkVersion), isVarArgs = false)
    }

    override fun minSdkVersion(minSdkVersion: Int) {
        contentHolder.call("minSdkVersion", listOf(minSdkVersion), isVarArgs = false)
    }

    override fun minSdkVersion(minSdkVersion: String?) {
        contentHolder.call("minSdkVersion", listOf(minSdkVersion), isVarArgs = false)
    }

    override var minSdkPreview: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("minSdkVersion", value)
        }
    override var renderscriptTargetApi: Int?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("renderscriptTargetApi", value)
        }
    override var renderscriptSupportModeEnabled: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("renderscriptSupportModeEnabled", value)
        }
    override var renderscriptSupportModeBlasEnabled: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("renderscriptSupportModeBlasEnabled", value)
        }
    override var renderscriptNdkModeEnabled: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("renderscriptNdkModeEnabled", value)
        }
    override var testInstrumentationRunner: String?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("testInstrumentationRunner", value)
        }
    override val testInstrumentationRunnerArguments: MutableMap<String, String>
        get() = MapProxy<String, String>(contentHolder.createChainedContentHolder("testInstrumentationRunnerArguments"))

    override fun testInstrumentationRunnerArgument(key: String, value: String) {
        contentHolder.call(
            "testInstrumentationRunnerArgument",
            listOf(key, value),
            isVarArgs = false
        )
    }

    override fun setTestInstrumentationRunnerArguments(testInstrumentationRunnerArguments: MutableMap<String, String>): Any? {
        contentHolder.call(
            "setTestInstrumentationRunnerArguments",
            listOf(testInstrumentationRunnerArguments),
            isVarArgs = false
        )
        return this
    }

    override fun testInstrumentationRunnerArguments(args: Map<String, String>) {
        contentHolder.call(
            "testInstrumentationRunnerArguments",
            listOf(args),
            isVarArgs = false
        )
    }

    override var testHandleProfiling: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.setBoolean("testHandleProfiling", value, usingIsNotation = false)
        }

    override fun setTestHandleProfiling(testHandleProfiling: Boolean): Any? {
        contentHolder.call("setTestHandleProfiling", listOf(testHandleProfiling), isVarArgs = false)
        return this
    }

    override var testFunctionalTest: Boolean?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.setBoolean("testFunctionalTest", value, usingIsNotation = false)
        }

    override fun setTestFunctionalTest(testFunctionalTest: Boolean): Any? {
        contentHolder.call("setTestFunctionalTest", listOf(testFunctionalTest), isVarArgs = false)
        return this
    }

    override val resourceConfigurations: MutableSet<String>
        get() = SetProxy<String>(contentHolder.createChainedContentHolder("resourceConfigurations"))

    override fun resConfigs(config: Collection<String>) {
        contentHolder.call("resConfigs", listOf(resConfigs()), isVarArgs = false)
    }

    override fun resConfigs(vararg config: String) {
        contentHolder.call("resConfigs", listOf(config), isVarArgs = true)
    }

    override fun resConfig(config: String) {
        contentHolder.call("resConfigs", listOf(config), isVarArgs = false)
    }

    override val vectorDrawables: VectorDrawables
        get() = DslProxy.createProxy(
            VectorDrawables::class.java,
            contentHolder.createChainedContentHolder("vectorDrawables")
        )

    override fun vectorDrawables(action: VectorDrawables.() -> Unit) {
        contentHolder.runNestedBlock(
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
            contentHolder.setBoolean("wearAppUnbundled", value, usingIsNotation = false)
        }

    override fun missingDimensionStrategy(dimension: String, requestedValue: String) {
        contentHolder.call(
            "missingDimensionStrategy",
            listOf(dimension, requestedValue),
            isVarArgs = false
        )
    }

    override fun missingDimensionStrategy(dimension: String, vararg requestedValues: String) {
        contentHolder.call(
            "missingDimensionStrategy",
            listOf(dimension, requestedValues),
            isVarArgs = true
        )
    }

    override fun missingDimensionStrategy(dimension: String, requestedValues: List<String>) {
        contentHolder.call(
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
            contentHolder.set("multiDexKeepProguard", value)
        }
    override var multiDexKeepFile: File?
        get() = throw RuntimeException("Not yet supported")
        set(value) {
            contentHolder.set("multiDexKeepFile", value)
        }
    override val ndk: Ndk
        get() = DslProxy.createProxy(Ndk::class.java, contentHolder.createChainedContentHolder("ndk"))

    override fun ndk(action: Ndk.() -> Unit) {
        contentHolder.runNestedBlock(
            name = "ndk",
            parameters = listOf(),
            instanceProvider = { DslProxy.createProxy(Ndk::class.java, it) }
        ) {
            action(this)
        }
    }

    override val proguardFiles: MutableList<File>
        get() = ListProxy<File>(contentHolder.createChainedContentHolder("proguardFiles"))

    override fun proguardFile(proguardFile: Any): Any {
        contentHolder.call("proguardFile", listOf(proguardFile), isVarArgs = false)
        return this
    }

    override fun proguardFiles(vararg files: Any): Any {
        contentHolder.call("proguardFiles", listOf(files), isVarArgs = true)
        return this
    }

    override fun setProguardFiles(proguardFileIterable: Iterable<*>): Any {
        contentHolder.call("setProguardFiles", listOf(proguardFileIterable), isVarArgs = false)
        return this
    }

    override val testProguardFiles: MutableList<File>
        get() = ListProxy<File>(contentHolder.createChainedContentHolder("testProguardFiles"))

    override fun testProguardFile(proguardFile: Any): Any {
        contentHolder.call("testProguardFile", listOf(proguardFile), isVarArgs = false)
        return this
    }

    override fun testProguardFiles(vararg proguardFiles: Any): Any {
        contentHolder.call("testProguardFiles", listOf(proguardFiles), isVarArgs = true)
        return this
    }

    override val manifestPlaceholders: MutableMap<String, Any>
        get() = MapProxy<String, Any>(contentHolder.createChainedContentHolder("manifestPlaceholders"))

    override fun addManifestPlaceholders(manifestPlaceholders: Map<String, Any>) {
        contentHolder.call("addManifestPlaceholders", listOf(manifestPlaceholders), isVarArgs = false)
    }

    override fun setManifestPlaceholders(manifestPlaceholders: Map<String, Any>): Void? {
        contentHolder.call("setManifestPlaceholders", listOf(manifestPlaceholders), isVarArgs = false)
        return null
    }

    override val javaCompileOptions: JavaCompileOptions
        get() = DslProxy.createProxy(
            JavaCompileOptions::class.java,
            contentHolder.createChainedContentHolder("javaCompileOptions")
        )

    override fun javaCompileOptions(action: JavaCompileOptions.() -> Unit) {
        contentHolder.runNestedBlock(
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
            contentHolder.createChainedContentHolder("shaders")
        )

    override fun shaders(action: Shaders.() -> Unit) {
        contentHolder.runNestedBlock(
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
            contentHolder.createChainedContentHolder("externalNativeBuild")
        )

    override fun externalNativeBuild(action: ExternalNativeBuildFlags.() -> Unit) {
        contentHolder.runNestedBlock(
            name = "externalNativeBuild",
            parameters = listOf(),
            instanceProvider = { DslProxy.createProxy(ExternalNativeBuildFlags::class.java, it) }
        ) {
            action(this)
        }
    }

    override fun buildConfigField(type: String, name: String, value: String) {
        contentHolder.call("buildConfigField", listOf(type, name, value), isVarArgs = false)
    }

    override fun resValue(type: String, name: String, value: String) {
        contentHolder.call("resValue", listOf(type, name, value), isVarArgs = false)
    }

    override val optimization: Optimization
        get() = DslProxy.createProxy(
            Optimization::class.java,
            contentHolder.createChainedContentHolder("optimization")
        )

    override fun optimization(action: Optimization.() -> Unit) {
        contentHolder.runNestedBlock(
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
