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
import com.android.adblib.tools.debugging.AtomicStateFlow
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.JdwpProcessProperties
import com.android.adblib.tools.debugging.JdwpProcessPropertiesCollector
import com.android.adblib.tools.debugging.OptionalValue
import com.android.adblib.tools.debugging.externalJdwpProcessPropertiesCollectorFactoryList
import com.android.adblib.tools.debugging.externalJdwpProcessCommandDispatcherList
import com.android.adblib.tools.debugging.orElse
import com.android.adblib.tools.debugging.useAppInfoForProcessProperties
import com.android.adblib.utils.logIOCompletionErrors
import com.android.adblib.withProcessPrefix
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Implementation of [JdwpProcessPropertiesCollector], responsible for collecting properties of
 * a given [JdwpProcess] in a [StateFlow] of [JdwpProcessProperties].
 */
internal class JdwpProcessPropertiesCollectorImpl(
    override val process: JdwpProcess,
) : JdwpProcessPropertiesCollector {

    private val device: ConnectedDevice
        get() = process.device

    private val pid: Int
        get() = process.pid

    private val processScope: CoroutineScope
        get() = process.scope

    private val logger = adbLogger(device.session).withProcessPrefix(device, pid)

    private val propertiesAtomicStateFlow =
        AtomicStateFlow(MutableStateFlow(JdwpProcessProperties(pid)))

    override val stateFlow: StateFlow<JdwpProcessProperties> =
        propertiesAtomicStateFlow.asStateFlow()
        get() {
            lazyStartMonitoring
            return field
        }

    private val lazyStartMonitoring by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        logger.debug { "Start monitoring" }

        processScope.launch(device.session.ioDispatcher) {
            runCatching {
                createFlowUpdater().collectUpdates(propertiesAtomicStateFlow)
            }.onFailure { throwable ->
                logger.logIOCompletionErrors(throwable)
                propertiesAtomicStateFlow.applyEndOfCollectorErrorIfEmpty()
            }
        }

        // Launch external collectors (e.g. out of process inventory) if available
        processScope.launch(device.session.ioDispatcher) {
            runCatching {
                if (device.useAppInfoForProcessProperties()) {
                    // Don't call external collectors if we use app info, because app info
                    // is always the source of truth
                    return@launch
                }

                device.session.externalJdwpProcessPropertiesCollectorFactoryList.mapNotNull { factory ->
                    factory.create(process)
                }.forEach { externalCollector ->
                    launch {
                        runCatching {
                            val handler = ExternalPropertiesCollectorHandler(
                                externalCollector,
                                propertiesAtomicStateFlow
                            )
                            handler.execute()
                        }.onFailure { throwable ->
                            logger.logIOCompletionErrors(throwable)
                        }
                    }
                }

                process.externalJdwpProcessCommandDispatcherList().forEach { externalDispatcher ->
                    launch {
                        runCatching {
                            externalDispatcher.start()
                        }.onFailure { throwable ->
                            logger.logIOCompletionErrors(throwable)
                        }
                    }
                }
            }.onFailure { throwable ->
                logger.logIOCompletionErrors(throwable)
            }
        }
    }

    private suspend fun createFlowUpdater(): JdwpProcessPropertiesFlowUpdater {
        return if (device.useAppInfoForProcessProperties()) {
            logger.debug { "${AdbFeatures.APP_INFO} is supported, using TRACK_APP collector" }
            UsingAppInfoFlowUpdater(process)
        } else {
            logger.debug { "${AdbFeatures.APP_INFO} is not supported or active, using JDWP collector" }
            UsingJdwpSessionFlowUpdater(process)
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

        private val endOfCollectorErrorSingleton = OptionalValue.ofError<Any>("Property collector has stopped")

        /**
         * Ensure all [OptionalValue] of this [JdwpProcessProperties] that are still
         * [OptionalValue.empty] are updated to [endOfCollectorErrorSingleton]
         */
        internal fun AtomicStateFlow<JdwpProcessProperties>.applyEndOfCollectorErrorIfEmpty() {
            fun <T:Any> OptionalValue<T>.applyEndOfCollectorErrorIfEmpty(): OptionalValue<T> {
                @Suppress("UNCHECKED_CAST")
                val error = endOfCollectorErrorSingleton as OptionalValue<T>
                // Note: Only "replace" this if it is empty (i.e. don't replace values or errors)
                return error.orElse(this)
            }

            update {
                it.copy(
                    processName = it.processName.applyEndOfCollectorErrorIfEmpty(),
                    packageNames = it.packageNames.applyEndOfCollectorErrorIfEmpty(),
                    userId = it.userId.applyEndOfCollectorErrorIfEmpty(),
                    vmIdentifier = it.vmIdentifier.applyEndOfCollectorErrorIfEmpty(),
                    instructionSet = it.instructionSet.applyEndOfCollectorErrorIfEmpty(),
                    jvmFlags = it.jvmFlags.applyEndOfCollectorErrorIfEmpty(),
                    isNativeDebuggable = it.isNativeDebuggable.applyEndOfCollectorErrorIfEmpty(),
                    isWaitingForDebugger = it.isWaitingForDebugger.applyEndOfCollectorErrorIfEmpty(),
                    features = it.features.applyEndOfCollectorErrorIfEmpty()
                )
            }
        }
    }
}
