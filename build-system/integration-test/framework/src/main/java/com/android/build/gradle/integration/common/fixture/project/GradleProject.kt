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

import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.project.builder.BaseGradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.DirectGradleProjectFilesImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinitionImpl
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import java.io.File
import java.nio.file.Path

/**
 * a subproject part of a [GradleBuild]
 */
interface GradleProject: TemporaryProjectModification.FileProvider {
    /** the location on disk of the project */
    val location: Path
    /** the object that allows to add/update/remove files from the project */
    val files: GradleProjectFiles
}

/**
 * Default implementation of [GradleProject]
 */
internal open class GradleProjectImpl(
    final override val location: Path,
    open protected val projectDefinition: BaseGradleProjectDefinition,
    protected val parentBuild: GradleBuildDefinitionImpl,
): GradleProject {

    override val files: GradleProjectFiles = DirectGradleProjectFilesImpl(location)

    override fun file(path: String): File? {
        return location.resolve(path).toFile()
    }
}

