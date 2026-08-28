/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.tools.deployer

import com.android.adblib.AdbLoggerFactory
import com.android.adblib.AdbServerConfiguration
import com.android.adblib.AdbServerController
import com.android.adblib.AdbSession
import com.android.adblib.AdbSessionHost
import com.android.adblib.ConnectedDevice
import com.android.adblib.connectedDevicesTracker
import com.android.adblib.serialNumber
import com.android.adblib.waitUntilOnline
import com.android.utils.ILogger
import com.android.utils.StdLogger
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

private const val DEFAULT_ADB_HOST_PORT = 5037

internal class DeployerRunnerHost(override val loggerFactory: AdbLoggerFactory) : AdbSessionHost() {
  override val isEventDispatchThread: Boolean = false
}

/**
 * Creates and starts an [AdbServerController] configured using the provided [adbExecutablePath] and [logLevel].
 *
 * If [adbExecutablePath] is provided (e.g., via the `--adb` command line option), it resolves the path to the ADB binary and manages
 * starting the ADB server process (`isUserManaged = false`).
 *
 * If [adbExecutablePath] is `null`, the ADB server is assumed to be user-managed (`isUserManaged = true`) and already running on
 * [adbServerPort], so the controller will connect to it without attempting to spawn a new server process.
 */
fun createAndStartAdbServerControllerBlocking(
  adbExecutablePath: String?,
  adbServerPort: Int,
  logLevel: StdLogger.Level,
): AdbServerController {
  val adbServerConfig =
    if (adbExecutablePath != null) {
      val adbPath = getAdbPath(adbExecutablePath)
      AdbServerConfiguration(adbPath = adbPath, serverPort = adbServerPort, isUserManaged = false, isUnitTest = false, envVars = emptyMap())
    } else {
      AdbServerConfiguration(adbPath = null, serverPort = adbServerPort, isUserManaged = true, isUnitTest = false, envVars = emptyMap())
    }
  return runBlocking { createAndStartAdbServerController(adbServerConfig, logLevel) }
}

/** Creates and starts an [AdbServerController] configured with [config]. */
suspend fun createAndStartAdbServerController(config: AdbServerConfiguration, logLevel: StdLogger.Level): AdbServerController {
  val loggerFactory = DeployerRunnerLoggerFactory(logLevel)
  val host = DeployerRunnerHost(loggerFactory)

  val controller = AdbServerController.createServerController(host, MutableStateFlow(config))
  try {
    controller.start()
  } catch (t: Throwable) {
    controller.close()
    host.close()
    throw t
  }
  return controller
}

/** Creates an [AdbSession] using the given [AdbServerController]'s channel provider. */
fun createAdbSession(controller: AdbServerController, logLevel: StdLogger.Level): AdbSession {
  val loggerFactory = DeployerRunnerLoggerFactory(logLevel)
  val host = DeployerRunnerHost(loggerFactory)
  return AdbSession.create(host = host, channelProvider = controller.channelProvider)
}

/** Returns the resolved absolute [Path] for the adb executable, or searches on `PATH` if not specified. */
fun getAdbPath(adbExecutablePath: String?): Path? {
  return adbExecutablePath?.takeIf { it.isNotBlank() }?.let { Paths.get(it).toAbsolutePath() } ?: findAdbOnPath()
}

private fun findAdbOnPath(): Path? {
  val os = System.getProperty("os.name") ?: ""
  val executableName = if (os.startsWith("Windows", ignoreCase = true)) "adb.exe" else "adb"
  val pathEnv = System.getenv("PATH") ?: return null
  for (dir in pathEnv.split(File.pathSeparator)) {
    val file = Paths.get(dir).resolve(executableName).toAbsolutePath()
    if (Files.isRegularFile(file)) {
      return file
    }
  }
  return null
}

/**
 * Returns the port where the ADB server should be launched or connected to.
 *
 * Note: This logic attempts to match the ADB server port lookup logic in `AdbLibAndroidDebugBridge.getAdbServerPort()`.
 */
fun getAdbServerPort(logger: ILogger): Int {
  val portSystemProperty =
    try {
      System.getProperty("ANDROID_ADB_SERVER_PORT")
    } catch (e: SecurityException) {
      logger.warning("No access to system properties allowed by current security manager: ${e.message}")
      null
    }

  if (portSystemProperty != null) {
    try {
      val port = portSystemProperty.toInt()
      if (port > 0) return port
      logger.warning("Invalid value ($portSystemProperty) for ANDROID_ADB_SERVER_PORT system property: port must be positive.")
    } catch (e: NumberFormatException) {
      logger.warning("Invalid value ($portSystemProperty) for ANDROID_ADB_SERVER_PORT system property: ${e.message}")
    }
  }

  try {
    val portEnvVariable = System.getenv("ANDROID_ADB_SERVER_PORT")
    if (portEnvVariable != null) {
      try {
        val port = portEnvVariable.toInt()
        if (port > 0) return port
        logger.warning("Invalid value ($portEnvVariable) for ANDROID_ADB_SERVER_PORT environment variable: port must be positive.")
      } catch (e: NumberFormatException) {
        logger.warning("Invalid value ($portEnvVariable) for ANDROID_ADB_SERVER_PORT environment variable: ${e.message}")
      }
    }
  } catch (e: SecurityException) {
    logger.warning(
      "No access to env variables allowed by current security manager: ${e.message}. If ANDROID_ADB_SERVER_PORT is set, it is being ignored."
    )
  }

  return DEFAULT_ADB_HOST_PORT
}

/**
 * Returns matching connected devices from [AdbSession], or an empty list if the device tracker fails to get a list of connected devices
 * within [timeoutMs].
 *
 * If [deviceSerials] is empty, waits for at least one device to be connected and returns the first connected device once it is online. If
 * [deviceSerials] is non-empty, waits until all specified device serials are present in the connected devices list and online, and returns
 * only the matching devices.
 */
internal suspend fun waitForConnectedDevices(
  session: AdbSession,
  deviceSerials: List<String>,
  timeoutMs: Long,
): @JvmSuppressWildcards List<ConnectedDevice> {
  return withTimeoutOrNull(timeoutMs.milliseconds) {
    val targetDevices =
      if (deviceSerials.isEmpty()) {
        listOf(session.connectedDevicesTracker.connectedDevices.first { it.isNotEmpty() }.first())
      } else {
        val connectedDevicesList =
          session.connectedDevicesTracker.connectedDevices.first { devices ->
            val connectedSerials = devices.map { it.serialNumber }.toSet()
            deviceSerials.all { it in connectedSerials }
          }
        connectedDevicesList.filter { it.serialNumber in deviceSerials }
      }
    for (device in targetDevices) {
      device.waitUntilOnline()
    }
    targetDevices
  } ?: emptyList()
}
