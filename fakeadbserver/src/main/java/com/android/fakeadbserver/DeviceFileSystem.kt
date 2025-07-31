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
package com.android.fakeadbserver

import java.io.IOException

/**
 * In-memory simulation of the file system of an Android device.
 */
class DeviceFileSystem {

    /**
     * Map of "normalized" path to [DeviceFileState]. Note that [DeviceFileState.path]
     * may be different from the map key due to path normalization.
     */
    private val entries = mutableMapOf<NormalizedPath, DeviceFileState>()

    /**
     * Replaces all files in this [DeviceFileSystem] with files from the [source] file system
     */
    internal fun copyFrom(source: DeviceFileSystem) {
        entries.clear()
        source.entries.forEach { entry ->
            entries.put(entry.key, entry.value)
        }
    }

    /**
     * Creates or replaces a file entry from a [DeviceFileState]
     */
    fun createFile(file: DeviceFileState) {
        val path = normalizePath(file.path)
        val parentPath = path.parentPath
        check(parentPath != null) { "Invalid path: ${file.path}" }
        getOrCreateDirectory(parentPath)
        entries.put(path, file)
    }

    /**
     * Returns the [DeviceFileState] for the given [filePath], or `null` if the file entry
     * does not exist.
     */
    fun getFile(filePath: String): DeviceFileState? {
        return entries[normalizePath(filePath)]
    }

    /**
     * Returns all [DeviceFileState] stored in the given [directoryPath]
     */
    fun getDirectoryFiles(directoryPath: String): List<DeviceFileState> {
        val npath = normalizePath(directoryPath)
        val entry = entries[npath]
        if (entry == null || entry.kind != DeviceFileState.Kind.Directory) {
            throw IOException("Directory does not exist")
        }

        return entries.filter {
            it.key.parentPath == npath
        }.map {
            it.value
        }
    }

    /**
     * Deletes a file if it exists. If the entry is a directory, this function does nothing.
     */
    fun deleteFile(filepath: String) {
        val path = normalizePath(filepath)
        val entry = entries[path]
        if (entry != null && entry.kind == DeviceFileState.Kind.File) {
            entries.remove(path)
        }
    }

    /**
     * Similar to `mkdir -p` Unix command
     */
    private fun getOrCreateDirectory(path: NormalizedPath): DeviceFileState {
        // Create parent dir
        path.parentPath?.also {
            getOrCreateDirectory(it)
        }
        // Check entry kind or create directory entry
        return entries[path]?.also {
            if (it.kind != DeviceFileState.Kind.Directory) {
                throw IOException("Path is not a directory: $path")
            }
        } ?: run {
            DeviceFileState(
                path = path.path,
                permission = 0,
                modifiedDate = 0,
                bytes = ByteArray(0),
                kind = DeviceFileState.Kind.Directory
            ).also {
                entries[path] = it
            }
        }
    }

    private fun normalizePath(path: String): NormalizedPath {
        return NormalizedPath.fromString(path)
    }

    /**
     * * Root is always "/"
     * * Prefix is always "/"
     * * Suffix is never "/"
     */
    private class NormalizedPath private constructor (val path: String) {
        init {
            require(path.isNotEmpty())
            require(path.first() == '/')
            require(path == "/" || path.last() != '/')
        }

        val isRoot: Boolean
            get() = path =="/"

        val parentPath: NormalizedPath?
            get() {
                return if (isRoot) {
                    null
                } else {
                    NormalizedPath(path.substringBeforeLast("/").ifEmpty { "/" })
                }
            }

        override fun toString(): String {
            return "${NormalizedPath::class.java.simpleName}(\"$path\")"
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as NormalizedPath

            return path == other.path
        }

        override fun hashCode(): Int {
            return path.hashCode()
        }

        companion object {
            fun fromString(path: String): NormalizedPath {
                if (path.isEmpty()) {
                    throw IOException("Invalid file path: $path")
                }
                if (path == "/") {
                    return NormalizedPath(path)
                }
                var result = path
                if (result.first() != '/') {
                    result = "/$result"
                }
                if (result.last() == '/') {
                    result = result.substring(0, result.length - 1)
                }
                return NormalizedPath(result)
            }
        }
    }
}
