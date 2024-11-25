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
package com.android.adblib

import kotlinx.coroutines.flow.StateFlow
import java.nio.file.Path

/**
 * Controller that can manage starting/stopping adb server.
 *
 * It provides a [AdbServerChannelProvider] that can restart adb server when it crashed or was
 * killed by an external user action.
 */
interface AdbServerController : AutoCloseable {

    /**
     * The [AdbServerChannelProvider] this [AdbServerController] implements. The behavior of the
     * channel provider is determined by the [start] and [stop] methods, as well as the current
     * [AdbServerConfiguration]
     */
    val channelProvider: AdbServerChannelProvider

    /** Start if not started, no-op otherwise */
    suspend fun start()

    /** Stop if started, no-op otherwise */
    suspend fun stop()

    companion object {

        fun createServerController(
            host: AdbSessionHost,
            configurationFlow: StateFlow<AdbServerConfiguration>
        ): AdbServerController {
            return AdbServerControllerImpl(host, configurationFlow)
        }
    }
}

data class AdbServerConfiguration(
    val adbPath: Path?,
    val serverPort: Int?,
    val isUserManaged: Boolean,
    val isUnitTest: Boolean,
    val envVars: Map<String, String>
) {

    init {
        require(serverPort == null || serverPort > 0) { "If provided, a port value should be positive" }
    }
}
