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

package com.android.build.gradle.integration.common.fixture.project

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryExtension
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.dsl.DefaultDslContentHolder
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.builder.kotlin.KotlinMultiplatformExtension
import java.nio.file.Path

/*
 * Support for Android AI Pack in the [GradleRule] fixture
 */

/**
 * Specialized interface for [GenericProjectDefinition]
 */
interface KotlinMultiplatformAndroidDefinition: GradleProjectDefinition {
    val androidLibrary: KotlinMultiplatformAndroidLibraryExtension
    fun androidLibrary(action: KotlinMultiplatformAndroidLibraryExtension.() -> Unit)

    val kotlin: KotlinMultiplatformExtension
    fun kotlin(action: KotlinMultiplatformExtension.() -> Unit)

    /** executes the lambda that adds/updates/removes files from the project */
    fun files(action: GradleProjectFiles.() -> Unit)
}

/**
 * Implementation of [KotlinMultiplatformAndroidDefinition]
 */
internal class KotlinMultiplatformAndroidDefinitionImpl(
    path: String,
    createMinimumProject: Boolean
) : GradleProjectDefinitionImpl(path),
    KotlinMultiplatformAndroidDefinition {

    init {
        applyPlugin(PluginType.KOTLIN_MPP)
        applyPlugin(PluginType.ANDROID_KMP_LIBRARY)
    }

    override fun files (action: GradleProjectFiles.() -> Unit) {
        action(files)
    }

    override val androidLibrary: KotlinMultiplatformAndroidLibraryExtension =
        DslProxy.createProxy(
            KotlinMultiplatformAndroidLibraryExtension::class.java,
            contentHolder,
        ).also {
            if (createMinimumProject) {
                it.namespace = "pkg.name${path.replace(':', '.')}"
                it.compileSdk = GradleTestProject.DEFAULT_COMPILE_SDK_VERSION.toInt()
            }
        }

    override fun androidLibrary(action: KotlinMultiplatformAndroidLibraryExtension.() -> Unit) {
        action(androidLibrary)
    }

    private val kotlinContentHolder = DefaultDslContentHolder()

    override val kotlin: KotlinMultiplatformExtension =
        DslProxy.createProxy(
            KotlinMultiplatformExtension::class.java,
            kotlinContentHolder
        )

    override fun kotlin(action: KotlinMultiplatformExtension.() -> Unit) {
        action(kotlin)
    }

    override fun writeExtension(writer: BuildWriter, location: Path) {
        writer.apply {
            block("kotlin") {
                block("androidLibrary") {
                    contentHolder.writeContent(this)
                }
                kotlinContentHolder.writeContent(this)
            }

            emptyLine()
        }
    }
}

/**
 * Specialized interface for AI Pack [AndroidProject] to use in the test
 */
interface KotlinMultiplatformAndroid: GradleProject<KotlinMultiplatformAndroidDefinition>, GeneratesAar

/**
 * Implementation of [AndroidProject]
 */
internal class KotlinMultiplatformAndroidImpl(
    location: Path,
    projectDefinition: KotlinMultiplatformAndroidDefinition,
) : GradleProjectImpl<KotlinMultiplatformAndroidDefinition>(
    location,
    projectDefinition,
), KotlinMultiplatformAndroid, GeneratesAar by GeneratesAarDelegate(location) {

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): KotlinMultiplatformAndroid =
        ReversibleKotlinMultiplatformAndroid(this, projectModification)
}

/**
 * Reversible version of [AiPackProject]
 */
internal class ReversibleKotlinMultiplatformAndroid(
    parentProject: KotlinMultiplatformAndroid,
    projectModification: TemporaryProjectModification
) : ReversibleGradleProject<KotlinMultiplatformAndroid, KotlinMultiplatformAndroidDefinition>(
    parentProject,
    projectModification
), KotlinMultiplatformAndroid, GeneratesAar by GeneratesAarFromParentDelegate(parentProject)
