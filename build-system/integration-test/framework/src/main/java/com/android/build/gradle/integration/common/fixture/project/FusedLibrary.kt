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

import com.android.build.api.dsl.FusedLibraryExtension
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.dsl.DefaultDslContentHolder
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.DelayedGradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.DirectGradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.AarSubject
import com.android.build.gradle.internal.fusedlibrary.FusedLibraryConstants
import com.android.testutils.apk.Aar
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/*
 * Support for Android Fused Library in the [GradleRule] fixture
 */

/**
 * Specialized interface for [GenericProjectDefinition]
 */
interface FusedLibraryDefinition: GradleProjectDefinition {
    val androidFusedLibrary: FusedLibraryExtension
    fun androidFusedLibrary(action: FusedLibraryExtension.() -> Unit)

    /** executes the lambda that adds/updates/removes files from the project */
    fun files(action: GradleProjectFiles.() -> Unit)
}

/**
 * Implementation of [FusedLibraryDefinition]
 */
internal class FusedLibraryDefinitionImpl(
    path: String,
    createMinimumProject: Boolean
) : GradleProjectDefinitionImpl(path),
    FusedLibraryDefinition {

    init {
        applyPlugin(PluginType.FUSED_LIBRARY)
    }

    override val files: GradleProjectFiles = DelayedGradleProjectFiles()

    override fun files (action: GradleProjectFiles.() -> Unit) {
        action(files)
    }

    override val androidFusedLibrary: FusedLibraryExtension =
        DslProxy.createProxy(
            FusedLibraryExtension::class.java,
            contentHolder,
        ).also {
            if (createMinimumProject) {
                it.namespace = "pkg.name${path.replace(':', '.')}"
            }
        }

    override fun androidFusedLibrary(action: FusedLibraryExtension.() -> Unit) {
        action(androidFusedLibrary)
    }

    override fun writeExtension(writer: BuildWriter, location: Path) {
        writer.apply {
            block(FusedLibraryConstants.EXTENSION_NAME) {
                contentHolder.writeContent(this)
            }

            emptyLine()
        }
    }
}

/**
 * Specialized interface for FusedLibrary [GradleProject] to use in the test
 */
interface FusedLibraryProject: BaseAndroidProject<FusedLibraryDefinition>, GeneratesAar

/**
 * Implementation of [AndroidProject]
 */
internal class FusedLibraryImpl(
    location: Path,
    projectDefinition: FusedLibraryDefinition,
) : BaseAndroidProjectImpl<FusedLibraryDefinition>(
    location,
    projectDefinition,
), FusedLibraryProject, GeneratesAar by GeneratesAarDelegate(location) {

    override val files: GradleProjectFiles = DirectGradleProjectFiles(location)

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): FusedLibraryProject =
        ReversibleFusedLibraryProject(this, projectModification)
}

/**
 * Reversible version of [FusedLibraryProject]
 */
internal class ReversibleFusedLibraryProject(
    parentProject: FusedLibraryProject,
    projectModification: TemporaryProjectModification
) : BaseReversibleAndroidProjectImpl<FusedLibraryProject, FusedLibraryDefinition>(
    parentProject,
    projectModification
), FusedLibraryProject, GeneratesAar by GeneratesAarFromParentDelegate(parentProject)
