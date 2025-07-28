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

import com.android.adblib.AdbSession
import com.android.adblib.AdbUsageTracker
import com.android.adblib.AdbUsageTracker.AppInfoProcessPropertiesCollectorEvent
import com.android.adblib.AdbUsageTracker.AppInfoProcessPropertiesCollectorEventType
import com.android.adblib.AmCapabilitiesResult
import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.activityManager
import com.android.adblib.adbLogger
import com.android.adblib.getOrPutSynchronized
import com.android.adblib.scope
import com.android.adblib.tools.debugging.AtomicStateFlow
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.JdwpProcessProperties
import com.android.adblib.tools.debugging.impl.UsingAppInfoFlowUpdater.Companion.VmInfoRetriever.VmInfo
import com.android.adblib.tools.debugging.isAppInfoSupported
import com.android.adblib.tools.debugging.orElse
import com.android.adblib.tools.debugging.rethrowCancellation
import com.android.adblib.tools.debugging.trackApp
import com.android.adblib.utils.logIOCompletionErrors
import com.android.adblib.withDevicePrefix
import com.android.adblib.withProcessPrefix
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * A [JdwpProcessPropertiesFlowUpdater] implementation that collects [JdwpProcessProperties]
 * from [ConnectedDevice.trackApp] for a given [JdwpProcess].
 *
 * This class should only be used if the device of the [process] supports
 * [ConnectedDevice.isAppInfoSupported]
 */
internal class UsingAppInfoFlowUpdater(
    private val process: JdwpProcess
) : JdwpProcessPropertiesFlowUpdater {

    private val session: AdbSession
        get() = device.session

    private val device: ConnectedDevice
        get() = process.device

    private val pid: Int
        get() = process.pid

    private val optionalValueFactory: OptionalValueFactory
        get() = process.device.optionalValueFactory

    private val logger = adbLogger(device.session).withProcessPrefix(device, pid)

    override suspend fun collectUpdates(stateFlow: AtomicStateFlow<JdwpProcessProperties>) {
        coroutineScope {
            val jobs = arrayOf(
                async {
                    // Collect properties from the `track-app` service
                    collectTrackAppUpdates(stateFlow)
                },
                async {
                    // Collect device specific properties
                    collectVmInfo(stateFlow)
                },
            )
            awaitAll(*jobs)
        }
    }

    private suspend fun collectTrackAppUpdates(stateFlow: AtomicStateFlow<JdwpProcessProperties>) {
        logger.debug { "Monitoring process properties using `track-app` service" }
        try {
            device.trackApp.stateFlow
                .map {
                    // Find process entry with `pid`
                    it.firstOrNull { appProcessEntry ->
                        appProcessEntry.pid == pid
                    }
                }
                .filterNotNull()
                .collect { appProcessEntry ->
                    assert(appProcessEntry.pid == pid)
                    logger.verbose { "Updating Jdwp process properties: appProcessEntry=$appProcessEntry" }
                    stateFlow.update { properties ->
                        properties.copy(
                            processName = optionalValueFactory.optionalOrErrorIfNull(appProcessEntry.processName) { processName ->
                                optionalValueFactory.ofFilteredFakeName(processName)
                            }.orElse(properties.processName),
                            packageNames = optionalValueFactory.optionalOrErrorIfNull(
                                appProcessEntry.packageNames
                            ) { packageNames ->
                                optionalValueFactory.ofFilteredFakeNames(packageNames)
                            }.orElse(properties.packageNames),
                            userId = optionalValueFactory.optionalOrErrorIfNull(appProcessEntry.userId32) { userId32 ->
                                optionalValueFactory.of(userId32)
                            }.orElse(properties.userId),
                            instructionSet = optionalValueFactory.ofInstructionSet(appProcessEntry.instructionSet)
                                .orElse(properties.instructionSet),
                            isWaitingForDebugger = optionalValueFactory.optionalOrErrorIfNull(
                                appProcessEntry.waitingForDebugger
                            ) { waitingForDebugger ->
                                optionalValueFactory.of(waitingForDebugger)
                            }.orElse(properties.isWaitingForDebugger),
                            isNativeDebuggable = optionalValueFactory.of(false),
                            jvmFlags = optionalValueFactory.ofJvmFlags(legacyJvmFlags())
                        )
                    }
                    logUsage(AppInfoProcessPropertiesCollectorEventType.TRACK_APP_VALUE_COLLECTED)
                }
        } catch (throwable: Throwable) {
            throwable.rethrowCancellation()
            val eventType = when (throwable) {
                is IOException -> AppInfoProcessPropertiesCollectorEventType.TRACK_APP_IO_EXCEPTION
                else -> AppInfoProcessPropertiesCollectorEventType.TRACK_APP_OTHER_EXCEPTION
            }
            logUsage(eventType)
            throw throwable
        }
    }

    private suspend fun collectVmInfo(stateFlow: AtomicStateFlow<JdwpProcessProperties>) {
        try {
            device.vmInfoRetriever.vmInfo()?.also { vmInfo ->
                vmInfo.also {
                    logger.debug { "Updating process properties with vmInfo=$vmInfo" }
                    stateFlow.update {
                        it.copy(
                            vmIdentifier = optionalValueFactory.ofVmIdentifier(vmInfo.vmIdentifier).orElse(it.vmIdentifier),
                            features = optionalValueFactory.ofFeatures(vmInfo.features).orElse(it.features)
                        )
                    }
                    logUsage(AppInfoProcessPropertiesCollectorEventType.VM_INFO_VALUE_COLLECTED)
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
        } catch (throwable: Throwable) {
            logger.logIOCompletionErrors(throwable)
            throwable.rethrowCancellation()
            val eventType = when (throwable) {
                is IOException -> AppInfoProcessPropertiesCollectorEventType.VM_INFO_IO_EXCEPTION
                else -> AppInfoProcessPropertiesCollectorEventType.VM_INFO_OTHER_EXCEPTION
            }
            logUsage(eventType)
            stateFlow.update {
                it.copy(
                    vmIdentifier = optionalValueFactory.ofError<String>("Error collecting VM identifier from device capabilities").orElse(it.vmIdentifier),
                    features = optionalValueFactory.ofError<List<String>>("Error collecting features from device capabilities").orElse(it.features)
                )
            }
        }
    }

    private suspend fun logUsage(
        eventType: AppInfoProcessPropertiesCollectorEventType
    ) {
        val deviceInfo = AdbUsageTracker.DeviceInfo.createFrom(device)
        session.host.usageTracker.logUsage(
            AdbUsageTracker.Event(
                deviceInfo = deviceInfo,
                appInfoProcessPropertiesCollector = AppInfoProcessPropertiesCollectorEvent(
                    eventType = eventType
                )
            )
        )
    }

    /**
     * We need set the [JdwpProcessProperties.jvmFlags] property to its legacy value,
     * which is not supported by `track-app`.
     */
    private fun legacyJvmFlags() = "CheckJNI=true"

    companion object {
        private val VmInfoRetrieverKey = CoroutineScopeCache.Key<VmInfoRetriever>("TrackApp")

        private val ConnectedDevice.vmInfoRetriever: VmInfoRetriever
            get() = cache.getOrPutSynchronized(VmInfoRetrieverKey) {
                VmInfoRetriever(this)
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
