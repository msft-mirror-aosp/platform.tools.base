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

import com.android.adblib.testingutils.CloseablesRule
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProvider
import com.android.adblib.testingutils.TestingAdbSessionHost
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import java.nio.file.Paths

class AdbServerControllerImplTest {

    @JvmField
    @Rule
    val closeables = CloseablesRule()

    @JvmField
    @Rule
    var exceptionRule: ExpectedException = ExpectedException.none()

    private val configFlow =
        MutableStateFlow(
            AdbServerConfiguration(
                adbPath = null,
                serverPort = null,
                isUserManaged = false,
                isUnitTest = true,
                envVars = emptyMap(),
            )
        )

    private fun <T : AutoCloseable> registerCloseable(item: T): T {
        return closeables.register(item)
    }

    private val fakeAdb =
        registerCloseable(
            FakeAdbServerProvider().also { it.installDefaultCommandHandlers() }.build().start()
        )
    private val host = registerCloseable(TestingAdbSessionHost())

    @Test
    fun testNotStarted() {
        val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
        assertFalse(controller.isStarted)
    }

    @Test
    fun testCanConnectToExistingAdbServer(): Unit = runBlockingWithTimeout {
        // Prepare
        val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
        configFlow.update { it.copy(serverPort = fakeAdb.port) }

        // Act / Assert
        controller.start()
        // Can create channel
        registerCloseable(controller.channelProvider.createChannel())
        assertTrue(controller.isStarted)
    }

    @Test
    fun testCreateChannelThrowsTimeoutException_whenTimesOutWaitingForControllerIsStarted(): Unit =
        runBlockingWithTimeout {
            // Prepare
            val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
            exceptionRule.expect(TimeoutException::class.java)

            // Act
            // createChannel call will timeout, because the controller has not been started
            registerCloseable(controller.channelProvider.createChannel(50, TimeUnit.MILLISECONDS))
        }

    @Test
    fun testCreateChannelThrowsTimeoutException_whenTimesOutOnRestart(): Unit =
        runBlockingWithTimeout {
            // Prepare
            val processRunner = FakeProcessRunner(100)
            val controller =
                registerCloseable(
                    AdbServerControllerImpl(
                        host,
                        configFlow,
                        processRunner = processRunner
                    )
                )
            configFlow.update {
                it.copy(
                    adbPath = Paths.get(ADB_FILE_PATH),
                    serverPort = fakeAdb.port,
                    isUnitTest = false
                )
            }
            controller.start()
            // Close fakeAdb server to force `restart()` call to be triggered
            fakeAdb.close()
            exceptionRule.expect(TimeoutException::class.java)

            // Act
            // createChannel call will timeout, because the `restart()` takes 100ms
            registerCloseable(controller.channelProvider.createChannel(50, TimeUnit.MILLISECONDS))
        }

    @Test
    fun testStartWaitsForAdbServerConfiguration(): Unit = runBlockingWithTimeout {
        // Prepare
        val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
        configFlow.update { it.copy(serverPort = fakeAdb.port) }
        var channel: AdbChannel? = null
        launch { channel = registerCloseable(controller.channelProvider.createChannel()) }

        // Act / Assert
        delay(50)
        assertNull(channel)
        controller.start()
        yieldUntil { channel != null }
        assertNotNull(channel)
    }

    @Test
    fun testCreateChannelWaitsForControllerStart(): Unit = runBlockingWithTimeout {
        // Prepare
        val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
        launch { controller.start() }

        // Act
        delay(50)
        assertFalse(controller.isStarted)
        configFlow.update { it.copy(serverPort = fakeAdb.port) }
        yieldUntil { controller.isStarted }
        assertTrue(controller.isStarted)
    }

    @Test
    fun testCanReconnect(): Unit = runBlockingWithTimeout {
        // Prepare
        val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
        configFlow.update { it.copy(serverPort = fakeAdb.port) }
        controller.start()

        // Act / Assert
        // Can create a channel
        registerCloseable(controller.channelProvider.createChannel())

        // Act
        // Kill adb server
        fakeAdb.close()
        // Start a new FakeAdbServer. `controller.channelProvider.createChannel()` should now be able
        // to reconnect to new server once we update controller's configuration
        val newFakeAdb =
            registerCloseable(
                FakeAdbServerProvider().also { it.installDefaultCommandHandlers() }.build().start()
            )
        configFlow.update { it.copy(serverPort = newFakeAdb.port) }
        // Assert: `controller.channelProvider.createChannel()` should be able to reconnect to a new
        // server port.
        controller.channelProvider.createChannel()
    }

