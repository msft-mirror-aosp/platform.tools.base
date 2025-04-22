/*
 * Copyright (C) 2023 The Android Open Source Project
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

import com.android.adblib.AppProcessEntry
import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.adbLogger
import com.android.adblib.scope
import com.android.adblib.tools.debugging.AppProcess
import com.android.adblib.tools.debugging.AppProcessProperties
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.OptionalValue
import com.android.adblib.tools.debugging.impl.JdwpProcessPropertiesCollectorImpl.Companion.filterFakeName
import com.android.adblib.withPrefix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Implementation of [AppProcess]
 */
internal class AppProcessImpl(
    override val device: ConnectedDevice,
    appProcessEntry: AppProcessEntry,
    override val jdwpProcess: JdwpProcess?,
) : AppProcess, AutoCloseable {

    private val processDescription = "${device.session} - $device - pid=${appProcessEntry.pid}"

    private val logger = adbLogger(device.session).withPrefix("$processDescription -")

    override val cache = CoroutineScopeCache.create(device.scope, processDescription)

    private val stateFlow = MutableStateFlow(appProcessEntry.toAppProcessProperties())

    override val propertiesFlow: StateFlow<AppProcessProperties> = stateFlow.asStateFlow()

    override val pid: Int = stateFlow.value.pid

    /**
     * Called when a newer version of the [AppProcessEntry] for this process has been
     * collected. This method updates the internal [propertiesFlow] with the new value.
     */
    internal fun onAppProcessEntryUpdated(newEntry: AppProcessEntry) {
        assert(newEntry.pid == pid)

        stateFlow.update {
            it.mergeWith(newEntry)
        }
    }

    override fun close() {
        logger.debug { "close()" }
        cache.close()
    }

    override fun toString(): String {
        return "AppProcess(device=$device, pid=$pid, jdwpProcess=$jdwpProcess, " +
                "properties=${stateFlow.value})"
    }

    private fun AppProcessEntry.toAppProcessProperties(): AppProcessProperties {
        return AppProcessProperties(this.pid).mergeWith(this)
    }

    private fun AppProcessProperties.mergeWith(newEntry: AppProcessEntry): AppProcessProperties {
        val current = this
        assert(current.pid == newEntry.pid)
        return current.copy(
            debuggable = OptionalValue.of(newEntry.debuggable),
            profileable = OptionalValue.of(newEntry.profileable),
            processName = filterFakeName(newEntry.processName)?.let { OptionalValue.of(it) } ?: current.processName,
            packageNames = newEntry.packageNames?.mapNotNull { filterFakeName(it) }?.let { OptionalValue.of(it) } ?: current.packageNames,
            instructionSet = OptionalValue.of(newEntry.instructionSet),
            userId = newEntry.userId?.let { OptionalValue.of(it) } ?: current.userId,
            waitingForDebugger = newEntry.waitingForDebugger?.let { OptionalValue.of(it) } ?: current.waitingForDebugger,
            uid = newEntry.uid?.let { OptionalValue.of(it) } ?: current.uid,
        )
    }
}
