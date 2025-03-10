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
package com.android.adblib.tools.debugging

import com.android.adblib.AdbInputChannel

interface JdwpProcessViewHierarchy {

    /**
     * The JDWP process associated to this [JdwpProcessViewHierarchy]
     */
    val process: JdwpProcess

    /**
     * Returns a list of the root View objects. Each root View represents the base of a window's
     * view hierarchy. This is used to inspect the top-level structure of the application's user
     * interface.
     * The resulting payload is passed to [payloadProcessor] as an [AdbInputChannel] instance
     * that is valid only during the [payloadProcessor] invocation.
     */
    suspend fun <R> listViewRoots(
        payloadProcessor: suspend (payload: AdbInputChannel, payloadLength: Int) -> R): R

    /**
     * Dumps a view hierarchy rooted at `viewRoot`.
     *
     * The resulting payload is passed to [payloadProcessor] as an [AdbInputChannel] instance
     * that is valid only during the [payloadProcessor] invocation.
     */
    suspend fun <R> dumpViewHierarchy(
        viewRoot: String,
        skipChildren: Boolean,
        includeProperties: Boolean,
        useV2: Boolean,
        payloadProcessor: suspend (payload: AdbInputChannel, payloadLength: Int) -> R
    ): R

    /**
     * Captures view of a running Jdwp process.
     *
     * The resulting payload is passed to [payloadProcessor] as an [AdbInputChannel] instance
     * that is valid only during the [payloadProcessor] invocation.
     */
    suspend fun <R> captureView(
        viewRoot: String,
        view: String,
        payloadProcessor: suspend (payload: AdbInputChannel, payloadLength: Int) -> R
    ): R

}
