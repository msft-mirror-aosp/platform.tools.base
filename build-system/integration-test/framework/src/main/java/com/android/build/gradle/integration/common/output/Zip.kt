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
 * A zip file, with methods to query for the content.
 *
 * This is used by [ZipSubject], [AarSubject], ... to validate the output of an AGP build
 *
 */
abstract class Zip(
    val name: String
): AutoCloseable {

    enum class Status { EXISTS, DIRECTORY, DOES_NOT_EXIST }

    private val innerZips = mutableMapOf<String, Zip>()

    abstract fun exists(): Boolean
    abstract val status: Status

    /**
     * Returns a zip entry given a name, returns null if it does not exist
     */
    abstract fun getEntry(path: String): Path?

    /**
     * Returns the list of entries, filtered by the given pattern.
     */
    fun getEntries(pattern: Pattern): List<String> {
        return getEntries { pattern.matcher(it.toString()).matches() }
    }

    /**
     * Returns the list of entries, filtered by the given filter.
     */
    abstract fun getEntries(filter: ((String) -> Boolean)? = null): List<String>

    fun innerZip(path: String): Zip? {
        val zipPath = getEntry(path) ?: return null

        return innerZips.computeIfAbsent(path) {

            // TODO inject a TemporaryFolder rule?
            val namePrefix = path.replace('.', '_').replace('/', '_')
            val temp = Files.createTempFile("innerzip_${namePrefix}_", ".zip")
            FileUtils.copyFile(zipPath, temp)
            temp.toFile().deleteOnExit()

            SimpleZip(temp, "$name:$it")
        }
    }

    fun textFile(path: String): String? {
        val zipPath = getEntry(path) ?: return null

        try {
            // read the lines separately and recombine them with the right carriage return
            // in order to work properly on windows.
            return Files.readAllLines(zipPath).stream().collect(Collectors.joining("\n")).trim()
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    fun binaryFile(path: String): ByteArray? {
        val zipPath = getEntry(path) ?: return null

        try {
            return zipPath.readBytes()
        } catch (e: IOException) {
            throw UncheckedIOException(e)
        }
    }

    override fun close() {
        innerZips.values.forEach { it.close() }
    }
}

/**
 * A simple zip implementation of [Zip].
 *
 * this is backed by a single zip file.
 *
 * @param archivePath the path to the archive. if invalid [status] will no be [Status.EXISTS]
 * @param name the name of the zip when displaying assertion
 */
class SimpleZip(
    val archivePath: Path?,
    name: String = archivePath?.fileName?.toString() ?: "missing zip path"
): Zip(name) {

    private val zip: FileSystem?
    private val internalStatus: Status

    init {
        if (archivePath == null) {
            internalStatus = Status.DOES_NOT_EXIST
            zip = null
        } else if (archivePath.isDirectory()) {
            internalStatus = Status.DIRECTORY
            zip = null
        } else if (archivePath.fileSystem != FileSystems.getDefault()) {
            throw IllegalArgumentException(
                "Cannot create zip from non default fs, use getEntryAsZip() instead"
            )
        } else if (archivePath.isRegularFile()) {
            internalStatus = Status.EXISTS
            zip = FileSystems.newFileSystem(archivePath, null as ClassLoader?)
        } else {
            internalStatus = Status.DOES_NOT_EXIST
            zip = null
        }
    }

    override fun exists(): Boolean {
        return internalStatus == Status.EXISTS
    }

    override val status: Zip.Status
        get() = internalStatus

    override fun getEntry(path: String): Path? {
        val path = "/$path"

        val entry = zip?.getPath(path) ?: return null
        return if (entry.exists()) entry else null
    }

    override fun getEntries(filter: ((String) -> Boolean)?): List<String> {
        return filter?.let { f ->
            allEntries.filter(f)
        } ?: allEntries
    }

    override fun close() {
        super.close()
        zip?.close()
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
}


/**
 * An implementation of [Zip] that is backed by multiple [Zip] instances.
 *
 * This provides a single view of several zips as if there were a single archive.
 *
 * This does not care about duplicate objects across archives. When searching for a file in
 * the archives, the first match is returned.
 */
class MultiZip(
    private val zips: List<Zip>,
    name: String
): Zip(name) {

    override fun exists(): Boolean {
        // all the underlying archives must exist, so we can return true
        return true
    }

    override val status: Status
        get() = Status.EXISTS

    override fun getEntry(path: String): Path? {
        for (zip in zips) {
            if (zip.getEntries().contains(path)) {
                return zip.getEntry(path)
            }
        }

        return null
    }

    override fun getEntries(filter: ((String) -> Boolean)?): List<String> {
        return filter?.let { f ->
            allEntries.filter(f)
        } ?: allEntries
    }

    override fun close() {
        super.close()
        zips.forEach(AutoCloseable::close)
    }

    private val allEntries: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        zips.flatMap { it.getEntries() }
    }
}

/**
 * An implementation of [Zip] that provides a filtered view of another zip file.
 *
 * The filtering is only to give access to a specific sub folder inside the original zip, making
 * all the paths relative to that sub-folder.
 */
class FilteredZip(
    private val zip: Zip,
    private val allowedPrefix: String
): Zip(zip.name) {

    override fun exists(): Boolean {
        return zip.exists()
    }

    override val status: Status
        get() = zip.status

    override fun getEntry(path: String): Path? {
        return zip.getEntry("$allowedPrefix$path")
    }

    override fun getEntries(filter: ((String) -> Boolean)?): List<String> {
        return filter?.let { f ->
            allEntries.filter(f)
        } ?: allEntries
    }

    private val allEntries: List<String> by lazy(LazyThreadSafetyMode.NONE) {
        zip.getEntries().mapNotNull {
            if (it.startsWith(allowedPrefix)) it.substring(allowedPrefix.length) else null
        }
    }
}
