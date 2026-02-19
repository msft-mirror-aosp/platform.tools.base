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

import java.net.InetSocketAddress
import java.nio.file.Path
import kotlinx.coroutines.flow.StateFlow

/**
 * Controller that can manage starting/stopping adb server.
 *
 * For manually managed ADB servers, this controller focuses solely on channel creation, leaving server startup and shutdown to the user.
 *
 * It provides a [AdbServerChannelProvider] that can restart adb server when it crashed or was killed by an external user action.
 */
interface AdbServerController : AutoCloseable {

  /**
   * The [AdbServerChannelProvider] this [AdbServerController] implements. The behavior of the channel provider is determined by the [start]
   * and [stop] methods, as well as the current [AdbServerConfiguration]
   */
  val channelProvider: AdbServerChannelProvider

  /**
   * Returns `true` if `start` has been called and was successful. Returns `false` if `start` has not been called or `stop` has been called
   * and was successful.
   */
  val isStarted: Boolean

  /** Start if not started, no-op otherwise */
  suspend fun start()

  /** Stop if started, no-op otherwise */
  suspend fun stop()

  /** Wait until the controller is started */
  suspend fun waitIsStarted()

  /**
   * Returns the remote address of a channel created by `channelProvider`. This value is reset to `null` when controller's [stop] method is
   * called.
   */
  val lastKnownRemoteAddress: InetSocketAddress?

  companion object {

    fun createServerController(host: AdbSessionHost, configurationFlow: StateFlow<AdbServerConfiguration>): AdbServerController {
      return AdbServerControllerImpl(host, configurationFlow)
    }
  }
}

data class AdbServerConfiguration(
  val adbPath: Path?,
  val serverPort: Int?,
  val isUserManaged: Boolean,
  val isUnitTest: Boolean,
  val envVars: Map<String, String>,
) {

  init {
    require(serverPort == null || serverPort > 0) { "If provided, a port value should be positive" }
  }
}
