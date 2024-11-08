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
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.dsl.DynamicFeatureExtension
import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.dsl.DefaultDslContentHolder
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.AndroidProject
import com.android.build.gradle.integration.common.fixture.testprojects.DependenciesBuilder
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType

/**
 * Represents an Android Gradle Project that can be configured before being written on disk
 */
interface AndroidProjectDefinition<T: CommonExtension<*,*,*,*,*,*>>: BaseGradleProjectDefinition {
    val android: T
    fun android(action: T.() -> Unit)

    override val layout: AndroidProjectLayout
    fun layout(action: AndroidProjectLayout.() -> Unit)
}

/**
 * Base implementation for all Android project types.
 */
internal abstract class AndroidProjectDefinitionImpl<T : CommonExtension<*, *, *, *, *, *>>(
    path: String
): BaseGradleProjectDefinitionImpl(path), AndroidProjectDefinition<T> {

    override val layout: AndroidProjectLayout = AndroidProjectLayoutImpl(this::namespace)

    override fun layout(action: AndroidProjectLayout.() -> Unit) {
        action(layout)
    }

    internal val namespace: String
        get() = android.namespace  ?: throw RuntimeException("Namespace has not been set yet!")

    protected val contentHolder = DefaultDslContentHolder()

    protected fun initDefaultValues(extension: T) {
        val pkgName = if (path == ":") {
            "pkg.name"
        } else {
            "pkg.name${path.replace(':', '.')}"
        }
        extension.namespace = pkgName
        extension.compileSdk = GradleTestProject.DEFAULT_COMPILE_SDK_VERSION.toInt()
    }

    override fun android(action: T.() -> Unit) {
        action(android)
    }

    override fun writeAndroid(writer: BuildWriter) {
        writer.apply {
            block("android") {
                contentHolder.writeContent(this)
            }
        }
    }

    internal fun asGradleProject(): GradleProjectDefinition {
        return AndroidProjectDefinitionWrapper(this)
    }
}

/**
 * An Android Application Project
 */
internal class AndroidApplicationDefinitionImpl(path: String): AndroidProjectDefinitionImpl<ApplicationExtension>(path) {
    init {
        plugins.add(PluginType.ANDROID_APP)
    }

    override val android: ApplicationExtension =
        DslProxy.createProxy(ApplicationExtension::class.java, contentHolder).also {
            initDefaultValues(it)
        }
}

/**
 * An Android Library Project
 */
internal class AndroidLibraryDefinitionImpl(path: String): AndroidProjectDefinitionImpl<LibraryExtension>(path) {
    init {
        plugins.add(PluginType.ANDROID_LIB)
    }

    override val android: LibraryExtension =
        DslProxy.createProxy(LibraryExtension::class.java, contentHolder).also {
            initDefaultValues(it)
        }
}

/**
 * An Android Feature Project
 */
internal class AndroidDynamicFeatureDefinitionImpl(path: String): AndroidProjectDefinitionImpl<DynamicFeatureExtension>(path) {
    init {
        plugins.add(PluginType.ANDROID_DYNAMIC_FEATURE)
    }

    override val android: DynamicFeatureExtension =
        DslProxy.createProxy(DynamicFeatureExtension::class.java, contentHolder).also {
            initDefaultValues(it)
        }
}

/**
 * Wraps a [AndroidProjectDefinition] into a [GradleProjectDefinition]
 *
 * This can be usd when manipulating project in their most basic form.
 */
internal class AndroidProjectDefinitionWrapper(
    private val androidProject: AndroidProjectDefinition<*>
): BaseGradleProjectDefinition by androidProject, GradleProjectDefinition {

    override fun layout(action: GradleProjectLayout.() -> Unit) {
        androidProject.layout(action)
    }
}
