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
import com.android.adblib.utils.rethrowCancellation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

internal class AdbServerControllerImpl(
    private val host: AdbSessionHost,
    configurationFlow: StateFlow<AdbServerConfiguration>
) : AdbServerController {

    private val logger = adbLogger(host)

    /**
     * Lock for accessing and updating [currentState]
     */
    private val stateLock = Mutex()

    /**
     * The current [State] of this instance. The [start], [stop] and [restart] methods can change
     * the [currentState] at any time.
     */
    private var currentState = State.initial(host, configurationFlow)

    private var currentJob: Deferred<Unit>? = null

    /**
     *  Job [TransitionStatus] is volatile because it's updated from `invokeOnCompletion`.
     *  When the transition starts it is set to `IN_PROGRESS` and when transition ends it's set
     *  to `COMPLETED_OK` or `COMPLETED_FAILURE`.
     */
    @Volatile
    private var currentJobTransitionStatus = TransitionStatus.COMPLETED_OK

    private var lastKnownRemoteAddressStateFlow = MutableStateFlow<InetSocketAddress?>(null)

    /**
     * * Returns `true` after the [start] method has successfully completed, i.e. after ADB server
     * was successfully started.
     *
     * * Returns `false` after the [stop] method has successfully completed, i.e. after ADB server
     * was successfully stopped.
     *
     * Note: If ADB server is killed manually, e.g. by running adb `kill-server` this value still
     * returns `true`
     */
    override val isStarted: Boolean
        get() = currentState.isStarted

    override val channelProvider: AdbServerChannelProvider = AdbServerControllerProvider()

    override suspend fun start() {
        transitionCurrentState(State::start)
    }

    override suspend fun stop() {
        transitionCurrentState(State::stop)
    }

    override val lastKnownRemoteAddress: InetSocketAddress?
        get() = lastKnownRemoteAddressStateFlow.value

    override fun close() {
        currentState.scope.cancel("${this::class.simpleName} has been closed")
    }

    private suspend inline fun transitionCurrentState(transition: State.(TransitionStatus) -> State) {
        val transitionJob = stateLock.withLock {
            // Throw if [closed] was called
            currentState.scope.ensureActive()

            val newState = currentState.transition(currentJobTransitionStatus)

            // Await on the same job if we are transitioning to the same state
            if (currentState == newState) {
                return@withLock currentJob
            }

            currentJob?.cancelAndJoin()

            val newTransitionJob = currentState.scope.async {
                when (newState) {
                    is InitialState -> Unit // should not happen
                    is StartingState -> newState.performStart()
                    is StoppingState -> newState.performStop()
                    is RestartingState -> newState.performRestart()
                }
            }.also {
                currentJobTransitionStatus = TransitionStatus.IN_PROGRESS
            }

            newTransitionJob.invokeOnCompletion { e ->
                currentJobTransitionStatus =
                    if (e == null) TransitionStatus.COMPLETED_OK else TransitionStatus.COMPLETED_FAILURE
            }

            currentState = newState
            currentJob = newTransitionJob
            newTransitionJob
        }

        try {
            transitionJob?.awaitOrThrow()
        } finally {
            lastKnownRemoteAddressStateFlow.update { null }
        }
    }

    /**
     * Awaits for the deferred, but distinguishes between different sources of
     * `CancellationException`s thrown from `await` and treats them differently:
     * - If `CancellationException` is the result of the current Job being cancelled,
     * then simply propagate this exception
     * - If `CancellationException` is thrown by await itself, e.g. when `AdbServerController`'s
     * state transition job was interrupted by the controller's business logic, then
     * throw `IOException` to indicate that a transition failed to complete.
     */
    private suspend fun <T> Deferred<T>.awaitOrThrow(): T {
        return try {
            await()
        } catch (e: CancellationException) {
            // As discussed in kotlin's documentation for `await` method
            // (https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines/-deferred/await.html)
            // the following call throws if the current coroutine was cancelled
            currentCoroutineContext().ensureActive()
            // If we get here then the exception is the result of `await()` itself
            throw IOException("`AdbServerController` operation was interrupted by another operation")
                .apply { addSuppressed(e) }
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
            logger.debug(e) { "Failed `createChannel` on port ${currentState.params.lastUsedConfig.value?.serverPort}" }
            // Failed to create channel. Try to restart adb server / update configuration and try again.
            host.timeProvider.withErrorTimeout(tracker.remainingMills) {
                try {
                    restart()
                } catch (e: IOException) {
                    throw IOException(
                        "`createChannel` failed to restart `AdbServerController` on failure", e
                    )
                }
            }
            connectProvider.createChannel(tracker.remainingNanos, TimeUnit.NANOSECONDS)
        }
    }

    /**
     * This will attempt to restart adb server if we previously detected a dropped connection.
     */
    internal suspend fun restart() {
        transitionCurrentState(State::restart)
    }

    /**
     * This channel provider relies on the `AdbServerControllerImpl` to start the ADB server.
     * Essentially, some other part of the system needs to call `AdbServerControllerImpl.start`.
     * Once that happens, the ADB server should be running, allowing this provider
     * to establish connections (channels) to it.
     *
     * This server provider can also restart the adb server if the server process dies.
     *
     * If the controller has not yet been started `createChannel` will attempt to wait
     * for it to get started before trying to establish the connection, except for when the
     * controller is in a stopped/stopping state in which case it immediately throws `IOException`.
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
            return createChannelWithRetryOnFailure(connectProvider, timeout, unit).also { channel ->
                lastKnownRemoteAddressStateFlow.update {
                    (channel as? AdbSocketChannel)?.remoteAddress
                }
            }
        }
    }

    /**
     * This enum class is used to keep track of current transition state.
     */
    private enum class TransitionStatus {

        IN_PROGRESS,
        COMPLETED_OK,
        COMPLETED_FAILURE,
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
            val configurationFlow: StateFlow<AdbServerConfiguration>,
            val isStartedFlow: MutableStateFlow<Boolean>,
            val lastUsedConfig: MutableStateFlow<AdbServerConfiguration?>,
        )

        private val logger = adbLogger(params.host)

        val scope: CoroutineScope
            get() = params.scope

        val processRunner: ProcessRunner
            get() = params.host.processRunner

        val configurationFlow: StateFlow<AdbServerConfiguration>
            get() = params.configurationFlow

        val isStarted: Boolean
            get() = params.isStartedFlow.value

        override fun close() {
            scope.cancel("${this::class.simpleName} has been closed")
        }

        abstract suspend fun waitIsStarted()

        /**
         * Waits for the adb server configuration to be set so that we could start adb server if needed,
         * and so that the [channelProvider] could connect to the correct port.
         */
        suspend fun waitForServerConfigurationAvailable(): AdbServerConfiguration {
            return configurationFlow.first { it.serverPort != null }
        }

        suspend fun runKillServerProcess(path: Path, port: Int, envVars: Map<String, String>) {
            val commandArgs = getAdbStopCommandArgs(port)
            runAdbProcess(path, commandArgs, envVars, "failed running adb kill server process")
        }

        suspend fun runStartServerProcess(path: Path, port: Int, envVars: Map<String, String>) {
            val commandArgs = getAdbLaunchCommandArgs(port)
            runAdbProcess(path, commandArgs, envVars, "failed running adb start server process")
        }

        private suspend fun runAdbProcess(
            path: Path,
            commandArgs: List<String>,
            envVars: Map<String, String>,
            failedLogMessage: String
        ) {
            try {
                processRunner.runProcess(path, commandArgs, envVars)
            } catch (e: Throwable) {
                e.rethrowCancellation()
                logger.info(e) { "$failedLogMessage: `$path $commandArgs`" }
                throw e
            }
        }

        private fun getAdbLaunchCommandArgs(adbPort: Int): List<String> {
            return if (adbPort == DEFAULT_ADB_HOST_PORT) {
                listOf("start-server")
            } else {
                listOf("-P", adbPort.toString(), "start-server")
            }
        }

        private fun getAdbStopCommandArgs(adbPort: Int): List<String> {
            return if (adbPort == DEFAULT_ADB_HOST_PORT) {
                listOf("kill-server")
            } else {
                listOf("-P", adbPort.toString(), "kill-server")
            }
        }

        /**
         * Initiates the `Start` transition and returns the new [State]
         */
        abstract fun start(currentTransitionStatus: TransitionStatus): State

        /**
         * Initiates the `Stop` transition and returns the new [State]
         */
        abstract fun stop(currentTransitionStatus: TransitionStatus): State

        /**
         * Initiates the `restart` transition and returns the new [State]
         */
        abstract fun restart(currentTransitionStatus: TransitionStatus): State

        companion object {

            /**
             * Returns the initial [State], corresponding to no calls made to [start], [stop] or
             * [restart].
             */
            fun initial(
                host: AdbSessionHost,
                configurationFlow: StateFlow<AdbServerConfiguration>
            ): State {
                val scope = CoroutineScope(host.parentContext + host.ioDispatcher + SupervisorJob())

                val stateParams = StateParams(
                    host = host,
                    scope = scope,
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

        override suspend fun waitIsStarted() {
            params.isStartedFlow.first { it }
        }

        override fun start(currentTransitionStatus: TransitionStatus): State {
            return StartingState(params)
        }

        override fun stop(currentTransitionStatus: TransitionStatus): State {
            // We are stopped => no-op
            return this
        }

        override fun restart(currentTransitionStatus: TransitionStatus): State {
            // We are stopped => no-op
            return this
        }
    }

    /**
     * The "starting" state, i.e. [State.start] has been called.
     * Note that we don't have an explicit `StartedState`, as this can be represented
     * by this [StartingState] with a completed job.
     */
    private class StartingState(params: StateParams) : State(params) {

        override suspend fun waitIsStarted() {
            params.isStartedFlow.first { it }
        }

        override fun start(currentTransitionStatus: TransitionStatus): State {
            return if (currentTransitionStatus == TransitionStatus.COMPLETED_FAILURE) {
                // Retry starting the server
                StartingState(params)
            } else {
                // Already starting or started
                this
            }
        }

        override fun stop(currentTransitionStatus: TransitionStatus): State {
            // We are starting (or started) => stop the server
            return StoppingState(params)
        }

        override fun restart(currentTransitionStatus: TransitionStatus): State {
            return when (currentTransitionStatus) {
                TransitionStatus.IN_PROGRESS ->
                    // We are not fully started => no-op
                    this

                TransitionStatus.COMPLETED_OK ->
                    // We are successfully started => restart the server
                    RestartingState(params)

                TransitionStatus.COMPLETED_FAILURE ->
                    // We are not succussfully started => try again
                    StartingState(params)
            }
        }

        suspend fun performStart() {

            val config = waitForServerConfigurationAvailable()
            val path = config.adbPath
            val port = config.serverPort
            val isUserManaged = config.isUserManaged
            val isUnitTest = config.isUnitTest

            if (!isUserManaged && !isUnitTest) {
                if (path == null) {
                    throw IllegalStateException("adb path must be provided")
                }
                if (port != null) {
                    runStartServerProcess(path, port, config.envVars)
                }
            }
            params.lastUsedConfig.update { config }
            params.isStartedFlow.update { true }
        }
    }

    /**
     * The "stopping" state, i.e. [State.stop] has been called.
     */
    private class StoppingState(params: StateParams) : State(params) {

        override suspend fun waitIsStarted() {
            throw IOException("`AdbServerController` is in a stopping/stopped state")
        }

        override fun start(currentTransitionStatus: TransitionStatus): State {
            // We are stopping (or stopped) => Cancel stop operation and start again
            return StartingState(params)
        }

        override fun stop(currentTransitionStatus: TransitionStatus): State {
            return if (currentTransitionStatus == TransitionStatus.COMPLETED_FAILURE) {
                // Retry stopping the server
                StoppingState(params)
            } else {
                // We are already stopping (or stopped)
                this
            }
        }

        override fun restart(currentTransitionStatus: TransitionStatus): State {
            // We are stopping (or stopped) => no-op, as we should only when started
            return this
        }

        suspend fun performStop() {

            val config = waitForServerConfigurationAvailable()
            val adbFilePath = config.adbPath
            if (!config.isUserManaged && !config.isUnitTest) {
                if (adbFilePath == null) {
                    throw IllegalStateException("adb path must be provided")
                }
                if (config.serverPort != null) {
                    runKillServerProcess(adbFilePath, config.serverPort, config.envVars)
                }
            }
            params.isStartedFlow.update { false }
        }
    }

    /**
     * The "restarting" state, i.e. [State.restart] has been called and is not finished yet.
     */
    private class RestartingState(params: StateParams) : State(params) {

        override suspend fun waitIsStarted() {
            params.isStartedFlow.first { it }
        }

        override fun start(currentTransitionStatus: TransitionStatus): State {
            // We are restarting => cancel restart and start normally
            return StartingState(params)
        }

        override fun stop(currentTransitionStatus: TransitionStatus): State {
            // We are restarting => cancel restart and stop normally
            return StoppingState(params)
        }

        override fun restart(currentTransitionStatus: TransitionStatus): State {

            return if (currentTransitionStatus == TransitionStatus.IN_PROGRESS) {
                // Current restart operation has not completed => no-op
                this
            } else {
                RestartingState(params)
            }
        }

        suspend fun performRestart() {
            // Start ADB server after waiting for valid configuration
            val config = waitForServerConfigurationAvailable()
            val adbFilePath = config.adbPath
            val port = config.serverPort!!
            if (config.isUserManaged || config.isUnitTest) {
                // This is a non-restartable channel, but still try using `port` from the config the next
                // time we try to create a channel
                params.lastUsedConfig.update { config }
                return
            }

            // TODO: Revisit the code below to match `AndroidDebugBridgeImpl` behavior. E.g. should we
            //  be updating `isStarted` value if `server-kill` succeeds and `server-start` fails
            if (adbFilePath == null) {
                throw IllegalStateException("adb path must be provided")
            }
            runKillServerProcess(adbFilePath, port, config.envVars)
            runStartServerProcess(adbFilePath, port, config.envVars)

            params.lastUsedConfig.update { config }
        }
    }
}
