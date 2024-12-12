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

import com.android.build.api.dsl.AiPackExtension
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.dsl.DefaultDslContentHolder
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.DelayedGradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.DirectGradleProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import java.nio.file.Path

/*
 * Support for Android AI Pack in the [GradleRule] fixture
 */

/**
 * Specialized interface for [GenericProjectDefinition]
 */
interface AiPackDefinition: GradleProjectDefinition {
    val aiPack: AiPackExtension
    fun aiPack(action: AiPackExtension.() -> Unit)

    /** executes the lambda that adds/updates/removes files from the project */
    fun files(action: GradleProjectFiles.() -> Unit)
}

/**
 * Implementation of [AiPackDefinition]
 */
internal class AiPackDefinitionImpl(path: String) : GradleProjectDefinitionImpl(path),
    AiPackDefinition {

    init {
        applyPlugin(PluginType.ANDROID_AI_PACK)
    }

    override val files: GradleProjectFiles = DelayedGradleProjectFiles()

    override fun files (action: GradleProjectFiles.() -> Unit) {
        action(files)
    }

    private val contentHolder = DefaultDslContentHolder()

    override val aiPack: AiPackExtension =
        DslProxy.createProxy(
            AiPackExtension::class.java,
            contentHolder,
        )

    override fun aiPack(action: AiPackExtension.() -> Unit) {
        action(aiPack)
    }

    override fun writeExtension(writer: BuildWriter, location: Path) {
        writer.apply {
            block("aiPack") {
                contentHolder.writeContent(this)
            }

            emptyLine()
        }
    }
}

/**
 * Specialized interface for AI Pack [AndroidProject] to use in the test
 */
interface AiPackProject: GradleProject<AiPackDefinition> {
    /** the object that allows to add/update/remove files from the project */
    val files: GradleProjectFiles
}

/**
 * Implementation of [AndroidProject]
 */
internal class AiPackImpl(
    location: Path,
    projectDefinition: AiPackDefinition,
) : GradleProjectImpl<AiPackDefinition>(
    location,
    projectDefinition,
), AiPackProject {

    override val files: GradleProjectFiles = DirectGradleProjectFilesImpl(location)

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): AiPackProject =
        ReversibleAiPackProject(this, projectModification)
}

/**
 * Reversible version of [AiPackProject]
 */
internal class ReversibleAiPackProject(
    parentProject: AiPackProject,
    projectModification: TemporaryProjectModification
) : ReversibleGradleProject<AiPackProject, AiPackDefinition>(
    parentProject,
), AiPackProject {
    override val files: GradleProjectFiles = ReversibleProjectFiles(projectModification, parentProject.location)
}
