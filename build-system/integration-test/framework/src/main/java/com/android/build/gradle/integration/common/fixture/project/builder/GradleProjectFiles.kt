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

import com.android.utils.toSystemLineSeparator
import org.jetbrains.annotations.VisibleForTesting
import org.junit.Assert
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.regex.Pattern
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Allows manipulating files of a [GenericProjectDefinition]
 */
interface GradleProjectFiles {

    /**
     * Adds a file to the given location with the given content.
     *
     * If the file already exists, an exception is thrown
     */
    fun add(relativePath: String, content: String)

    /**
     * Returns a [FileUpdateBuilder] to update the content of a file.
     */
    fun update(relativePath: String): FileUpdateBuilder

    /**
     * Update a file via the provided action on a [FileUpdateBuilder]
     */
    fun update(relativePath: String, action: FileUpdateBuilder.() -> Unit) {
        action(update(relativePath))
    }

    /**
     * Removes the file at the given location
     */
    fun remove(relativePath: String)
}

interface FileUpdateBuilder {
    val exists: Boolean
    fun replaceWith(newContent: String)

    fun searchAndReplace(search: String, replace: String, lenient: Boolean = false): FileUpdateBuilder

    fun append(newContent: String)

    fun transform(action: (String) -> String): FileUpdateBuilder
}

/**
 * Allows manipulating files of a [GradleProject] that is an Android project
 *
 * The main goal is to give access to the namespace to create files in the right location.
 */
interface AndroidProjectFiles: GradleProjectFiles {
    val namespace: String
    val namespaceAsPath: String

    /**
     * Sets up a basic minimum Manifest, enough to build some projects
     */
    fun setupMinimumManifest() {
        update("src/main/AndroidManifest.xml").replaceWith(
            //language=XML
            """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                             xmlns:dist="http://schemas.android.com/apk/distribution">
                        <application />
                    </manifest>
                """.trimMargin()
        )
    }
}

/**
 * Implementation of [GradleProjectFiles] that only records the actions but does not yet
 * write anything on disk. This is done later when the project is created via [write]
 */
internal open class DelayedGradleProjectFiles: GradleProjectFiles {
    // map from relative path to file content
    @get:VisibleForTesting
    internal val sourceFiles = mutableMapOf<String, String>()

    override fun add(relativePath: String, content: String) {
        val existingContent = sourceFiles[relativePath]
        if (existingContent != null) {
            throw RuntimeException("A file already exist at $relativePath")
        }

        sourceFiles[relativePath] = content
    }

    override fun update(relativePath: String): FileUpdateBuilder =
        FileUpdater(sourceFiles, relativePath)

    override fun remove(relativePath: String) {
        sourceFiles[relativePath]
            ?: throw NoSuchFileException("No file exists at $relativePath")

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

    private class FileUpdater(
        private val map: MutableMap<String, String>,
        private val key: String
    ): FileUpdateBuilder {

        override val exists: Boolean
            get() = map[key] != null

        override fun replaceWith(newContent: String) {
            map[key] = newContent
        }

        override fun searchAndReplace(
            search: String,
            replace: String,
            lenient: Boolean
        ): FileUpdateBuilder {
            val content = map[key] ?: throw RuntimeException("File $key not found. Cannot update")
            map[key] = content.searchAndReplace(key, search, replace, Pattern.LITERAL, lenient = false)
            return this
        }

        override fun append(newContent: String) {
            val content = map[key]
            map[key] = content?.let { it + newContent } ?: newContent
        }

        override fun transform(action: (String) -> String): FileUpdateBuilder {
            val content = map[key] ?: throw RuntimeException("File $key not found. Cannot update")
            map[key] = action(content)
            return this
        }
    }
}

internal open class DirectGradleProjectFiles(
    @get:VisibleForTesting
    internal val location: Path
): GradleProjectFiles {

    override fun add(relativePath: String, content: String) {
        val file = location.resolve(relativePath)
        file.parent.createDirectories()
        file.writeText(content)
    }

    override fun update(relativePath: String): FileUpdateBuilder =
        FileUpdater(location.resolve(relativePath))

    override fun remove(relativePath: String) {
        location.resolve(relativePath).deleteExisting()
    }

    private class FileUpdater(private val file: Path): FileUpdateBuilder {

        override val exists: Boolean
            get() = file.isRegularFile()

        override fun replaceWith(newContent: String) {
            file.parent.createDirectories()
            file.writeText(newContent)
        }

        override fun searchAndReplace(
            search: String,
            replace: String,
            lenient: Boolean
        ): FileUpdateBuilder {
            val content = if (file.isRegularFile())
                file.readText()
            else
                throw RuntimeException("File $file not found. Cannot update")

            file.writeText(
                content.searchAndReplace(
                    file.toString(),
                    search,
                    replace,
                    Pattern.LITERAL,
                    lenient = false
                )
            )

            return this
        }

        override fun append(newContent: String) {
            val oldContent = if (file.isRegularFile()) file.readText() else null
            file.parent.createDirectories()
            file.writeText(oldContent?.let {
                it + newContent
            } ?: newContent)
        }

        override fun transform(action: (String) -> String): FileUpdateBuilder {
            val content = if (file.isRegularFile())
                file.readText()
            else
                throw RuntimeException("File $file not found. Cannot update")

            file.writeText(action(content))
            return this
        }
    }
}

internal class DelayedAndroidProjectFiles(
    private val namespaceProvider: () -> String
): DelayedGradleProjectFiles(), AndroidProjectFiles {
    override val namespace: String
        get() = namespaceProvider()
    override val namespaceAsPath: String
        get() = namespace.replace('.', '/')
}

internal class DirectAndroidProjectFiles(
    location: Path,
    override val namespace: String
): DirectGradleProjectFiles(location), AndroidProjectFiles {
    override val namespaceAsPath: String
        get() = namespaceAsPath.replace('.', '/')
}

internal fun String.searchAndReplace(
    name: String,
    search: String,
    replace: String,
    flags: Int,
    lenient: Boolean
): String {
    var rwSearch = search
    var rwReplace = replace

    // Handle patterns that use unix-style line endings even on Windows where the test
    // projects are sometimes checked out with Windows-style endings depending on the .gitconfig
    // "autocrlf" property
    if (this.contains("\r\n")) {
        rwSearch = search.toSystemLineSeparator()
        rwReplace = replace.toSystemLineSeparator()
    }

    val newContent = Pattern.compile(rwSearch, flags).matcher(this).replaceAll(rwReplace)
    if (!lenient) {
        Assert.assertNotEquals(
            """
                No match in file
                - File:   $name
                - Search: $search
                - Replace: $replace
            """.trimIndent(),
            this,
            newContent
        )
    }

    return newContent

}
