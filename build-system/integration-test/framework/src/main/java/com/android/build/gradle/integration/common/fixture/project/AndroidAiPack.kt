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
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.dsl.DefaultDslContentHolder
import com.android.build.gradle.integration.common.fixture.dsl.DslProxy
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.BaseGradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.BaseGradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.DirectGradleProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.truth.ApkSubject
import com.android.testutils.apk.Apk
import java.io.File
import java.nio.file.Path

/*
 * Support for Android AI Pack in the [GradleRule] fixture
 */

/**
 * Specialized interface for [GradleProjectDefinition]
 */
interface AiPackDefinition: BaseGradleProjectDefinition {
    val aiPack: AiPackExtension
    fun aiPack(action: AiPackExtension.() -> Unit)

    /** executes the lambda that adds/updates/removes files from the project */
    fun files(action: GradleProjectFiles.() -> Unit)
}

/**
 * Implementation of [AiPackDefinition]
 */
internal class AndroidAiPackDefinitionImpl(path: String) : BaseGradleProjectDefinitionImpl(path),
    AiPackDefinition {

    init {
        applyPlugin(PluginType.ANDROID_AI_PACK)
    }

    override val files: GradleProjectFiles = GradleProjectFilesImpl()

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

    override fun writExtension(writer: BuildWriter) {
        writer.apply {
            block("aiPack") {
                contentHolder.writeContent(this)
            }
        }
    }
}

/**
 * Specialized interface for AI Pack [AndroidProject] to use in the test
 */
interface AndroidAiPackProject: BaseGradleProject<AiPackDefinition> {
    /** the object that allows to add/update/remove files from the project */
    val files: GradleProjectFiles
}

/**
 * Implementation of [AndroidProject]
 */
internal class AndroidAiPackImpl(
    location: Path,
    projectDefinition: AiPackDefinition,
    buildWriter: () -> BuildWriter,
    parentBuild: GradleBuildDefinitionImpl,
) : BaseGradleProjectImpl<AiPackDefinition>(location, projectDefinition,buildWriter, parentBuild),
    AndroidAiPackProject {

    override val files: GradleProjectFiles = DirectGradleProjectFilesImpl(location)

    override fun getReversibleInstance(projectModification: TemporaryProjectModification): AndroidAiPackProject =
        ReversibleAndroidAiPackProject(this, projectModification)
}

/**
 * Reversible version of [AndroidAiPackProject]
 */
internal class ReversibleAndroidAiPackProject(
    parentProject: AndroidAiPackProject,
    projectModification: TemporaryProjectModification
) : BaseReversibleGradleProject<AndroidAiPackProject, AiPackDefinition>(
    parentProject,
), AndroidAiPackProject {
    override val files: GradleProjectFiles = ReversibleProjectFiles(projectModification)
}
