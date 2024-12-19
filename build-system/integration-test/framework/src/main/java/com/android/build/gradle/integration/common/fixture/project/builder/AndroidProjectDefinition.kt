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
import com.android.build.gradle.integration.common.fixture.project.builder.kotlin.KotlinExtension
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
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

    val android: ExtensionT
    fun android(action: ExtensionT.() -> Unit)

    fun kotlin(action: KotlinExtension.() -> Unit)

    override val files: AndroidProjectFiles
    fun files(action: AndroidProjectFiles.() -> Unit)
}

/**
 * Base implementation for all Android project types.
 */
internal abstract class AndroidProjectDefinitionImpl<T>(
    path: String
): GradleProjectDefinitionImpl(path), AndroidProjectDefinition<T> {

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

    protected val contentHolder = DefaultDslContentHolder()

    protected open fun initDefaultValues(extension: T) {
        if (extension is CommonExtension<*,*,*,*,*,*>) {
            extension.namespace = "pkg.name${path.replace(':', '.')}"
            extension.compileSdk = GradleTestProject.DEFAULT_COMPILE_SDK_VERSION.toInt()
        } else {
            throw RuntimeException("Unsupported android extension type. Override initDefaultValues() in the specific AndroidProjectDefinition implementation!")
        }
    }

    override fun android(action: T.() -> Unit) {
        action(android)
    }

    override fun kotlin(action: KotlinExtension.() -> Unit) {
        if (!hasPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN))
            throw RuntimeException("Cannot configure kotlin without plugin ANDROID_BUILT_IN_KOTLIN")

        contentHolder.runNestedBlock(
            "kotlin",
            parameters = listOf(),
            KotlinExtension::class.java
        ) {
            action(this)
        }
    }

    override fun writeExtension(writer: BuildWriter, location: Path) {
        writer.apply {
            block("android") {
                contentHolder.writeContent(this)
            }

            emptyLine()
        }
    }
}
