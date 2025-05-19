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
package com.android.adblib.tools.debugging.impl

import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.getOrPutSynchronized
import com.android.adblib.tools.debugging.JdwpProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/**
 * Maintains a list of active [JdwpProcess] instances of a given [ConnectedDevice].
 *
 * The [addProcesses] method allows callers to retrieve [JdwpProcess] instances, which are
 * automatically closed when the corresponding processes exit on the device.
 *
 * A [JdwpProcessManager] instance, as well as all [JdwpProcess] instances, are closed when
 * the [ConnectedDevice] scope is cancelled (i.e. when the device is disconnected).
 *
 * Note: All methods of this class are thread-safe.
 */
internal interface JdwpProcessManager {

    /**
     * The [device] this manager is tied to.
     */
    val device: ConnectedDevice

    /**
     * Add [processIds] to the list of active processes and returns a [Map]
     * of these process IDs to [JdwpProcess] instances. This is an atomic
     * operation to ensure thread-safety, i.e. it is guaranteed that [Map.keys]
     * of the returned [Map] is the same [Set] as [processIds].
     *
     * Note that calling this method multiple times with the same process ID
     * may result in identical [JdwpProcess] instances returned.
     *
     * Note it is valid to call this method with process IDS of processes that
     * do not (yet) exist on the device, the returned [JdwpProcess] instances will
     * simply remain active for a little while before being closed. This behavior is
     * needed to ensure smooth behavior due to the asynchronous nature of process
     * creation and termination.
     *
     * The lifetime of the returned [JdwpProcess] instances is managed by this
     * [JdwpProcessManager], i.e. [JdwpProcess.scope] is valid until the process
     * has terminated on the device. Given the asynchronous behavior of process
     * termination, there may be a short delay between the process termination
     * and the [JdwpProcess.scope] being [cancelled][CoroutineScope.cancel].
     */
    fun addProcesses(processIds: Set<Int>): Map<Int, JdwpProcess>
}

/**
 * Returns the [JdwpProcessManager] for this [ConnectedDevice].
 */
internal val ConnectedDevice.jdwpProcessManager: JdwpProcessManager
    get() = jdwpProcessManagerImpl

private val jdwpProcessManagerKey =
    CoroutineScopeCache.Key<JdwpProcessManagerImpl>(JdwpProcessManagerImpl::class.java.simpleName)

/**
 * Returns the [JdwpProcessManagerImpl] for this [ConnectedDevice].
 */
internal val ConnectedDevice.jdwpProcessManagerImpl: JdwpProcessManagerImpl
    get() {
        return cache.getOrPutSynchronized(jdwpProcessManagerKey) {
            JdwpProcessManagerImpl(device = this)
        }
    }