    @Test
    fun testCanStartAndStopAdbServerFromFile(): Unit = runBlockingWithTimeout {
        // Prepare
        val processRunner = FakeProcessRunner()
        val controller =
            registerCloseable(
                AdbServerControllerImpl(
                    host,
                    configFlow,
                    processRunner = processRunner
                )
            )
        configFlow.update {
            it.copy(
                adbPath = Paths.get(ADB_FILE_PATH),
                serverPort = PORT,
                isUnitTest = false
            )
        }

        // Act
        controller.start()

        // Assert
        assertTrue(controller.isStarted)
        assertEquals(START_COMMAND, processRunner.lastCommand)

        // Act
        controller.stop()
        assertFalse(controller.isStarted)
        assertEquals(STOP_COMMAND, processRunner.lastCommand)
    }

    @Test
    fun testRestartIsNoop_whenStartIsInProgress(): Unit = runBlockingWithTimeout {
        // Prepare
        val processRunner = FakeProcessRunner(50)
        val controller =
            registerCloseable(
                AdbServerControllerImpl(
                    host,
                    configFlow,
                    processRunner = processRunner
                )
            )
        configFlow.update {
            it.copy(
                adbPath = Paths.get(ADB_FILE_PATH),
                serverPort = PORT,
                isUnitTest = false
            )
        }

        // Act
        val startJob = launch { controller.start() }
        val restartJob = launch {
            // delay a little to make sure start job is in progress when we trigger restart
            delay(10)
            controller.restart()
        }
        startJob.join()
        restartJob.join()

        // Assert
        assertTrue(controller.isStarted)
        assertContentEquals(listOf(START_COMMAND), processRunner.allCommands)
    }

    @Test
    fun testRestartIsNoop_whenStopIsInProgress(): Unit = runBlockingWithTimeout {
        // Prepare
        val processRunner = FakeProcessRunner(50)
        val controller =
            registerCloseable(
                AdbServerControllerImpl(
                    host,
                    configFlow,
                    processRunner = processRunner
                )
            )
        configFlow.update {
            it.copy(
                adbPath = Paths.get(ADB_FILE_PATH),
                serverPort = PORT,
                isUnitTest = false
            )
        }
        controller.start()
        processRunner.reset()

        // Act
        val stopJob = launch { controller.stop() }
        val restartJob = launch {
            // delay a little to make sure stop job is in progress when we trigger restart
            delay(10)
            controller.restart()
        }
        stopJob.join()
        restartJob.join()

        // Assert
        assertFalse(controller.isStarted)
        assertContentEquals(listOf(STOP_COMMAND), processRunner.allCommands)
    }

    @Test
    fun testOnlyOneAdbServerRestartIsTriggered_whenConcurrentRestarts(): Unit =
        runBlockingWithTimeout {
            // Prepare
            val processRunner = FakeProcessRunner(50)
            val controller =
                registerCloseable(
                    AdbServerControllerImpl(
                        host,
                        configFlow,
                        processRunner = processRunner
                    )
                )
            configFlow.update {
                it.copy(adbPath = Paths.get(ADB_FILE_PATH), serverPort = PORT, isUnitTest = false)
            }
            controller.start()
            processRunner.reset()

            // Act: queue up multiple restarts at the same time
            val restartJobs = List(5) { launch { controller.restart() } }
            restartJobs.joinAll()

            // Assert
            assertTrue(controller.isStarted)
            assertContentEquals(listOf(STOP_COMMAND, START_COMMAND), processRunner.allCommands)
        }

    @Test
    fun testOnlyOneAdbServerStartIsTriggered_whenStartIsCalledConcurrently(): Unit =
        runBlockingWithTimeout {
            // Prepare
            val processRunner = FakeProcessRunner(50)
            val controller =
                registerCloseable(
                    AdbServerControllerImpl(
                        host,
                        configFlow,
                        processRunner = processRunner
                    )
                )
            configFlow.update {
                it.copy(adbPath = Paths.get(ADB_FILE_PATH), serverPort = PORT, isUnitTest = false)
            }

            // Act: queue up multiple start calls at the same time
            val restartJobs = List(5) { launch { controller.start() } }
            restartJobs.joinAll()

            // Assert
            assertTrue(controller.isStarted)
            assertContentEquals(listOf(START_COMMAND), processRunner.allCommands)
        }

