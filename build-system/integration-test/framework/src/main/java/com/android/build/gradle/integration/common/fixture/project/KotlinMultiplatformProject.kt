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

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.dsl.DefaultDslRecorder
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.builder.kotlin.KotlinMultiplatformExtension
import java.nio.file.Path

/*
 * Support for Kotlin Multiplatform project in the [GradleRule] fixture
 */

/**
 * Specialized interface for [GenericProjectDefinition]
 */
interface KotlinMultiplatformDefinition: GradleProjectDefinition {

    /**
     * the Android DSL object. This is only available if the [PluginType.ANDROID_KMP_LIBRARY] is
     * applied
     */
    val androidLibrary: KotlinMultiplatformAndroidLibraryTarget
    /**
     * configures the Android DSL object. This is only available if the
     * [PluginType.ANDROID_KMP_LIBRARY] is applied
     */
    fun androidLibrary(action: KotlinMultiplatformAndroidLibraryTarget.() -> Unit)

    val kotlin: KotlinMultiplatformExtension
    fun kotlin(action: KotlinMultiplatformExtension.() -> Unit)

    /** executes the lambda that adds/updates/removes files from the project */
    fun files(action: GradleProjectFiles.() -> Unit)
}

/**
 * Implementation of [KotlinMultiplatformDefinition]
 */
internal class KotlinMultiplatformDefinitionImpl(
    path: String,
) : GradleProjectDefinitionImpl(path),
    KotlinMultiplatformDefinition {

    init {
        applyPlugin(PluginType.KOTLIN_MPP)
    }

    override fun files (action: GradleProjectFiles.() -> Unit) {
        action(files)
    }

    override val androidLibrary: KotlinMultiplatformAndroidLibraryTarget =
        DslProxy.createProxy(
            KotlinMultiplatformAndroidLibraryTarget::class.java,
            dslRecorder,
        )

    override fun androidLibrary(action: KotlinMultiplatformAndroidLibraryTarget.() -> Unit) {
        if (!hasPlugin(PluginType.ANDROID_KMP_LIBRARY))
            throw RuntimeException("ANDROID_KMP_PLUGIN not applied")

        action(androidLibrary)
    }

    private val kotlinDslRecorder = DefaultDslRecorder()

    override val kotlin: KotlinMultiplatformExtension =
        DslProxy.createProxy(
            KotlinMultiplatformExtension::class.java,
            kotlinDslRecorder
        )

    override fun kotlin(action: KotlinMultiplatformExtension.() -> Unit) {
        action(kotlin)
    }

    override fun writeExtension(writer: BuildWriter, location: Path) {
        writer.apply {
            block("kotlin") {
                if (hasPlugin(PluginType.ANDROID_KMP_LIBRARY)) {
                    block("androidLibrary") {
                        dslRecorder.writeContent(this)
                    }
                }
                kotlinDslRecorder.writeContent(this)
            }

            emptyLine()
        }
    }
}

/**
 * Specialized interface for KMP [GradleProject] to use in the test
 *
 * While it implements [GeneratesAar] this will only find AARs if the android kmp library plugin
 * is applied (obviously)
 */
interface KotlinMultiplatformProject: GradleProject<KotlinMultiplatformDefinition>, GeneratesAar

/**
 * Implementation of [GradleProject]
 */
internal class KotlinMultiplatformProjectImpl(
    location: Path,
    projectDefinition: KotlinMultiplatformDefinition,
) : GradleProjectImpl<KotlinMultiplatformDefinition>(
    location,
    projectDefinition,
), KotlinMultiplatformProject, GeneratesAar by GeneratesAarDelegate(projectDefinition.path, location) {

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): KotlinMultiplatformProject =
        ReversibleKotlinMultiplatformProject(this, projectModification)
}

/**
 * Reversible version of [KotlinMultiplatformProject]
 */
internal class ReversibleKotlinMultiplatformProject(
    parentProject: KotlinMultiplatformProject,
    projectModification: TemporaryProjectModification
) : ReversibleGradleProject<KotlinMultiplatformProject, KotlinMultiplatformDefinition>(
    parentProject,
    projectModification
), KotlinMultiplatformProject, GeneratesAar by GeneratesAarFromParentDelegate(parentProject)
