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

import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.property
import com.android.adblib.tools.AdbLibToolsProperties.PROCESS_PROPERTIES_COLLECTOR_USE_APP_INFO_IF_AVAILABLE
import com.android.adblib.tools.debugging.impl.JdwpProcessPropertiesCollectorImpl
import kotlinx.coroutines.flow.StateFlow

/**
 * A [JdwpProcessPropertiesCollector] is responsible for collecting properties of
 * a given [JdwpProcess] in a [StateFlow] of [JdwpProcessProperties].
 */
interface JdwpProcessPropertiesCollector {

    /**
     * The [JdwpProcess] this collector applies to
     */
    val process: JdwpProcess

    /**
     * A [StateFlow] of [JdwpProcessProperties] that describes the current process information.
     *
     * Note: once process has exited, the flow stops being updated.
     */
    val stateFlow: StateFlow<JdwpProcessProperties>
}

/**
 * A [StateFlow] of [JdwpProcessProperties] that describes the current process information.
 *
 * Note: once the process [JdwpProcess.scope] has completed, the flow stops being updated.
 */
val JdwpProcess.jdwpPropertiesCollector: JdwpProcessPropertiesCollector
    get() = this.cache.getOrPut(jdwpProcessPropertiesCollectorKey) {
        JdwpProcessPropertiesCollectorImpl(this)
    }

private val jdwpProcessPropertiesCollectorKey =
    CoroutineScopeCache.Key<JdwpProcessPropertiesCollector>("jdwpProcessPropertiesCollectorKey")

/**
 * A [StateFlow] of [JdwpProcessProperties] that describes the current process information.
 *
 * Note: once [scope] has completed, the flow stops being updated.
 */
val JdwpProcess.propertiesFlow: StateFlow<JdwpProcessProperties>
    get() = jdwpPropertiesCollector.stateFlow

/**
 * Returns a snapshot of the current [JdwpProcessProperties] for this process.
 *
 * Note: This is a shortcut for [processPropertiesFlow.value][JdwpProcess.propertiesFlow].
 *
 * @see JdwpProcess.propertiesFlow
 */
val JdwpProcess.properties: JdwpProcessProperties
    get() = propertiesFlow.value

/**
 * Similar to [isAppInfoSupported], but also checks
 * [PROCESS_PROPERTIES_COLLECTOR_USE_APP_INFO_IF_AVAILABLE]
 */
internal suspend fun ConnectedDevice.useAppInfoForProcessProperties(): Boolean {
    return cache.getOrPutSuspending(useAppInfoKey) {
        session.property(PROCESS_PROPERTIES_COLLECTOR_USE_APP_INFO_IF_AVAILABLE)
                && isAppInfoSupported()
    }
}

private val useAppInfoKey = CoroutineScopeCache.Key<Boolean>("useAppInfoKey")

