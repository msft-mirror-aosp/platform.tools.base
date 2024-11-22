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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

internal class AdbServerControllerImpl(
  private val host: AdbSessionHost,
  configurationFlow: StateFlow<AdbServerConfiguration>,
  processRunner: ProcessRunner = ProcessRunnerImpl(host),
) : AdbServerController {

    private val logger = adbLogger(host)

    /**
     * Lock for accessing and updating [currentState]
     */
    private val stateLock = Any()

    /**
     * The current [State] of this instance. The [start], [stop] and [restart] methods can change
     * the [currentState] at any time.
     */
    private var currentState = State.initial(host, processRunner, configurationFlow)

    /**
     * * Returns `true` after the [start] method has successfully completed, i.e. after ADB server
     * was successfully started.
     *
     * * Returns `false` after the [stop] method has successfully completed, i.e. after ADB server
     * was successfully stopped.
     *
     * Note: If [isStarted] is `false`, the [channelProvider] throws exception
     *
     * Note: If ADB server is killed manually, e.g. by running adb `kill-server` this value still
     * returns `true`
     */
    val isStarted: Boolean
        get() = currentState.isStarted

    override val channelProvider: AdbServerChannelProvider = AdbServerControllerProvider()

    override suspend fun start() {
        transitionCurrentState { start() }
    }

    override suspend fun stop() {
        transitionCurrentState { stop() }
    }

    override fun close() {
        synchronized(stateLock) {
            currentState.close()
        }
    }

    private suspend inline fun transitionCurrentState(transition: State.() -> State) {
        synchronized(stateLock) {
            // Throw if [closed] was called
            currentState.scope.ensureActive()

            // Apply transition and record new state
            currentState = currentState.transition()
            currentState
        }.also {
            // Wait for transition to complete (suspending)
            it.await()
        }
    }

    // TODO: Reuse AdbServerStartupImpl or AdbChannelProviderWithServerStartup for this
    private suspend fun createChannelWithRetryOnFailure(
        connectProvider: AdbServerChannelProvider,
        timeout: Long,
        unit: TimeUnit,
    ): AdbChannel {
        val tracker = TimeoutTracker(host.timeProvider, timeout, unit)
        host.timeProvider.withErrorTimeout(tracker.remainingMills) {
            currentState.waitIsStarted()
        }

        return try {
            connectProvider.createChannel(tracker.remainingNanos, TimeUnit.NANOSECONDS)
        } catch (e: IOException) {
            logger.debug(e) { "Failed `createChannel` on port ${currentState.params.lastUsedConfig}" }
            // Failed to create channel. Try to restart adb server / update configuration and try again.
            host.timeProvider.withErrorTimeout(tracker.remainingMills) {
                restart()
            }
            connectProvider.createChannel(tracker.remainingNanos, TimeUnit.NANOSECONDS)
        }
    }

    /**
     * This will attempt to restart adb server if we previously detected a dropped connection.
     */
    internal suspend fun restart() {
        transitionCurrentState { restart() }
    }

    /**
     * This channel provider relies on the `AdbServerControllerImpl` to start the ADB server.
     * Essentially, some other part of the system needs to call `AdbServerControllerImpl.start`.
     * Once that happens, the ADB server should be running, allowing this provider
     * to establish connections (channels) to it.
     *
     * This server provider can also restart the adb server if the server process dies.
     */
    private inner class AdbServerControllerProvider : AdbServerChannelProvider {

        private val connectProvider =
            AdbServerChannelProvider.createConnectAddresses(host) {
                val port =
                    currentState.params.lastUsedConfig.value?.serverPort
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

    /**
     * This class is used to keep track of transitions the adb server goes through.
     */
    private sealed class State(val params: StateParams) : AutoCloseable {
        /**
         * Immutable parameters shared by all instances of [State]
         */
        class StateParams(
            val host: AdbSessionHost,
            val scope: CoroutineScope,
            val processRunner: ProcessRunner,
            val configurationFlow: StateFlow<AdbServerConfiguration>,
            val isStartedFlow: MutableStateFlow<Boolean>,
            val lastUsedConfig: MutableStateFlow<AdbServerConfiguration?>,
        )


        private val logger = adbLogger(params.host)

        val scope: CoroutineScope
            get() = params.scope

        val processRunner: ProcessRunner
            get() = params.processRunner

        val configurationFlow: StateFlow<AdbServerConfiguration>
            get() = params.configurationFlow

        val isStarted: Boolean
            get() = params.isStartedFlow.value

        override fun close() {
            scope.cancel("${this::class.simpleName} has been closed")
        }

        suspend fun waitIsStarted() {
            params.isStartedFlow.first { it }
        }

        /**
         * Waits for the adb server configuration to be set so that we could start adb server if needed,
         * and so that the [channelProvider] could connect to the correct port.
         */
        suspend fun waitForServerConfigurationAvailable(): AdbServerConfiguration {
            return configurationFlow.first { it.serverPort != null }
        }

        suspend fun runKillServerProcess(path: String, envVars: Map<String, String>): Boolean {
            val command = getAdbStopCommand(path)
            return try {
                processRunner.runProcess(command, envVars)
                true
            } catch (e: IOException) {
                logger.info(e) { "failed running process `$command`" }
                false
            }
        }

        suspend fun runStartServerProcess(
            path: String,
            port: Int,
            envVars: Map<String, String>,
        ): Boolean {
            val command = getAdbLaunchCommand(path, port)
            return try {
                processRunner.runProcess(command, envVars)
                true
            } catch (e: IOException) {
                logger.info(e) { "failed running process `$command`" }
                false
            }
        }

        private fun getAdbLaunchCommand(adbPath: String, adbPort: Int): List<String> {
            return listOf(adbPath, "-P", adbPort.toString(), "start-server")
        }

        private fun getAdbStopCommand(adbPath: String): List<String> {
            return listOf(adbPath, "kill-server")
        }

        /**
         * Initiates the `Start` transition and returns the new [State]
         */
        abstract fun start(): State

        /**
         * Initiates the `Stop` transition and returns the new [State]
         */
        abstract fun stop(): State

        /**
         * Initiates the `restart` transition and returns the new [State]
         */
        abstract fun restart(): State

        /**
         * Waits for the current transition (i.e. [start], [stop] or [restart]) of this
         * state to complete
         */
        abstract suspend fun await()

        companion object {

            fun completedJob(): Deferred<Unit> = CompletableDeferred(Unit)

            /**
             * Returns the initial [State], corresponding to no calls made to [start], [stop] or
             * [restart].
             */
            fun initial(
                host: AdbSessionHost,
                processRunner: ProcessRunner,
                configurationFlow: StateFlow<AdbServerConfiguration>
            ): State {
                val scope = CoroutineScope(host.parentContext + host.ioDispatcher + SupervisorJob())

                val stateParams = StateParams(
                    host = host,
                    scope = scope,
                    processRunner = processRunner,
                    configurationFlow = configurationFlow,
                    isStartedFlow = MutableStateFlow(false),
                    lastUsedConfig = MutableStateFlow<AdbServerConfiguration?>(null))

                return InitialState(stateParams)
            }
        }
    }

    /**
     * The "stopped" state (also initial state). [start] returns a new [StartingState].
     */
    private class InitialState(params: StateParams) : State(params) {

        override fun start(): State {
            return StartingState(params, previousJob = completedJob())
        }

        override fun stop(): State {
            // We are stopped => no-op
            return this
        }

        override fun restart(): State {
            // We are stopped => no-op
            return this
        }

        override suspend fun await() {
            // We are stopped => no-op
        }
    }

    /**
     * The "starting" state, i.e. [State.start] has been called.
     * Note that we don't have an explicit `StartedState`, as this can be represented
     * by this [StartingState] with a completed job.
     */
    private class StartingState(params: StateParams, previousJob: Deferred<Unit>) : State(params) {
        // Job completed with exception (including a cancellation exception)
        @Volatile
        private var jobCompletedWithException = false

        private val startJob: Deferred<Unit> = scope.async {
            // Cancel and wait for previously running job
            previousJob.cancelAndJoin()

            // Start ADB server after waiting for valid configuration
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
            params.lastUsedConfig.update { config }
            params.isStartedFlow.update { true }
        }.also {
            it.invokeOnCompletion { e ->
                jobCompletedWithException = e != null
            }
        }

        override fun start(): State {
            // NOTE: We could use `startJob.isCompleted && startJob.getCompletionExceptionOrNull() != null`
            //  if the API was not experimental.
            return if (jobCompletedWithException) {
                // Previous start attempt failed => try again
                StartingState(params, startJob)
            } else {
                // We are already starting (or started) => no-op
                this
            }
        }

        override fun stop(): State {
            // We are starting (or started) => cancel job and stop the server
            return StoppingState(params, startJob)
        }

        override fun restart(): State {
            return if (startJob.isCompleted) {
                // We are started => cancel job and restart the server
                RestartingState(params, startJob)
            } else {
                // We are not fully started => no-op
                this
            }
        }

        override suspend fun await() {
            startJob.await()
        }
    }

    /**
     * The "stopping" state, i.e. [State.stop] has been called.
     */
    private class StoppingState(params: StateParams, previousJob: Deferred<Unit>) : State(params) {
        // Job completed with exception (including a cancellation exception)
        @Volatile
        private var jobCompletedWithException = false

        private val stopJob: Deferred<Unit> = scope.async {
            // Cancel and wait for previously running job
            previousJob.cancelAndJoin()

            val config = waitForServerConfigurationAvailable()
            val adbFilePath = config.adbFile?.path
            if (!config.isUserManaged && adbFilePath != null && config.serverPort != null) {
                runKillServerProcess(adbFilePath, config.envVars)
            }
            params.isStartedFlow.update { false }
        }.also {
            it.invokeOnCompletion { e ->
                jobCompletedWithException = e != null
            }
        }

        override fun start(): State {
            // We are stopping (or stopped) => Cancel stop operation and start again
            return StartingState(params, stopJob)
        }

        override fun stop(): State {
            // NOTE: We could use `stopJob.isCompleted && stopJob.getCompletionExceptionOrNull() != null`
            //  if the API was not experimental.
            return if (jobCompletedWithException) {
                // Previous stop attempt failed => try again
                StoppingState(params, stopJob)
            } else {
                // We are stopping (or stopped) => no-op
                this
            }
        }

        override fun restart(): State {
            // We are stopping (or stopped) => no-op, as we should only when started
            return this
        }

        override suspend fun await() {
            stopJob.await()
        }
    }

    /**
     * The "starting" state, i.e. [State.start] has been called and is not finished yet.
     */
    private class RestartingState(params: StateParams, previousJob: Deferred<Unit>) : State(params) {
        private val restartJob: Deferred<Unit> = scope.async {
            // Cancel and wait for previously running job
            previousJob.cancelAndJoin()

            // Start ADB server after waiting for valid configuration
            val config = waitForServerConfigurationAvailable()
            val path = config.adbFile?.path
            val port = config.serverPort!!
            if (path == null) {
                // This is a non-restartable channel, but still try using `port` from the config the next
                // time we try to create a channel
                params.lastUsedConfig.update { config }
                return@async
            }

            // TODO: Revisit the code below to match `AndroidDebugBridgeImpl` behavior. E.g. should we
            //  be updating `isStarted` value if `server-kill` succeeds and `server-start` fails
            runKillServerProcess(path, config.envVars)
            runStartServerProcess(path, port, config.envVars)

            params.lastUsedConfig.update { config }
        }

        override fun start(): State {
            // We are restarting => cancel restart and start normally
            return StartingState(params, restartJob)
        }

        override fun stop(): State {
            // We are restarting => cancel restart and stop normally
            return StoppingState(params, restartJob)
        }

        override fun restart(): State {
            return if (restartJob.isCompleted) {
                // Previous restart has completed, but now another restart is triggered => restart
                RestartingState(params, restartJob)
            } else {
                // We are already restarting => no-op
                this
            }
        }

        override suspend fun await() {
            restartJob.await()
        }
    }
}
