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

import com.android.adblib.impl.TimeoutTracker
import com.android.adblib.impl.channels.runInterruptibleIO
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AdbServerControllerImpl(
  private val host: AdbSessionHost,
  private val configurationFlow: StateFlow<AdbServerConfiguration>,
  private val processRunner: ProcessRunner = ProcessRunnerImpl(host),
) : AdbServerController {

  private val logger = adbLogger(host)

  private val scope = CoroutineScope(host.parentContext + host.ioDispatcher + SupervisorJob())

  private val startStopMutex = Mutex()

  @Volatile private var startJob: Deferred<Unit>? = null

  @Volatile private var stopJob: Deferred<Unit>? = null

  @Volatile private var restartJob: Deferred<Unit>? = null

  @Volatile private var lastUsedConfig: AdbServerConfiguration? = null

  /**
   * Returns true is ADB server has been started and hasn't been yet explicitly stopped, e.g. by
   * calling `AndroidDebugBridge.disconnectBridge` method. If the server is killed manually, e.g. by
   * running adb `kill-server` this value may still return `true`
   */
  var isStarted: Boolean
    get() = isStartedFlow.value
    private set(value) {
      isStartedFlow.value = value
    }

  private var isStartedFlow = MutableStateFlow(false)

  override val channelProvider: AdbServerChannelProvider = AdbServerControllerProvider()

  override suspend fun start() {
    val startJob =
      startStopMutex.withLock {
        stopJob?.cancelAndJoin()
        stopJob = null
        restartJob?.cancelAndJoin()
        restartJob = null

        startJob
          ?: scope
            .async {
              val config = waitForServerConfigurationAvailable()
              val path = config.adbFile?.path
              val port = config.serverPort
              val isUserManaged = config.isUserManaged
              val isUnitTest = config.isUnitTest
              if (!isUserManaged && !isUnitTest) {
                if (path != null && port != null) {
                  runStartServerProcess(path, port, config.envVars)
                }
              }
              lastUsedConfig = config
              isStarted = true
            }
            .also { startJob = it }
      }
    startJob.await()
  }

  override suspend fun stop() {
    val stopJob =
      startStopMutex.withLock {
        startJob?.cancelAndJoin()
        startJob = null
        restartJob?.cancelAndJoin()
        restartJob = null

        stopJob
          ?: scope
            .async {
              val config = waitForServerConfigurationAvailable()
              val adbFilePath = config.adbFile?.path
              if (!config.isUserManaged && adbFilePath != null && config.serverPort != null) {
                runKillServerProcess(adbFilePath, config.envVars)
              }
              isStarted = false
            }
            .also { stopJob = it }
      }
    stopJob.await()
  }

  override fun close() {
    scope.cancel("${this::class.simpleName} has been closed")
  }

  /** This will attempt to restart adb server if we previously detected a dropped connection. */
  internal suspend fun restart() {

    val restartJob =
      startStopMutex.withLock {
        // Do not restart if server start or stop is in progress. Just wait for these jobs
        // to complete, so that retrying `openChannel` would reflect the correct server state.
        if (startJob?.isActive == true || stopJob?.isActive == true) {
          startJob?.join()
          stopJob?.join()
          return
        }

        restartJob
          ?: scope
            .async {
              if (!isStarted) {
                return@async
              }

              val config = waitForServerConfigurationAvailable()
              val path = config.adbFile?.path
              val port = config.serverPort!!
              if (path == null) {
                // This is a non-restartable channel, but still try using `port` from the
                // config the next time we try to create a channel
                if (lastUsedConfig != config) {
                  lastUsedConfig = config
                  return@async
                }
                return@async
              }

              // TODO: Revisit the code below to match `AndroidDebugBridgeImpl` behavior. E.g.
              //  should we be updating `isStarted` value if `server-kill` succeeds and
              //  `server-start` fails
              runKillServerProcess(path, config.envVars)
              runStartServerProcess(path, port, config.envVars)

              lastUsedConfig = config
            }
            .also {
              restartJob = it
              it.invokeOnCompletion { restartJob = null }
            }
      }
    return restartJob.await()
  }

  /**
   * Waits for the adb server configuration to be set so that we could start adb server if needed,
   * and so that the [channelProvider] could connect to the correct port.
   */
  private suspend fun waitForServerConfigurationAvailable(): AdbServerConfiguration {
    return configurationFlow.first { it.serverPort != null }
  }

  private suspend fun runKillServerProcess(path: String, envVars: Map<String, String>): Boolean {
    val command = getAdbStopCommand(path)
    return try {
      processRunner.runProcess(command, envVars)
      true
    } catch (e: IOException) {
      logger.info { "failed running process `$command`" }
      false
    }
  }

  private suspend fun runStartServerProcess(
    path: String,
    port: Int,
    envVars: Map<String, String>,
  ): Boolean {
    val command = getAdbLaunchCommand(path, port)
    return try {
      processRunner.runProcess(command, envVars)
      true
    } catch (e: IOException) {
      logger.info { "failed running process `$command`" }
      false
    }
  }

  private fun getAdbLaunchCommand(adbPath: String, adbPort: Int): List<String> {
    return listOf(adbPath, "-P", adbPort.toString(), "start-server")
  }

  private fun getAdbStopCommand(adbPath: String): List<String> {
    return listOf(adbPath, "kill-server")
  }

  // TODO: Reuse AdbServerStartupImpl or AdbChannelProviderWithServerStartup for this
  private suspend fun createChannelWithRetryOnFailure(
    connectProvider: AdbServerChannelProvider,
    timeout: Long,
    unit: TimeUnit,
  ): AdbChannel {
    val tracker = TimeoutTracker(host.timeProvider, timeout, unit)
    host.timeProvider.withErrorTimeout(tracker.remainingMills) { isStartedFlow.first { it } }

    return try {
      connectProvider.createChannel(tracker.remainingNanos, TimeUnit.NANOSECONDS)
    } catch (e: IOException) {
      logger.debug(e) { "Failed `createChannel` on port $lastUsedConfig" }
      // Failed to create channel. Try to restart adb server / update configuration and try again.
      host.timeProvider.withErrorTimeout(tracker.remainingMills) { restart() }
      connectProvider.createChannel(tracker.remainingNanos, TimeUnit.NANOSECONDS)
    }
  }

  /**
   * This channel provider relies on the `AdbServerControllerImpl` to start the ADB server.
   * Essentially, some other part of the system needs to call `AdbServerControllerImpl.start`. Once
   * that happens, the ADB server should be running, allowing this provider to establish connections
   * (channels) to it.
   *
   * This server provider can also restart the adb server if the server process dies.
   */
  private inner class AdbServerControllerProvider : AdbServerChannelProvider {
    private val connectProvider =
      AdbServerChannelProvider.createConnectAddresses(host) {
        val port =
          lastUsedConfig?.serverPort
            ?: throw IllegalStateException("lastUsedConfig serverPort is null")
        listOf(InetSocketAddress("127.0.0.1", port), InetSocketAddress("::1", port))
      }

    override suspend fun createChannel(timeout: Long, unit: TimeUnit): AdbChannel {
      return createChannelWithRetryOnFailure(connectProvider, timeout, unit)
    }
  }

  interface ProcessRunner {
    suspend fun runProcess(command: List<String>, envVars: Map<String, String>)
  }

  class ProcessRunnerImpl(private val host: AdbSessionHost) : ProcessRunner {
    private val logger = adbLogger(host)

    override suspend fun runProcess(command: List<String>, envVars: Map<String, String>) {
      runInterruptibleIO(host.blockingIoDispatcher) {
        logger.info { "runProcess: ${command.joinToString(" ")}" }
        val processBuilder = ProcessBuilder(command)
        val env = processBuilder.environment()
        envVars.forEach { (key, value) -> env[key] = value }
        processBuilder.redirectErrorStream(true)

        val process = processBuilder.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        logger.debug { output }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
          throw IOException("adb ${command.joinToString(" ")} failed. Exit code: $exitCode")
        }
      }
    }
  }
}
