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

import com.android.adblib.testing.FakeAdbSessionHost
import com.android.adblib.testingutils.CloseablesRule
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.testingutils.FakeAdbServerProvider
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException

class AdbServerControllerImplTest {

  @JvmField @Rule val closeables = CloseablesRule()

  @JvmField @Rule var exceptionRule: ExpectedException = ExpectedException.none()

  private val configFlow =
    MutableStateFlow(
      AdbServerConfiguration(adbPath = null, serverPort = null, isUserManaged = false, isUnitTest = true, envVars = emptyMap())
    )

  private fun <T : AutoCloseable> registerCloseable(item: T): T {
    return closeables.register(item)
  }

  private val fakeAdb = registerCloseable(FakeAdbServerProvider().also { it.installDefaultCommandHandlers() }.build().start())
  private val host = registerCloseable(FakeAdbSessionHost())
  private val processRunner = host.processRunner

  @Test
  fun testNotStarted() {
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    assertFalse(controller.isStarted)
  }

  @Test
  fun testCanStartControllerInUserManagedMode(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(isUserManaged = true, adbPath = ADB_FILE_PATH, serverPort = fakeAdb.port, isUnitTest = false) }

    // Act
    controller.start()

    // Assert
    // Can create channel
    registerCloseable(controller.channelProvider.createChannel())
    assertTrue(controller.isStarted)
  }

  @Test
  fun testStartThrows_whenAdbPathNotProvided(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(serverPort = PORT, isUnitTest = false) }
    exceptionRule.expect(IllegalStateException::class.java)
    exceptionRule.expectMessage("adb path must be provided")

    // Act
    controller.start()
    fail("Should not reach")
  }

  @Test
  fun testStartThrowsIOException_whenItIsPreemptedByStop(): Unit = runBlockingWithTimeout {
    // This test makes sure `start` throws an `IOException` and not a
    // `CancellationException` when its `job` is cancelled by a call to `stop`.

    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = fakeAdb.port, isUnitTest = false) }

    // Make sure start() takes some time to run, so that we could interrupt it by a `stop()`
    val startResult = async {
      processRunner.delayByMs = 5000
      controller.start()
    }
    exceptionRule.expect(IOException::class.java)

    // Act
    // Give `start` an opportunity to begin execution, and make sure that it completes
    // with expected exception.
    delay(100)
    processRunner.delayByMs = 0
    controller.stop()
    // This should throw
    startResult.await()

    // Assert
    fail("Should not reach")
  }

  @Test
  fun testStopThrowsIOException_whenItIsPreemptedByStart(): Unit = runBlockingWithTimeout {
    // This test makes sure `stop` throws an `IOException` and not a
    // `CancellationException` when its `job` is cancelled by a call to `start`.

    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = fakeAdb.port, isUnitTest = false) }
    controller.start()

    // Make sure stop() takes some time to run, so that we could interrupt it by a `start()`
    val stopResult = async {
      processRunner.delayByMs = 5000
      controller.stop()
    }
    exceptionRule.expect(IOException::class.java)

    // Act
    // Give `stop` an opportunity to begin execution, and make sure that it completes
    // with expected exception.
    delay(100)
    processRunner.delayByMs = 0
    controller.start()
    // This should throw
    stopResult.await()

    // Assert
    fail("Should not reach")
  }

  @Test
  fun testRestartThrowsIOException_whenItIsPreemptedByStop(): Unit = runBlockingWithTimeout {
    // This test makes sure `restart` throws an `IOException` and not a
    // `CancellationException` when its `job` is cancelled by a call to `stop`.

    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = fakeAdb.port, isUnitTest = false) }
    controller.start()

    supervisorScope {
      // Make sure restart() takes some time to run, so that we could interrupt it by a `stop()`
      val restartResult = async {
        processRunner.delayByMs = 5000
        controller.restart()
      }

      // Act
      // Give `restart` an opportunity to begin execution, and make sure that it completes
      // with expected exception.
      delay(100)
      processRunner.delayByMs = 0
      controller.stop()

      // `restartResult` should throw
      exceptionRule.expect(IOException::class.java)
      restartResult.await()

      // Assert
      fail("Should not reach")
    }
  }

  @Test
  fun testCanStopControllerInUserManagedMode(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(isUserManaged = true, adbPath = ADB_FILE_PATH, serverPort = fakeAdb.port, isUnitTest = false) }
    // Put controller in a started state
    controller.start()
    assertTrue(controller.isStarted)

    // Act
    controller.stop()

    // Assert
    assertFalse(controller.isStarted)
  }

  @Test
  fun testCreateChannelInStoppingState_throwsIOException(): Unit = runBlockingWithTimeout {
    // Prepare: Create a controller and put it into a stopped state
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = fakeAdb.port, isUnitTest = false) }
    controller.start()

    // Act: start stopping. While the controller is stopping, it can no longer create channels.
    processRunner.delayByMs = 100
    launch { controller.stop() }

    // delay a little to let the controller start stopping
    delay(50)
    // It hasn't been successfully stopped yet, but `createChannel` already throws exceptions
    assertTrue(controller.isStarted)
    exceptionRule.expect(IOException::class.java)
    exceptionRule.expectMessage("`AdbServerController` is in a stopping/stopped")
    registerCloseable(controller.channelProvider.createChannel())
    fail("Should not reach")
  }

  @Test
  fun testCreateChannelInStoppedState_throwsIOException(): Unit = runBlockingWithTimeout {
    // Prepare: Create a controller and put it into a stopped state
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(serverPort = fakeAdb.port) }
    controller.start()
    controller.stop()
    assertFalse(controller.isStarted)

    exceptionRule.expect(IOException::class.java)
    exceptionRule.expectMessage("`AdbServerController` is in a stopping/stopped")

    // Act/Assert: after controller is stopped, it can no longer create channels
    registerCloseable(controller.channelProvider.createChannel())
    fail("Should not reach")
  }

  @Test
  fun testStopThrows_whenAdbPathNotProvided(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(serverPort = PORT) }
    // Put controller into `isStarted` state before tweaking the config
    controller.start()
    configFlow.update { it.copy(serverPort = PORT, isUnitTest = false) }
    exceptionRule.expect(IllegalStateException::class.java)
    exceptionRule.expectMessage("adb path must be provided")

    // Act
    controller.stop()
    fail("Should not reach")
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
  fun testLastKnownRemoteAddress(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(serverPort = fakeAdb.port) }

    // Act: `createChannel` should set `lastKnownRemoteAddress`
    controller.start()
    registerCloseable(controller.channelProvider.createChannel())

    // Assert
    assertTrue(controller.isStarted)
    assertEquals(InetSocketAddress(InetAddress.getLoopbackAddress(), fakeAdb.port), controller.lastKnownRemoteAddress)

    // Act: `stop` should reset `lastKnownRemoteAddress` to `null`
    controller.stop()

    // Assert
    assertFalse(controller.isStarted)
    assertNull(controller.lastKnownRemoteAddress)
  }

  @Test
  fun testCreateChannelThrowsTimeoutException_whenTimesOutWaitingForControllerIsStarted(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    exceptionRule.expect(TimeoutException::class.java)

    // Act
    // createChannel call will timeout, because the controller has not been started
    registerCloseable(controller.channelProvider.createChannel(50, TimeUnit.MILLISECONDS))
    fail("Should not reach")
  }

  @Test
  fun testCreateChannelThrowsTimeoutException_whenTimesOutOnRestart(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = fakeAdb.port, isUnitTest = false) }
    controller.start()
    // Close fakeAdb server to force `restart()` call to be triggered
    fakeAdb.close()
    exceptionRule.expect(TimeoutException::class.java)

    // Act
    // createChannel call will timeout, because the `restart()` takes a long time
    processRunner.delayByMs = 5000
    registerCloseable(controller.channelProvider.createChannel(50, TimeUnit.MILLISECONDS))
    fail("Should not reach")
  }

  @Test
  fun testCreateChannelThrowsIOException_whenRestartJobCancelled(): Unit = runBlockingWithTimeout {
    // This test makes sure `createChannel` throws an `IOException` and not a
    // `CancellationException` when the `restart` job is cancelled by `stop`

    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = fakeAdb.port, isUnitTest = false) }
    controller.start()
    // Close fakeAdb server to force `restart()` call to be triggered from `createChannel()`
    fakeAdb.close()

    // Act / Assert: `createChannel` throws a correct exception when its execution is
    // interrupted by `AdbServerController.stop()`.
    var stopJob: Job? = null
    try {
      assertEquals(true, controller.isStarted)
      // ChannelProvider's `createChannel` will try to establish a connection and if it
      // fails it will run `controller.restart()`. Because the attempt to establish
      // a connection can fail quickly (as on linux) or after a few seconds (as appears
      // to be happening on Windows), we wait until `restart` begins to execute before
      // initiating a `stop` operation.
      processRunner.onRunProcessStarted = {
        // We started executing restart, and so trigger `stop` command
        stopJob = launch {
          // Trigger a quick stop command
          processRunner.onRunProcessStarted = null
          processRunner.delayByMs = 50
          controller.stop()
        }
      }
      // Make restart() take a long time, so that we could call `stop()` in the meantime
      processRunner.delayByMs = 5000
      registerCloseable(controller.channelProvider.createChannel())
      fail("Should not reach")
    } catch (e: IOException) {
      // Expected exception
      assertEquals("`createChannel` failed to restart `AdbServerController` on failure", e.message)
    }

    // Stop should succeed
    assertNotNull(stopJob)
    stopJob.join()
    assertEquals(false, controller.isStarted)
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
    val newFakeAdb = registerCloseable(FakeAdbServerProvider().also { it.installDefaultCommandHandlers() }.build().start())
    configFlow.update { it.copy(serverPort = newFakeAdb.port) }
    // Assert: `controller.channelProvider.createChannel()` should be able to reconnect to a new
    // server port.
    controller.channelProvider.createChannel()
  }

  @Test
  fun testCanStartAndStopAdbServerFromFile(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }

    // Act
    controller.start()

    // Assert
    assertTrue(controller.isStarted)
    assertEquals(START_COMMAND, processRunner.lastCommand)
    assertEquals(ADB_FILE_DIR_PATH.toString(), processRunner.lastDirectory)

    // Act
    controller.stop()
    assertFalse(controller.isStarted)
    assertEquals(STOP_COMMAND, processRunner.lastCommand)
    assertEquals(ADB_FILE_DIR_PATH.toString(), processRunner.lastDirectory)
  }

  @Test
  fun testRestartIsNoop_whenStartIsInProgress(): Unit = runBlockingWithTimeout {
    // Prepare
    processRunner.delayByMs = 50
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }

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
  fun testRestartThrowsIOException_whenStopIsInProgress(): Unit = runBlockingWithTimeout {
    // Prepare
    processRunner.delayByMs = 50
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }
    controller.start()
    processRunner.reset()

    supervisorScope {
      // Act
      val stopJob = launch { controller.stop() }
      val restartResult = async {
        // delay a little to make sure stop job is in progress when we trigger restart
        delay(10)
        // Assert stop operation is still in progress
        assertTrue(stopJob.isActive)
        controller.restart()
      }

      exceptionRule.expect(IOException::class.java)
      exceptionRule.expectMessage("cannot be restarted when it's in a stopping/stopped state")
      restartResult.await()
    }
  }

  @Test
  fun testTransition_fromSuccessfulRestart_toSuccessfulStop(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }
    controller.start()
    controller.restart()
    processRunner.reset()
    assertTrue(controller.isStarted)

    // Act
    controller.stop()

    // Assert
    assertFalse(controller.isStarted)
    assertContentEquals(listOf(STOP_COMMAND), processRunner.allCommands)
  }

  @Test
  fun testTransition_fromFailedRestart_toSuccessfulStop(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }
    controller.start()
    processRunner.throwOnNextCommand = IOException("Exception in a call to `controller.restart()`")
    try {
      controller.restart()
      fail("Should not reach")
    } catch (_: IOException) {
      // Ignore: This exception is expected
    }
    processRunner.reset()
    assertTrue(controller.isStarted)

    // Act
    processRunner.throwOnNextCommand = null
    controller.stop()

    // Assert
    assertFalse(controller.isStarted)
    assertContentEquals(listOf(STOP_COMMAND), processRunner.allCommands)
  }

  @Test
  fun testRestartIsNoop_whenInUserManagedMode(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(isUserManaged = true, adbPath = ADB_FILE_PATH, serverPort = fakeAdb.port, isUnitTest = false) }
    // Put controller in a started state
    controller.start()
    assertTrue(controller.isStarted)
    processRunner.reset()

    // Act
    controller.restart()

    // Assert: after controller is restarted it can still create channels
    registerCloseable(controller.channelProvider.createChannel())
    assertTrue(controller.isStarted)
    // We didn't run any `adb start-server` or `adb kill-server` commands
    assertTrue(processRunner.allCommands.isEmpty())
  }

  @Test
  fun testRestartThrowsIOException_whenInInitialState(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }

    // Act/Assert: restart from the initial state should throw an exception
    exceptionRule.expect(IOException::class.java)
    exceptionRule.expectMessage("cannot be restarted when it's in a stopped state")
    controller.restart()
  }

  @Test
  fun testRestartThrowsIOException_whenInStoppedState(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }

    // Prepare: Put controller into a stopped state
    controller.start()
    controller.stop()
    processRunner.reset()

    // Act/Assert: restart from the stopped state should throw an exception
    exceptionRule.expect(IOException::class.java)
    exceptionRule.expectMessage("cannot be restarted when it's in a stopping/stopped state")
    controller.restart()
  }

  @Test
  fun testRestarting_afterFailedStart(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }
    processRunner.throwOnNextCommand = IOException("Exception in a call to `controller.start()`")

    // Act: put controller into a failed started state
    try {
      controller.start()
      fail("Should not reach")
    } catch (_: IOException) {
      // Ignore: This exception is expected
    }

    // Assert
    assertFalse(controller.isStarted)
    assertTrue(processRunner.allCommands.isEmpty())

    // Prepare: Try restart from a failed start state and make it fail
    processRunner.throwOnNextCommand = IOException("Exception in a call to `controller.restart()`")
    // Act: Try restart from a failed start state
    try {
      controller.restart()
      fail("Should not reach")
    } catch (_: IOException) {
      // Ignore: This exception is expected
    }
    // Assert
    assertFalse(controller.isStarted)
    assertTrue(processRunner.allCommands.isEmpty())

    // Prepare: Try restart from a failed start state and this time make it succeed
    processRunner.throwOnNextCommand = null
    // Act
    controller.restart()
    // Assert
    assertTrue(controller.isStarted)
    assertContentEquals(listOf(START_COMMAND), processRunner.allCommands)
  }

  @Test
  fun testOnlyOneAdbServerRestartIsTriggered_whenConcurrentRestarts(): Unit = runBlockingWithTimeout {
    // Prepare
    processRunner.delayByMs = 50
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }
    controller.start()
    processRunner.reset()

    // Act: queue up multiple restarts at the same time
    val restartJobs = List(20) { launch { controller.restart() } }
    restartJobs.joinAll()

    // Assert
    assertTrue(controller.isStarted)
    assertContentEquals(listOf(STOP_COMMAND, START_COMMAND), processRunner.allCommands)
  }

  @Test
  fun testMultipleStateTransitions_properlyCancelled_whenPreempted(): Unit = runBlockingWithTimeout {
    // Prepare
    processRunner.delayByMs = 5000
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }

    // Act: queue up a bunch of start/stop pairs
    val totalOperations = 20
    var failingTransitions = 0
    var index = 0
    val startStopJobs =
      List(totalOperations) {
        launch {
            try {
              if (index == totalOperations - 1) {
                // This is the last operation. Make it run quickly.
                processRunner.delayByMs = 10
              }
              if (index++ % 2 == 0) {
                controller.start()
              } else {
                controller.stop()
              }
            } catch (_: IOException) {
              // Expected
              ++failingTransitions
            }
          }
          .also { delay(25) }
      }

    startStopJobs.joinAll()

    // Assert: only the last controller operation succeeds
    assertFalse(controller.isStarted)
    assertEquals(19, failingTransitions)
    assertContentEquals(listOf(STOP_COMMAND), processRunner.allCommands)
  }

  @Test
  fun testOnlyOneAdbServerStartIsTriggered_whenStartIsCalledConcurrently(): Unit = runBlockingWithTimeout {
    // Prepare
    processRunner.delayByMs = 50
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }

    // Act: queue up multiple start calls at the same time
    val restartJobs = List(5) { launch { controller.start() } }
    restartJobs.joinAll()

    // Assert
    assertTrue(controller.isStarted)
    assertContentEquals(listOf(START_COMMAND), processRunner.allCommands)
  }

  @Test
  fun testOnlyOneAdbServerStopIsTriggered_whenStopIsCalledConcurrently(): Unit = runBlockingWithTimeout {
    // Prepare
    processRunner.delayByMs = 50
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }
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
  fun testCallingRestartAfterAnotherRestartCompleted_shouldRestartAgain(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }
    controller.start()
    processRunner.reset()

    // Act
    controller.restart()
    controller.restart()

    // Assert
    assertTrue(controller.isStarted)
    assertContentEquals(listOf(STOP_COMMAND, START_COMMAND, STOP_COMMAND, START_COMMAND), processRunner.allCommands)
  }

  @Test
  fun testCanStartAfterTheFirstStartFails(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }

    // Act
    processRunner.throwOnNextCommand = IOException("Exception in a first call to `controller.start()`")
    try {
      controller.start()
      fail("Should not reach")
    } catch (_: IOException) {
      // Ignore: This exception is expected
    }

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
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = PORT, isUnitTest = false) }
    controller.start()
    processRunner.reset()

    // Act
    processRunner.throwOnNextCommand = IllegalStateException("Exception in a first call to `controller.start()`")
    try {
      controller.stop()
      fail("Should not reach")
    } catch (_: IllegalStateException) {
      // Ignore: This exception is expected
    }

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

  @Test
  fun testStartDoesNotSpecifyPortParamWhenUsingDefaultAdbPort(): Unit = runBlockingWithTimeout {
    // Prepare
    val controller = registerCloseable(AdbServerControllerImpl(host, configFlow))
    configFlow.update { it.copy(adbPath = ADB_FILE_PATH, serverPort = DEFAULT_ADB_HOST_PORT, isUnitTest = false) }

    // Act
    controller.start()

    // Assert
    assertTrue(controller.isStarted)
    assertEquals(listOf(ADB_FILE_PATH.toString(), "start-server"), processRunner.lastCommand)
  }

  companion object {

    private val ADB_FILE_PATH = Paths.get("dir1", "dir2", "adb")
    private val ADB_FILE_DIR_PATH = Paths.get("dir1", "dir2")
    private const val PORT = 12345
    private val START_COMMAND = listOf(ADB_FILE_PATH.toString(), "-P", 12345.toString(), "start-server")
    private val STOP_COMMAND = listOf(ADB_FILE_PATH.toString(), "-P", 12345.toString(), "kill-server")
  }
}
