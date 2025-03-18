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

/**
 * Subject to test the content of an Archive.
 */
interface FileArchiveSubject {
    /**
     * Validates that the archive file list matches exactly with the provided list.
     *
     * The archive list contains files only. There are no folders in it.
     *
     * The possible format of the items in the provided list includes both file path and folders.
     * In the case of folders, it will match against any files in the archive that are in that folder.
     */
    fun containsExactly(items: Collection<String>)

    /**
     * Validates that the archive file list matches exactly with the provided list.
     *
     * The archive list contains files only. There are no folders in it.
     *
     * The possible format of the items in the provided list includes both file path and folders.
     * In the case of folders, it will match against any files in the archive that are in that folder.
     */
    fun containsExactly(vararg items: String) {
        containsExactly(items.toList())
    }

    /**
     * Validates that the archive file list contains at least the provided elements
     *
     * The archive list contains files only. There are no folders in it.
     *
     * The possible format of the items in the provided list includes both file path and folders.
     * In the case of folders, it will match against any files in the archive that are in that folder.
     */
    fun containsAtLeast(items: Collection<String>)

    /**
     * Validates that the archive file list contains at least the provided element
     *
     * The archive list contains files only. There are no folders in it.
     *
     * The possible format of the provided item list includes both file path and folders.
     * In the case of folders, it will match against any files in the archive that are in that folder.
     */
    fun contains(item: String) {
        containsAtLeast(listOf(item))
    }

    /**
     * Validates that the archive file list contains at least the provided elements
     *
     * The archive list contains files only. There are no folders in it.
     *
     * The possible format of the items in the provided list includes both file path and folders.
     * In the case of folders, it will match against any files in the archive that are in that folder.
     */
    fun containsAtLeast(vararg items: String) {
        containsAtLeast(items.toList())
    }

    /**
     * Validates whether the archive is empty.
     */
    fun isEmpty()

    /**
     * Checks the size of the archive
     */
    fun hasSize(size: Int)
}
