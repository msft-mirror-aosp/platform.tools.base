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

import com.android.build.api.dsl.AssetPackBundleExtension
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleDefinitionDsl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import java.nio.file.Path

/*
 * Support for Android Asset Pack Bundle in the [GradleRule] fixture
 */

/**
 * Specialized interface for [GenericProjectDefinition]
 */
@GradleDefinitionDsl
interface AssetPackBundleDefinition: GradleProjectDefinition {
    val bundle: AssetPackBundleExtension
    fun bundle(action: AssetPackBundleExtension.() -> Unit)

    /** executes the lambda that adds/updates/removes files from the project */
    fun files(action: GradleProjectFiles.() -> Unit)
}

/**
 * Implementation of [AssetPackDefinition]
 */
internal class AssetPackBundleDefinitionImpl(
    path: String,
    createMinimumProject: Boolean,
) : GradleProjectDefinitionImpl(path),
    AssetPackBundleDefinition {

    init {
        applyPlugin(PluginType.ANDROID_ASSET_PACK_BUNDLE)
    }

    override fun files (action: GradleProjectFiles.() -> Unit) {
        action(files)
    }

    override val bundle: AssetPackBundleExtension =
        DslProxy.createProxy(
            AssetPackBundleExtension::class.java,
            dslRecorder,
        ).also {
            if (createMinimumProject) {
                it.applicationId = "pkg.name${path.replace(':', '.')}"
                it.compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
            }
        }

    override fun bundle(action: AssetPackBundleExtension.() -> Unit) {
        action(bundle)
    }

    override fun writeExtension(writer: BuildWriter, location: Path) {
        writer.apply {
            block("bundle") {
                dslRecorder.writeContent(this)
            }

            emptyLine()
        }
    }
}

/**
 * Specialized interface for AssetPackBundle [GradleProject] to use in the test
 */
interface AssetPackBundleProject: GradleProject<AssetPackBundleDefinition>, GeneratesAab

/**
 * Implementation of [AssetPackBundleProject]
 */
internal class AssetPackBundleImpl(
    location: Path,
    projectDefinition: AssetPackBundleDefinition,
) : GradleProjectImpl<AssetPackBundleDefinition>(
    location,
    projectDefinition,
), AssetPackBundleProject, GeneratesAab by GeneratesAabDelegate(location) {

    override fun getReversibleInstance(
        projectModification: TemporaryProjectModification
    ): AssetPackBundleProject =
        ReversibleAssetPackBundleProject(this, projectModification)
}

/**
 * Reversible version of [AssetPackBundleProject]
 */
internal class ReversibleAssetPackBundleProject(
    parentProject: AssetPackBundleProject,
    projectModification: TemporaryProjectModification
) : ReversibleGradleProject<AssetPackBundleProject, AssetPackBundleDefinition>(
    parentProject,
    projectModification,
), AssetPackBundleProject,
    GeneratesAab by GeneratesAabFromParentDelegate(parentProject)
