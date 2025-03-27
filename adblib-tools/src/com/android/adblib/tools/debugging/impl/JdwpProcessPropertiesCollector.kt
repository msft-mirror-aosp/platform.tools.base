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
package com.android.adblib.tools.debugging.impl

import com.android.adblib.AdbFeatures
import com.android.adblib.ConnectedDevice
import com.android.adblib.adbLogger
import com.android.adblib.property
import com.android.adblib.tools.AdbLibToolsProperties
import com.android.adblib.tools.debugging.AtomicStateFlow
import com.android.adblib.tools.debugging.JdwpProcessProperties
import com.android.adblib.tools.debugging.JdwpProxySocketServerStatus
import com.android.adblib.tools.debugging.isAppInfoSupported
import com.android.adblib.withProcessPrefix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * A [JdwpProcessPropertiesCollector] is responsible for collecting properties of a given JDWP
 * process [pid] running on a given [device].
 *
 * * [processScope] is a [CoroutineScope] this [JdwpProcessPropertiesCollector] can use
 * to launch asynchronous coroutines, and is guaranteed to be cancelled when the
 * process [pid] is terminated on the device.
 * * [jdwpSessionProvider] provides access to a JDWP session for the process if needed.
 */
internal class JdwpProcessPropertiesCollector(
    private val device: ConnectedDevice,
    private val processScope: CoroutineScope,
    private val pid: Int,
    private val jdwpSessionProvider: SharedJdwpSessionProvider
) {

    private val logger = adbLogger(device.session).withProcessPrefix(device, pid)

    /**
     * Collects [JdwpProcessProperties] for the process [pid] emits them to [stateFlow],
     * retrying as many times as necessary if there is contention on acquiring JDWP sessions
     * to the process.
     */
    suspend fun execute(
        stateFlow: AtomicStateFlow<JdwpProcessProperties>,
        proxyStatusFlow: StateFlow<JdwpProxySocketServerStatus>) {
        createFlowUpdater(proxyStatusFlow).execute(processScope, stateFlow)
    }

    private suspend fun createFlowUpdater(
        proxyStatusFlow: StateFlow<JdwpProxySocketServerStatus>
    ): JdwpProcessPropertiesFlowUpdater {
        val useAppInfo =
            device.session.property(AdbLibToolsProperties.PROCESS_PROPERTIES_COLLECTOR_USE_APP_INFO_IF_AVAILABLE) &&
                    device.isAppInfoSupported()
        return if (useAppInfo) {
            logger.debug { "${AdbFeatures.APP_INFO} is supported, using TRACK_APP collector" }
            return UsingAppInfoFlowUpdater(device, pid)
        } else {
            logger.debug { "${AdbFeatures.APP_INFO} is not supported or active, using JDWP collector" }
            UsingJdwpSessionFlowUpdater(device, pid, jdwpSessionProvider, proxyStatusFlow)
        }
    }

    companion object {

        /**
         * The process name (and package name) can be set to this value when the process is not yet fully
         * initialized. We should ignore this value to make sure we only return "valid" process/package name.
         * Note that sometimes the process name (or package name) can also be empty.
         */
        private val EARLY_PROCESS_NAMES = arrayOf("<pre-initialized>", "")

        fun filterFakeName(processOrPackageName: String?): String? {
            return if (EARLY_PROCESS_NAMES.contains(processOrPackageName)) {
                return null
            } else {
                processOrPackageName
            }
        }
    }
}
