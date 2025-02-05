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

package com.android.build.gradle.integration.common.output

import com.android.utils.FileUtils
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern
import java.util.stream.Collectors
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/**
 * Base class for outputs. This handles core Zip features
 *
 * @param archivePath the path to the archive. if invalid [status] will no be [Status.EXISTS]
 * @param name the name of the zip when displaying assertion
 */
open class Zip(
    val archivePath: Path?,
    val name: String = archivePath?.fileName?.toString() ?: "missing zip path",
): AutoCloseable {
    enum class Status { EXISTS, DIRECTORY, DOES_NOT_EXIST }

    private val zip: FileSystem?
    private val innerZips = mutableMapOf<String, Zip>()

    val status: Status

    init {
        if (archivePath == null) {
            status = Status.DOES_NOT_EXIST
            zip = null
        } else if (archivePath.isDirectory()) {
            status = Status.DIRECTORY
            zip = null
        } else if (archivePath.fileSystem != FileSystems.getDefault()) {
            throw IllegalArgumentException(
                "Cannot create zip from non default fs, use getEntryAsZip() instead"
            )
        } else if (archivePath.isRegularFile()) {
            status = Status.EXISTS
            zip = FileSystems.newFileSystem(archivePath, null as ClassLoader?)
        } else {
            status = Status.DOES_NOT_EXIST
            zip = null
        }
    }

    fun exists(): Boolean {
        return status == Status.EXISTS
    }

    /**
     * Returns a zip entry given a name, returns null if it does not exist
     */
    fun getEntry(path: String): Path? {
        val path = path.makeAbsolute()

        val entry = zip?.getPath(path) ?: return null
        return if (entry.exists()) entry else null
    }

    fun getEntries(pattern: Pattern): List<String> {
        return getEntries { pattern.matcher(it.toString()).matches() }
    }

    fun getEntries(filter: ((String) -> Boolean)? = null): List<String> {
        return filter?.let { f ->
            allEntries.filter(f)
        } ?: allEntries
    }

    fun innerZip(path: String): Zip? {
        val zipPath = getEntry(path) ?: return null

        return innerZips.computeIfAbsent(path) {

            // TODO inject a TemporaryFolder rule?
            // archivePath here must be non-null since zip is fine
            val temp = Files.createTempFile(archivePath!!.fileName.toString(), "_inner_zip.zip")
            FileUtils.copyFile(zipPath, temp)
            temp.toFile().deleteOnExit()

            Zip(temp, "$name:$it")
        }
    }

    fun textFile(path: String): String? {
        val path = path.makeAbsolute()

        val zipPath = getEntry(path) ?: return null

        try {
            return zipPath.readText()
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    fun binaryFile(path: String): ByteArray? {
        val path = path.makeAbsolute()

        val zipPath = getEntry(path) ?: return null

        try {
            return zipPath.readBytes()
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    override fun close() {
        zip?.close()
        innerZips.values.forEach { it.close() }
    }

    override fun toString(): String {
        return "Zip(name='$name', status=$status)"
    }

    private val allEntries: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        val zip = this.zip ?: return@lazy listOf()

        Files.walk(zip.getPath("/"))
            .filter { it.isRegularFile() }
            .map { it.toString().substring(1) }
            .collect(Collectors.toList())
            .toList()
    }

    // --------------

    /**
     * Ensures the zip archive path is correct by prepending a '/' if needed.
     */
    protected fun String.makeAbsolute() = "/$this"

}
