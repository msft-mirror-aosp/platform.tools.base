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
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.junit.Rule
import org.junit.Test

class AdbServerControllerImplTest {

  @JvmField @Rule val closeables = CloseablesRule()

  private val configFlow =
    MutableStateFlow(
      AdbServerConfiguration(
        adbFile = null,
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
      registerCloseable(AdbServerControllerImpl(host, configFlow, processRunner = processRunner))
    val adbFilePath = "adb"
    val adbFile = File(adbFilePath)
    val port = 12345
    configFlow.update { it.copy(adbFile = adbFile, serverPort = port, isUnitTest = false) }

    // Act
    controller.start()

    // Assert
    assertTrue(controller.isStarted)
    assertEquals(
      listOf(adbFilePath, "-P", port.toString(), "start-server"),
      processRunner.lastCommand,
    )

    // Act
    controller.stop()
    assertFalse(controller.isStarted)
    assertEquals(listOf(adbFilePath, "kill-server"), processRunner.lastCommand)
  }

  private class FakeProcessRunner : AdbServerControllerImpl.ProcessRunner {

    var lastCommand: List<String>? = null

    override suspend fun runProcess(command: List<String>, envVars: Map<String, String>) {
      this.lastCommand = command
    }
  }
}
