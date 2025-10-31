/*
 * Copyright (C) 2025 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.builder.BuildWriter
import com.android.build.gradle.integration.common.fixture.project.builder.GradleDefinitionDsl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.reversible.FileChangeController
import java.nio.file.Path

@GradleDefinitionDsl
interface JavaLibraryProjectDefinition : GradleProjectDefinition {
    /** executes the lambda that adds/updates/removes files from the project */
    fun files(action: GradleProjectFiles.() -> Unit)
}

interface JavaLibraryProject: GradleProject<JavaLibraryProjectDefinition>

internal class JavaLibraryProjectDefinitionImpl(path: String) :
    GradleProjectDefinitionImpl(path), JavaLibraryProjectDefinition {

    init {
        applyPlugin(PluginType.JAVA_LIBRARY)
    }

    override fun files(action: GradleProjectFiles.() -> Unit) {
        action(files)
    }

    override fun writeExtension(writer: BuildWriter, location: Path) {
        writer.apply {
            block("jar") {
                dslRecorder.writeContent(this)
            }
            emptyLine()
        }
    }
}

internal class JavaLibraryProjectImpl(
    location: Path,
    projectDefinition: JavaLibraryProjectDefinition
) : GradleProjectImpl<JavaLibraryProjectDefinition>(location, projectDefinition),
    JavaLibraryProject {

    override fun getReversibleInstance(fileChangeController: FileChangeController)
    : GradleProject<JavaLibraryProjectDefinition> {
        return ReversibleJavaLibraryProject(this, fileChangeController)
    }
}

internal open class ReversibleJavaLibraryProject(
    parentProject: JavaLibraryProject,
    fileChangeController: FileChangeController,
) : ReversibleGradleProject<JavaLibraryProject,
        JavaLibraryProjectDefinition>(
    parentProject,
    fileChangeController
), JavaLibraryProject
