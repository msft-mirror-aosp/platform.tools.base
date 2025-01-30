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

import com.android.build.api.artifact.Artifact
import com.android.build.gradle.integration.common.fixture.TemporaryProjectModification
import com.android.build.gradle.integration.common.fixture.project.builder.FileUpdateBuilder
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleProjectFiles
import com.android.build.gradle.integration.common.fixture.project.builder.searchAndReplace
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Base Class for all reversible projects
 */
abstract class ReversibleGradleProject<ProjectT : GradleProject<ProjectDefinitionT>, ProjectDefinitionT : GradleProjectDefinition>(
    protected val parentProject: ProjectT,
    projectModification: TemporaryProjectModification
) : GradleProject<ProjectDefinitionT>, TemporaryProjectModification.FileProvider  {

    @Suppress("UNCHECKED_CAST")
    override val files: GradleProjectFiles = ReversibleProjectFiles(
        projectModification,
        (parentProject as GradleProjectImpl<ProjectDefinitionT>).location
    )

    override fun resolve(path: String): Path = parentProject.resolve(path)

    override fun resolve(artifact: Artifact<*>): Path = parentProject.resolve(artifact)

    override val buildDir: Path
        get() = parentProject.buildDir

    override fun reconfigure(action: ProjectDefinitionT.() -> Unit) {
        throw RuntimeException("Cannot reconfigure inside withReversibleModifications")
    }

    override fun file(path: String): File? {
        return (parentProject as GradleProjectImpl<*>).file(path)
    }
}


internal open class ReversibleProjectFiles(
    private val projectModification: TemporaryProjectModification,
    private val location: Path,
): GradleProjectFiles {
    override fun add(relativePath: String, content: String) {
        projectModification.addFile(relativePath, content)
    }

    override fun add(relativePath: String, content: ByteArray) {
        projectModification.addFile(relativePath, content)
    }

    override fun update(relativePath: String): FileUpdateBuilder =
        FileUpdater(projectModification, relativePath, location.resolve(relativePath), location)

    override fun remove(relativePath: String) {
        projectModification.removeFile(relativePath)
    }

    private class FileUpdater(
        private val projectModification: TemporaryProjectModification,
        private val relativePath: String,
        private val file: Path,
        private val location: Path
    ): FileUpdateBuilder {

        override val exists: Boolean
            get() = file.isRegularFile()

        override fun replaceWith(newContent: String) {
            projectModification.modifyFile(relativePath) {
                newContent
            }
        }

        override fun replaceWith(newContent: ByteArray) {
            projectModification.modifyFileWithBytes(relativePath) {
                newContent
            }
        }

        override fun searchAndReplace(
            search: String,
            replace: String,
            lenient: Boolean
        ): FileUpdateBuilder {
            if (!file.isRegularFile()) throw RuntimeException("File $file not found. Cannot update")

            projectModification.modifyFile(relativePath) {
                it.searchAndReplace(
                    file.toString(),
                    search,
                    replace,
                    Pattern.LITERAL,
                    lenient
                )
            }

            return this
        }

        override fun append(newContent: String) {
            if (file.isRegularFile()) {
                val content = file.readText()
                projectModification.modifyFile(relativePath) {
                    content + newContent
                }
            } else {
                projectModification.addFile(relativePath, newContent)
            }
        }

        override fun transform(action: (String) -> String): FileUpdateBuilder {
            if (!file.isRegularFile()) throw RuntimeException("File $file not found. Cannot update")

            projectModification.modifyFile(relativePath, action)

            return this
        }

        override fun moveTo(relativePath: String): FileUpdater {
            projectModification.addFile(relativePath, location.resolve(this.relativePath).readBytes())
            projectModification.removeFile(this.relativePath)
            return this
        }
    }
}

