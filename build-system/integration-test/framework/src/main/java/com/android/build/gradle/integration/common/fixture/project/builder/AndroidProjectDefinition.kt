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

import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.dsl.DefaultDslContentHolder
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.kotlin.KotlinExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions
import java.nio.file.Path

/**
 * Represents an Android Gradle Project that can be configured before being written on disk
 */
interface AndroidProjectDefinition<ExtensionT>: GradleProjectDefinition {
    companion object {
        const val DEFAULT_APP_PATH = ":app"
        const val DEFAULT_LIB_PATH = ":lib"
        const val DEFAULT_FEATURE_PATH = ":feature"
        const val DEFAULT_TEST_PATH = ":test"
    }

    /**
     * the android extension
     */
    val android: ExtensionT

    /**
     * Method to configure the android extension
     */
    fun android(action: ExtensionT.() -> Unit)

    /**
     * Resets the content of the Android (and legacy kotlin) DSL.
     */
    fun resetAndroidDsl()

    /**
     * Method to configure the new kotlin extension.
     *
     * When using [PluginType.ANDROID_BUILT_IN_KOTLIN] you must use this instead of
     * [legacyKotlin]
     */
    fun kotlin(action: KotlinExtension.() -> Unit)

    /**
     * Resets the content of the Kotlin DSL
     */
    fun resetKotlinDsl()

    /**
     * Method to configure the old Kotlin extension that is added as `android.kotlinOptions`.
     *
     * This requires [PluginType.KOTLIN_ANDROID] to be applied
     */
    fun legacyKotlin(@Suppress("DEPRECATION") action: KotlinJvmOptions.() -> Unit)

    /** the object that allows to add/update/remove files from the project */
    override val files: AndroidProjectFiles

    /**
     * configures the files for this project.
     */
    fun files(action: AndroidProjectFiles.() -> Unit)
}

/**
 * Base implementation for all Android project types.
 */
internal abstract class AndroidProjectDefinitionImpl<ExtensionT>(
    path: String
): GradleProjectDefinitionImpl(path), AndroidProjectDefinition<ExtensionT> {

    // For kotlin, because we want this to be separate from the android extension, we have
    // to create a separate content holder and proxy.
    // custom content holder for kotlin so that it's not under the android one
    private val kotlinContentHolder = DefaultDslContentHolder()
    private val kotlinExtension: KotlinExtension = DslProxy.createProxy(KotlinExtension::class.java, kotlinContentHolder)
    private var kotlinActionRan = false

    override val files: AndroidProjectFiles = DelayedAndroidProjectFiles(this::namespace)

    override fun files(action: AndroidProjectFiles.() -> Unit) {
        action(files)
    }

    internal open val namespace: String
        get() {
            val extension = android
            if (extension is CommonExtension<*,*,*,*,*,*>) {
                return extension.namespace ?: throw RuntimeException("Namespace has not been set yet!")
            }

            throw RuntimeException("Unsupported android extension type. Override namespace getter in the specific AndroidProjectDefinition implementation!")
        }

    protected open fun initDefaultValues(extension: ExtensionT) {
        if (extension is CommonExtension<*,*,*,*,*,*>) {
            extension.namespace = "pkg.name${path.replace(':', '.')}"
            extension.compileSdk = GradleTestProject.DEFAULT_COMPILE_SDK_VERSION.toInt()
        } else {
            throw RuntimeException("Unsupported android extension type. Override initDefaultValues() in the specific AndroidProjectDefinition implementation!")
        }
    }

    override fun android(action: ExtensionT.() -> Unit) {
        action(android)
    }

    override fun resetAndroidDsl() {
        contentHolder.clear()
    }

    override fun kotlin(action: KotlinExtension.() -> Unit) {
        if (!hasPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN) && !hasPlugin(PluginType.KOTLIN_ANDROID))
            throw RuntimeException("Cannot configure kotlin without plugin ANDROID_BUILT_IN_KOTLIN or KOTLIN_ANDROID")

        kotlinActionRan = true
        action(kotlinExtension)
    }

    override fun resetKotlinDsl() {
        kotlinContentHolder.clear()
    }

    override fun legacyKotlin(@Suppress("DEPRECATION") action: KotlinJvmOptions.() -> Unit) {
        if (!hasPlugin(PluginType.KOTLIN_ANDROID))
            throw RuntimeException("Cannot configure legacyKotlin without plugin KOTLIN_ANDROID")
        @Suppress("DEPRECATION")
        contentHolder.runNestedBlock(
            name = "kotlinOptions",
            parameters = listOf(),
            instanceProvider = { DslProxy.createProxy(KotlinJvmOptions::class.java, it) }
        ) {
            action(this)
        }
    }

    override fun writeExtension(writer: BuildWriter, location: Path) {
        writer.apply {
            block("android") {
                contentHolder.writeContent(this)
            }

            if (kotlinActionRan) {
                emptyLine()
                block("kotlin") {
                    kotlinContentHolder.writeContent(this)
                }
            }

            emptyLine()
        }
    }
}