    @Test
    fun testOnlyOneAdbServerStopIsTriggered_whenStopIsCalledConcurrently(): Unit =
        runBlockingWithTimeout {
            // Prepare
            val processRunner = FakeProcessRunner(50)
            val controller =
                registerCloseable(
                    AdbServerControllerImpl(
                        host,
                        configFlow,
                        processRunner = processRunner
                    )
                )
            configFlow.update {
                it.copy(adbPath = Paths.get(ADB_FILE_PATH), serverPort = PORT, isUnitTest = false)
            }
            controller.start()
            processRunner.reset()

            // Act: queue up multiple start calls at the same time
            val restartJobs = List(5) { launch { controller.stop() } }
            restartJobs.joinAll()

            // Assert
            assertFalse(controller.isStarted)
            assertContentEquals(listOf(STOP_COMMAND), processRunner.allCommands)
        }

    @Test
    fun testCallingRestartAfterAnotherRestartCompleted_shouldRestartAgain(): Unit =
        runBlockingWithTimeout {
            // Prepare
            val processRunner = FakeProcessRunner()
            val controller =
                registerCloseable(
                    AdbServerControllerImpl(
                        host,
                        configFlow,
                        processRunner = processRunner
                    )
                )
            configFlow.update {
                it.copy(adbPath = Paths.get(ADB_FILE_PATH), serverPort = PORT, isUnitTest = false)
            }
            controller.start()
            processRunner.reset()

            // Act
            controller.restart()
            controller.restart()

            // Assert
            assertTrue(controller.isStarted)
            assertContentEquals(
                listOf(STOP_COMMAND, START_COMMAND, STOP_COMMAND, START_COMMAND),
                processRunner.allCommands,
            )
        }

    @Test
    fun testCanStartAfterTheFirstStartFails(): Unit = runBlockingWithTimeout {
        // Prepare
        val processRunner = FakeProcessRunner()
        val controller =
            registerCloseable(
                AdbServerControllerImpl(
                    host,
                    configFlow,
                    processRunner = processRunner
                )
            )
        configFlow.update {
            it.copy(
                adbPath = Paths.get(ADB_FILE_PATH),
                serverPort = PORT,
                isUnitTest = false
            )
        }

        // Act
        val startJob = launch {
            processRunner.throwOnNextCommand =
                IllegalStateException("Exception in a first call to `controller.start()`")
            try {
                controller.start()
            } catch (_: IllegalStateException) {
                // Ignore: This exception is expected
            }
        }
        startJob.join()

        // Assert
        assertFalse(controller.isStarted)
        assertTrue(processRunner.allCommands.isEmpty())

        // Act: Try to start again, and this time don't throw the exception
        processRunner.throwOnNextCommand = null
        controller.start()

        // Assert
        assertTrue(controller.isStarted)
        assertContentEquals(listOf(START_COMMAND), processRunner.allCommands)
    }

    @Test
    fun testCanStopAfterTheFirstStopFails(): Unit = runBlockingWithTimeout {
        // Prepare
        val processRunner = FakeProcessRunner()
        val controller =
            registerCloseable(
                AdbServerControllerImpl(
                    host,
                    configFlow,
                    processRunner = processRunner
                )
            )
        configFlow.update {
            it.copy(
                adbPath = Paths.get(ADB_FILE_PATH),
                serverPort = PORT,
                isUnitTest = false
            )
        }
        controller.start()
        processRunner.reset()

        // Act
        val startJob = launch {
            processRunner.throwOnNextCommand =
                IllegalStateException("Exception in a first call to `controller.start()`")
            try {
                controller.stop()
            } catch (_: IllegalStateException) {
                // Ignore: This exception is expected
            }
        }
        startJob.join()

        // Assert
        assertTrue(controller.isStarted)
        assertTrue(processRunner.allCommands.isEmpty())

        // Act: Try to stop again, and this time don't throw the exception
        processRunner.throwOnNextCommand = null
        controller.stop()

        // Assert
        assertFalse(controller.isStarted)
        assertContentEquals(listOf(STOP_COMMAND), processRunner.allCommands)
    }

    private class FakeProcessRunner(private val delayByMs: Long = 0) :
        AdbServerControllerImpl.ProcessRunner {

        var lastCommand: List<String>? = null
        val allCommands: MutableList<List<String>> = mutableListOf()
        var throwOnNextCommand: Throwable? = null

        override suspend fun runProcess(command: List<String>, envVars: Map<String, String>) {
            delay(delayByMs)
            throwOnNextCommand?.let { throw it }
            lastCommand = command
            allCommands.add(command)
        }

        fun reset() {
            lastCommand = null
            allCommands.clear()
        }
    }

    companion object {

        private const val ADB_FILE_PATH = "/dir1/dir2/adb"
        private const val PORT = 12345
        private val START_COMMAND = listOf(ADB_FILE_PATH, "-P", 12345.toString(), "start-server")
        private val STOP_COMMAND = listOf(ADB_FILE_PATH, "kill-server")
    }
}
