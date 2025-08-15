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

import com.android.adblib.adbLogger
import com.android.adblib.tools.debugging.JdwpProcess
import com.android.adblib.tools.debugging.JdwpProcessProperties
import com.android.adblib.tools.debugging.JdwpProcessPropertiesCollector
import com.android.adblib.tools.debugging.jdwpPropertiesCollector
import com.android.adblib.utils.logIOCompletionErrors
import com.android.adblib.withProcessPrefix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class JdwpProcessPropertiesCollectorDelegate(
  override val process: JdwpProcess,
  private val processProvider: AbstractJdwpProcessDelegateProvider
) : JdwpProcessPropertiesCollector {
    private val logger = adbLogger(process.device.session).withProcessPrefix(process.device, process.pid)

    private val mutableStateFlow = MutableStateFlow(JdwpProcessProperties(pid = process.pid))

    private val lazyStartMonitoring by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        forwardStateFlowFromDelegateProcess()
    }

    override val stateFlow: StateFlow<JdwpProcessProperties> = mutableStateFlow.asStateFlow()
        get() {
            lazyStartMonitoring
            return field
        }

    private fun forwardStateFlowFromDelegateProcess() {
        logger.debug { "Forwarding JDWP properties from delegate process" }
        process.scope.launch {
            runCatching {
                processProvider.abstractJdwpProcess().also { delegateProcess ->
                    logger.debug { "Acquired delegate process, starting forwarding" }
                    delegateProcess.jdwpPropertiesCollector.stateFlow.collect { newProperties ->
                        logger.verbose { "Forwarding new JDWP process properties: $newProperties" }
                        mutableStateFlow.update { newProperties }
                    }
                }
            }.onFailure { throwable ->
                logger.logIOCompletionErrors(throwable)
            }
        }
    }
}
