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

import com.android.adblib.AmCapabilitiesResult
import com.android.adblib.AppProcessEntry
import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.activityManager
import com.android.adblib.adbLogger
import com.android.adblib.getOrPutSynchronized
import com.android.adblib.scope
import com.android.adblib.tools.debugging.AtomicStateFlow
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.JdwpProcessProperties
import com.android.adblib.tools.debugging.addException
import com.android.adblib.tools.debugging.impl.UsingAppInfoFlowUpdater.Companion.VmInfoRetriever.VmInfo
import com.android.adblib.tools.debugging.trackAppStateFlow
import com.android.adblib.tools.debugging.utils.logIOCompletionErrors
import com.android.adblib.withDevicePrefix
import com.android.adblib.withProcessPrefix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Reads [JdwpProcessProperties] from [ConnectedDevice.trackAppStateFlow] entries
 */
internal class UsingAppInfoFlowUpdater(
    private val process: JdwpProcess
) : JdwpProcessPropertiesFlowUpdater {

    private val device: ConnectedDevice
        get() = process.device

    private val pid: Int
        get() = process.pid

    private val logger = adbLogger(device.session).withProcessPrefix(device, pid)

    override suspend fun execute(stateFlow: AtomicStateFlow<JdwpProcessProperties>) {
        coroutineScope {
            executeWorker(this, stateFlow)
        }
    }

    private fun executeWorker(
        processScope: CoroutineScope,
        stateFlow: AtomicStateFlow<JdwpProcessProperties>
    ) {
        // Collect device specific properties into the process properties flow
        processScope.launch {
            kotlin.runCatching {
                device.vmInfoRetriever.vmInfo()?.also { vmInfo ->
                    vmInfo.also {
                        logger.debug { "Updating process properties with vmInfo=$vmInfo" }
                        stateFlow.update { current ->
                            current.copy(
                                vmIdentifier = vmInfo.vmIdentifier,
                                features = vmInfo.features
                            )
                        }
                    }
                } ?: run {
                    // This should not happen, because if we were able to retrieve the device
                    // capabilities, the `VmInfoRetriever` should also be able to retrieve the
                    // `VmInfo`.
                    throw IOException(
                        "The `${VmInfo::class.simpleName}` for " +
                                "the device is `null`, this is not expected"
                    )
                }
            }.onFailure { throwable ->
                logger.logIOCompletionErrors(throwable)
                stateFlow.update { current ->
                    current.copy(
                        exception = current.addException(throwable)
                    )
                }
            }
        }

        // Figure out when to set the `completed` boolean property
        processScope.launch {
            runCatching {
                // Wait for all properties to be set
                stateFlow.asStateFlow().first { properties ->
                    val completed =
                        (properties.processName != null) &&
                                (properties.packageName != null) &&
                                (properties.userId != null) &&
                                (properties.vmIdentifier != null) &&
                                (properties.jvmFlags != null) &&
                                (properties.instructionSetDescription != null) &&
                                (properties.instructionSet != null) &&
                                (properties.features.isNotEmpty())

                    completed
                }

                logger.debug { "Setting `completed` to `true` as all properties are set" }
                stateFlow.update { it.copy(completed = true) }
            }.onFailure { throwable ->
                logger.logIOCompletionErrors(throwable)
            }
        }

        // Collect properties from the `track-app` service
        processScope.launch {
            logger.debug { "Monitoring process properties using `track-app` service" }
            device.trackAppStateFlow()
                .map {
                    // Find process entry with `pid`
                    it.entries.firstOrNull { appProcessEntry ->
                        appProcessEntry.pid == pid
                    }
                }
                .filterNotNull()
                .collect {
                    assert(it.pid == pid)
                    updateStateFlow(stateFlow, it)
                }
        }
    }

    private fun updateStateFlow(
        stateFlow: AtomicStateFlow<JdwpProcessProperties>,
        appProcessEntry: AppProcessEntry
    ) {
        logger.verbose { "Updating Jdwp process properties: appProcessEntry=$appProcessEntry" }
        stateFlow.update { current ->
            current.copy(
                processName = JdwpProcessPropertiesCollectorImpl.filterFakeName(appProcessEntry.processName)
                    ?: current.processName,
                packageName = JdwpProcessPropertiesCollectorImpl.filterFakeName(appProcessEntry.packageNames?.firstOrNull())
                    ?: current.packageName,
                userId = appProcessEntry.userId32 ?: current.userId,
                instructionSet = appProcessEntry.instructionSet,
                isWaitingForDebugger = appProcessEntry.waitingForDebugger ?: current.isWaitingForDebugger,
                jvmFlags = legacyJvmFlags()
            )
        }
    }

    /**
     * We need set the [JdwpProcessProperties.jvmFlags] property to its legacy value,
     * which is not supported by `track-app`.
     */
    private fun legacyJvmFlags(): String {
        return "CheckJNI=true"
    }

    companion object {
        private val VmInfoRetrieverKey = CoroutineScopeCache.Key<VmInfoRetriever>("TrackApp")

        private val ConnectedDevice.vmInfoRetriever: VmInfoRetriever
            get() {
                return cache.getOrPutSynchronized(VmInfoRetrieverKey) {
                    VmInfoRetriever(this)
                }
            }

        /**
         * Allow retrieving (and caching) a [VmInfo] instance for a given [ConnectedDevice]
         */
        private class VmInfoRetriever(device: ConnectedDevice) {

            private val logger = adbLogger(device.session).withDevicePrefix(device)

            private val deferredVmInfo: Deferred<VmInfo?> =
                device.scope.async {
                    // Note: The result of `capabilities()` is cached per device
                    device.activityManager.capabilities()?.let { amCapabilities ->
                        VmInfo(
                            vmIdentifier = buildVmIdentifier(amCapabilities),
                            features = buildFeatureList(amCapabilities)
                        )
                    } ?: run {
                        logger.info { "Device capabilities is not supported" }
                        null
                    }
                }

            private fun buildVmIdentifier(amCapabilities: AmCapabilitiesResult): String {
                val vmInfo = amCapabilities.vmInfo
                return if (vmInfo == null) {
                    // Note: If "app_info" is supported, then the "vmInfo" capability should also
                    // be supported (see https://android-review.googlesource.com/c/platform/frameworks/base/+/3086485)
                    logger.info { "The result of 'am capabilities' does not contain a " +
                            "'${AmCapabilitiesResult.VmInfo::class.simpleName}' value" }
                    "<unknown>"
                } else {
                    // At the time of this writing, both field are set to non-empty strings,
                    // but make sure we are resilient to future changes.
                    if (vmInfo.name.isEmpty()) {
                        vmInfo.version
                    } else if (vmInfo.version.isEmpty()) {
                        vmInfo.name
                    } else {
                        "${vmInfo.name} ${vmInfo.version}"
                    }
                }
            }

            private fun buildFeatureList(amCapabilities: AmCapabilitiesResult): List<String> {
                val additionalFrameworkCapabilities =
                    amCapabilities.frameworkCapabilities - amCapabilities.vmCapabilities.toSet()
                return amCapabilities.vmCapabilities + additionalFrameworkCapabilities
            }

            suspend fun vmInfo(): VmInfo? {
                return deferredVmInfo.await()
            }

            data class VmInfo(
                val vmIdentifier: String,
                val features: List<String>
            )
        }
    }
}
