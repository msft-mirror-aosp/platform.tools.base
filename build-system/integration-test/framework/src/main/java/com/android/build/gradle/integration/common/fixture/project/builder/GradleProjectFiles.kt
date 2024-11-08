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

import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Allows manipulating files of a [GradleProjectDefinition]
 */
interface GradleProjectFiles {

    /**
     * Adds a file to the given location with the given content.
     */
    fun add(relativePath: String, content: String)

    /**
     * Update the content of a file
     */
    fun update(relativePath: String, action: (String) -> String)

    /**
     * Removes the file at the given location
     */
    fun remove(relativePath: String)
}

/**
 * Allows manipulating files of a [GradleProject] that is an Android project
 *
 * The main goal is to give access to the namespace to create files in the right location.
 */
interface AndroidProjectFiles: GradleProjectFiles {
    val namespace: String
    val namespaceAsPath: String
}

internal open class GradleProjectFilesImpl: GradleProjectFiles {
    // map from relative path to file content
    private val sourceFiles = mutableMapOf<String, String>()

    override fun add(relativePath: String, content: String) {
        val existingContent = sourceFiles[relativePath]
        if (existingContent != null) {
            throw RuntimeException("A file already exist at $relativePath")
        }

        sourceFiles[relativePath] = content
    }

    override fun update(relativePath: String, action: (String) -> String) {
        val existingContent = sourceFiles[relativePath]
            ?: throw RuntimeException("No file exists at $relativePath")

        sourceFiles[relativePath] = action(existingContent)
    }

    override fun remove(relativePath: String) {
        sourceFiles[relativePath]
            ?: throw RuntimeException("No file exists at $relativePath")

        sourceFiles.remove(relativePath)
    }

    internal fun write(location: Path) {
        // write the content of the project
        for ((path, content) in sourceFiles) {

            val fileLocation = location.resolve(path)
            fileLocation.parent.createDirectories()
            fileLocation.writeText(content)
        }
    }
}

internal open class DirectGradleProjectFilesImpl(
    private val location: Path
): GradleProjectFiles {

    override fun add(relativePath: String, content: String) {
        location.resolve(relativePath).writeText(content)
    }

    override fun update(relativePath: String, action: (String) -> String) {
        val file = location.resolve(relativePath)
        val originalContent = file.readText()
        val newContent = action(originalContent)
        file.writeText(newContent)
    }

    override fun remove(relativePath: String) {
        location.resolve(relativePath).deleteExisting()
    }
}

internal class AndroidProjectFilesImpl(
    private val namespaceProvider: () -> String
): GradleProjectFilesImpl(), AndroidProjectFiles {
    override val namespace: String
        get() = namespaceProvider()
    override val namespaceAsPath: String
        get() = namespace.replace('.', '/')
}

internal class DirectAndroidProjectFilesImpl(
    location: Path,
    override val namespace: String
): DirectGradleProjectFilesImpl(location), AndroidProjectFiles {
    override val namespaceAsPath: String
        get() = namespaceAsPath.replace('.', '/')
}
