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
package com.android.sdklib.deviceprovisioner

import com.android.adblib.testing.FakeAdbLoggerFactory
import com.android.adblib.testing.FakeAdbSession
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import com.android.adblib.testingutils.CoroutineTestUtils.yieldUntil
import com.android.adblib.utils.createChildScope
import com.android.sdklib.deviceprovisioner.testing.SdkFixture
import com.android.sdklib.internal.avd.AvdInfo
import com.android.sdklib.internal.avd.AvdManager
import com.android.sdklib.internal.avd.ColdBoot
import com.android.sdklib.internal.avd.QuickBoot
import com.android.testutils.FakeProcessHandle
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.google.common.truth.Truth.assertThat
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalEmulatorDeviceHandleTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun activationTimeout() = runTest {
    val context = testContext(this)

    val handleScope = this.createChildScope()
    val handle =
      LocalEmulatorDeviceHandle(
        context = context,
        avdScanner = NullAvdScanner(handleScope),
        scope = handleScope,
        extensions = emptyList(),
        initialAvdInfo = makeAvdInfo(createInMemoryFileSystemAndFolder("avds"), 1),
      )

    val activateJob = async(SupervisorJob()) { handle.activate { FakeProcess() } }

    handle.stateFlow.takeWhile { !it.isTransitioning }.collect()

    advanceTimeBy(10.minutes)

    handle.stateFlow.takeWhile { it.isTransitioning }.collect()

    assertThat(activateJob.getCompletionExceptionOrNull()).isInstanceOf(DeviceActionException::class.java)

    handleScope.cancel()
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun activationProcessCrash() = runTest {
    val context = testContext(this)

    val handleScope = this.createChildScope()
    val handle =
      LocalEmulatorDeviceHandle(
        context = context,
        avdScanner = NullAvdScanner(handleScope),
        scope = handleScope,
        extensions = emptyList(),
        initialAvdInfo = makeAvdInfo(createInMemoryFileSystemAndFolder("avds"), 1),
      )

    val process = FakeProcess()
    val activateJob = async(SupervisorJob()) { handle.activate { process } }

    handle.stateFlow.takeWhile { !it.isTransitioning }.collect()

    process.terminate(1)

    handle.stateFlow.takeWhile { it.isTransitioning }.collect()

    val exception = activateJob.getCompletionExceptionOrNull()
    assertThat(exception).isInstanceOf(DeviceActionException::class.java)
    assertThat(exception).isNotInstanceOf(DeviceActionCanceledException::class.java)
    assertThat(exception?.message).isEqualTo("The emulator process for AVD Fake Device 1 has terminated.")

    handleScope.cancel()
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun activationProcessClosedByUser() = runTest {
    val context = testContext(this)

    val handleScope = this.createChildScope()
    val handle =
      LocalEmulatorDeviceHandle(
        context = context,
        avdScanner = NullAvdScanner(handleScope),
        scope = handleScope,
        extensions = emptyList(),
        initialAvdInfo = makeAvdInfo(createInMemoryFileSystemAndFolder("avds"), 1),
      )

    val process = FakeProcess()
    val activateJob = async(SupervisorJob()) { handle.activate { process } }

    handle.stateFlow.takeWhile { !it.isTransitioning }.collect()

    process.terminate(0)

    handle.stateFlow.takeWhile { it.isTransitioning }.collect()

    val exception = activateJob.getCompletionExceptionOrNull()
    assertThat(exception).isInstanceOf(DeviceActionCanceledException::class.java)
    assertThat(exception?.message).isEqualTo("The emulator process for AVD Fake Device 1 was closed.")

    handleScope.cancel()
  }

  @Test
  fun processTerminatedWhileConnected(): Unit =
    runBlockingWithTimeout(Duration.ofSeconds(5)) {
      val session = FakeAdbSession()
      val avdManager = FakeAvdManager(session, temporaryFolder.newFolder("avds").toPath())
      try {
        val plugin =
          LocalEmulatorProvisionerPlugin(
            scope = session.scope,
            adbSession = session,
            avdScanner = FakeAvdScanner(avdManager, session.scope),
            deviceIcons = emptyDeviceIcons,
            runningAvdsFlow = MutableStateFlow(emptyMap()),
          )
        val defaultPlugin = DefaultProvisionerPlugin(session.scope, emptyDeviceIcons)
        val provisioner = DeviceProvisioner.create(session.scope, session, listOf(plugin, defaultPlugin))

        avdManager.createAvd()
        yieldUntil { provisioner.devices.value.size == 1 }
        val handle = provisioner.devices.value[0] as LocalEmulatorDeviceHandle

        val process = FakeProcess()
        handle.activate {
          avdManager.startAvd(handle.avdInfo, QuickBoot)
          process
        }
        handle.awaitReady()
        assertThat(handle.state).isInstanceOf(DeviceState.Connected::class.java)
        assertThat(handle.state.isTransitioning).isFalse()

        // Terminate the process without stopping the fake ADB device, simulating ADB lagging behind OS process exit.
        process.terminate(0)

        handle.stateFlow.first { it is DeviceState.Disconnected }
        assertThat(handle.state.isTransitioning).isFalse()
        assertThat(handle.state.connectedDevice).isNull()
        // DefaultProvisionerPlugin should not claim the dying ADB connection while ADB still considers it online.
        assertThat(provisioner.devices.value).containsExactly(handle)
      } finally {
        avdManager.close()
        session.close()
      }
    }

  @Test
  fun processTerminatedWhileBooting(): Unit =
    runBlockingWithTimeout(Duration.ofSeconds(5)) {
      val session = FakeAdbSession()
      val avdManager = FakeAvdManager(session, temporaryFolder.newFolder("avds").toPath())
      try {
        val plugin =
          LocalEmulatorProvisionerPlugin(
            scope = session.scope,
            adbSession = session,
            avdScanner = FakeAvdScanner(avdManager, session.scope),
            deviceIcons = emptyDeviceIcons,
            runningAvdsFlow = MutableStateFlow(emptyMap()),
          )
        val provisioner = DeviceProvisioner.create(session.scope, session, listOf(plugin))

        avdManager.createAvd()
        yieldUntil { provisioner.devices.value.size == 1 }
        val handle = provisioner.devices.value[0] as LocalEmulatorDeviceHandle

        val process = FakeProcess()
        handle.activate {
          avdManager.startAvd(handle.avdInfo, ColdBoot)
          process
        }
        assertThat(handle.state).isInstanceOf(DeviceState.Connected::class.java)
        assertThat(handle.state.isReady).isFalse()
        assertThat(handle.state.isTransitioning).isTrue()

        val awaitReadyJob = session.scope.async(start = CoroutineStart.UNDISPATCHED) { handle.awaitReady() }

        // Crash the process while still booting before ADB notices disconnection.
        process.terminate(1)

        handle.stateFlow.first { it is DeviceState.Disconnected }
        assertThat(handle.state.isTransitioning).isFalse()
        try {
          awaitReadyJob.await()
          throw AssertionError("Expected DeviceActionException")
        } catch (expected: DeviceActionException) {
          assertThat(expected.message).isEqualTo("Device did not come online")
        }
      } finally {
        avdManager.close()
        session.close()
      }
    }

  @Test
  fun processTerminatedDuringDeactivation(): Unit =
    runBlockingWithTimeout(Duration.ofSeconds(5)) {
      val session = FakeAdbSession()
      val avdManager = FakeAvdManager(session, temporaryFolder.newFolder("avds").toPath())
      try {
        val plugin =
          LocalEmulatorProvisionerPlugin(
            scope = session.scope,
            adbSession = session,
            avdScanner = FakeAvdScanner(avdManager, session.scope),
            deviceIcons = emptyDeviceIcons,
            runningAvdsFlow = MutableStateFlow(emptyMap()),
          )
        val provisioner = DeviceProvisioner.create(session.scope, session, listOf(plugin))

        avdManager.createAvd()
        yieldUntil { provisioner.devices.value.size == 1 }
        val handle = provisioner.devices.value[0] as LocalEmulatorDeviceHandle

        val process = FakeProcess()
        handle.activate {
          avdManager.startAvd(handle.avdInfo, QuickBoot)
          process
        }
        handle.awaitReady()

        // Deactivate by terminating the process without updating ADB's device list yet.
        handle.deactivate { process.terminate(0) }

        assertThat(handle.state).isInstanceOf(DeviceState.Disconnected::class.java)
        assertThat(handle.state.isTransitioning).isFalse()
      } finally {
        avdManager.close()
        session.close()
      }
    }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun runningAvdShuttingDownDuringActivation() = runTest {
    val context = testContext(this)
    val handleScope = this.createChildScope()
    val avdInfo = makeAvdInfo(createInMemoryFileSystemAndFolder("avds"), 1)
    val runningAvdFlow = MutableStateFlow<RunningAvd?>(null)
    val handle =
      LocalEmulatorDeviceHandle(
        context = context,
        avdScanner = NullAvdScanner(handleScope),
        scope = handleScope,
        extensions = emptyList(),
        initialAvdInfo = avdInfo,
        runningAvdFlow = runningAvdFlow,
      )

    val process = FakeProcess()
    val processHandle = FakeProcessHandle(101)
    val activateJob =
      async(SupervisorJob()) {
        handle.activate {
          runningAvdFlow.value =
            RunningAvd(avdInfo.dataFolderPath, processHandle, RunningAvd.RunType.EMBEDDED, isLaunchedByThisProcess = true)
          process
        }
      }

    handle.stateFlow.first { it.isTransitioning && it.status == "Starting up" }

    // Simulate Running Devices sending shutdown via gRPC and notifying RunningAvdTracker.
    runningAvdFlow.value = runningAvdFlow.value?.copy(isShuttingDown = true)

    val exception = runCatching { activateJob.await() }.exceptionOrNull()
    assertThat(exception).isInstanceOf(DeviceActionCanceledException::class.java)
    assertThat(exception?.message).isEqualTo("The emulator process for AVD Fake Device 1 was closed.")

    // While the emulator process is still shutting down, the handle remains transitioning with "Shutting down" status.
    handle.stateFlow.first { it is DeviceState.Disconnected && it.isTransitioning && it.status == "Shutting down" }
    val retryException = runCatching { handle.activate { FakeProcess() } }.exceptionOrNull()
    assertThat(retryException).isInstanceOf(DeviceActionDisabledException::class.java)

    // Even if QEMU exits with a non-zero exit code during gRPC shutdown, activation was already canceled cleanly,
    // and once the process handle exits the handle returns to Offline.
    process.terminate(1)
    processHandle.destroy()
    runningAvdFlow.value = null

    handle.stateFlow.first { it is DeviceState.Disconnected && !it.isTransitioning && it.status == "Offline" }

    handleScope.cancel()
  }

  @Test
  fun runningAvdShuttingDownAndTerminatedWhileConnected(): Unit =
    runBlockingWithTimeout(Duration.ofSeconds(5)) {
      val session = FakeAdbSession()
      val avdManager = FakeAvdManager(session, temporaryFolder.newFolder("avds").toPath())
      val runningAvdsFlow = MutableStateFlow<Map<Path, RunningAvd>>(emptyMap())
      try {
        val plugin =
          LocalEmulatorProvisionerPlugin(
            scope = session.scope,
            adbSession = session,
            avdScanner = FakeAvdScanner(avdManager, session.scope),
            deviceIcons = emptyDeviceIcons,
            runningAvdsFlow = runningAvdsFlow,
          )
        val provisioner = DeviceProvisioner.create(session.scope, session, listOf(plugin))

        avdManager.createAvd()
        yieldUntil { provisioner.devices.value.size == 1 }
        val handle = provisioner.devices.value[0] as LocalEmulatorDeviceHandle
        val avdPath = handle.avdInfo.dataFolderPath

        // Start AVD without going through handle.activate() (e.g. already running or launched externally).
        val processHandle = FakeProcessHandle(202)
        avdManager.startAvd(handle.avdInfo, QuickBoot)
        runningAvdsFlow.track(RunningAvd(avdPath, processHandle, RunningAvd.RunType.EMBEDDED, isLaunchedByThisProcess = false))

        handle.awaitReady()
        assertThat(handle.state.status).isEqualTo("Connected")
        assertThat(handle.state.isReady).isTrue()
        assertThat(handle.state.isTransitioning).isFalse()

        // Running Devices initiates shutdown via RunningAvdTracker.
        runningAvdsFlow.value = mapOf(avdPath to runningAvdsFlow.value.getValue(avdPath).copy(isShuttingDown = true))

        val shuttingDownState = handle.stateFlow.first { it.status == "Shutting down" }
        assertThat(shuttingDownState).isInstanceOf(DeviceState.Connected::class.java)
        assertThat(shuttingDownState.isTransitioning).isTrue()
        assertThat(shuttingDownState.isReady).isFalse()

        // Process exits before ADB notices disconnection; RunningAvdTracker removes the entry on exit.
        processHandle.destroy()

        val disconnectedState = handle.stateFlow.first { it is DeviceState.Disconnected }
        assertThat(disconnectedState.isTransitioning).isFalse()
        assertThat(disconnectedState.status).isEqualTo("Offline")
        assertThat(disconnectedState.connectedDevice).isNull()
      } finally {
        avdManager.close()
        session.close()
      }
    }

  @Test
  fun runningAvdTerminatedWithoutShuttingDownWhileConnected(): Unit =
    runBlockingWithTimeout(Duration.ofSeconds(5)) {
      val session = FakeAdbSession()
      val avdManager = FakeAvdManager(session, temporaryFolder.newFolder("avds").toPath())
      val runningAvdsFlow = MutableStateFlow<Map<Path, RunningAvd>>(emptyMap())
      try {
        val plugin =
          LocalEmulatorProvisionerPlugin(
            scope = session.scope,
            adbSession = session,
            avdScanner = FakeAvdScanner(avdManager, session.scope),
            deviceIcons = emptyDeviceIcons,
            runningAvdsFlow = runningAvdsFlow,
          )
        val provisioner = DeviceProvisioner.create(session.scope, session, listOf(plugin))

        avdManager.createAvd()
        yieldUntil { provisioner.devices.value.size == 1 }
        val handle = provisioner.devices.value[0] as LocalEmulatorDeviceHandle
        val avdPath = handle.avdInfo.dataFolderPath

        val processHandle = FakeProcessHandle(303)
        avdManager.startAvd(handle.avdInfo, QuickBoot)
        runningAvdsFlow.track(RunningAvd(avdPath, processHandle, RunningAvd.RunType.STANDALONE, isLaunchedByThisProcess = false))
        handle.awaitReady()

        // Process exits unexpectedly before ADB notices disconnection.
        processHandle.destroy()

        val disconnectedState = handle.stateFlow.first { it is DeviceState.Disconnected }
        assertThat(disconnectedState.isTransitioning).isFalse()
        assertThat(disconnectedState.status).isEqualTo("Offline")
      } finally {
        avdManager.close()
        session.close()
      }
    }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun deactivateWhileShuttingDownTimeout() = runTest {
    val context = testContext(this)
    val handleScope = this.createChildScope()
    val avdInfo = makeAvdInfo(createInMemoryFileSystemAndFolder("avds"), 1)
    val runningAvdFlow = MutableStateFlow<RunningAvd?>(null)
    val handle =
      LocalEmulatorDeviceHandle(
        context = context,
        avdScanner = NullAvdScanner(handleScope),
        scope = handleScope,
        extensions = emptyList(),
        initialAvdInfo = avdInfo,
        runningAvdFlow = runningAvdFlow,
      )

    val processHandle = FakeProcessHandle(404)
    runningAvdFlow.value =
      RunningAvd(avdInfo.dataFolderPath, processHandle, RunningAvd.RunType.EMBEDDED, isLaunchedByThisProcess = true, isShuttingDown = true)

    handle.stateFlow.first { it is DeviceState.Disconnected && it.isTransitioning && it.status == "Shutting down" }

    val actionInvoked = CompletableDeferred<Unit>()
    val deactivateResult = async { runCatching { handle.deactivate { actionInvoked.complete(Unit) } } }

    actionInvoked.await()
    advanceTimeBy(2.minutes)

    handle.stateFlow.first { it is DeviceState.Disconnected && !it.isTransitioning && it.status == "Offline" }
    assertThat(deactivateResult.await().exceptionOrNull()).isInstanceOf(DeviceActionException::class.java)

    handleScope.cancel()
  }

  /** updatePaired{Glasses/Phone} should result in properties.paired{Glasses/Phone}Id being updated. */
  @Test
  fun updatePairedDevices(): Unit =
    runBlockingWithTimeout(Duration.ofSeconds(5)) {
      with(SdkFixture()) {
        avdManager.createAvd(
          avdManager.createAvdBuilder(deviceManager.getDevice("pixel_9", "Google")!!).apply { systemImage = testSystemImages.api36.image }
        )
        avdManager.createAvd(
          avdManager.createAvdBuilder(deviceManager.getDevice("ai_glasses_device", "Google")!!).apply {
            systemImage = testSystemImages.aiGlasses.image
          }
        )

        val session = FakeAdbSession()

        val plugin =
          LocalEmulatorProvisionerPlugin(
            scope = session.scope,
            adbSession = session,
            avdScanner = AvdScannerImpl(session.scope, avdManager),
            deviceIcons = emptyDeviceIcons,
            runningAvdsFlow = MutableStateFlow(emptyMap()),
          )

        yieldUntil { plugin.devices.value.size == 2 }

        val devices = plugin.devices.value.map { it as LocalEmulatorDeviceHandle }
        val phone = devices.first { it.state.properties.deviceType == DeviceType.HANDHELD }
        val glasses = devices.first { it.state.properties.deviceType == DeviceType.AI_GLASSES }
        phone.addPairedGlasses(glasses.id, null)
        glasses.updatePairedPhone(phone)

        phone.stateFlow.first { (it.properties as LocalEmulatorProperties).pairedGlassesInfos.isNotEmpty() }
        glasses.stateFlow.first { it.properties.pairedPhoneId != null }

        phone.removePairedGlasses(glasses.id)
        glasses.updatePairedPhone(null)

        phone.stateFlow.first { (it.properties as LocalEmulatorProperties).pairedGlassesInfos.isEmpty() }
        glasses.stateFlow.first { it.properties.pairedPhoneId == null }

        // Test 1:N pairing methods
        phone.addPairedGlasses(glasses.id, "00:11:22:33:44:55")
        phone.stateFlow.first { (it.properties as LocalEmulatorProperties).pairedGlassesInfos.size == 1 }

        // Test updating an existing paired MAC address
        phone.addPairedGlasses(glasses.id, "11:22:33:44:55:66")
        phone.stateFlow.first {
          val infos = (it.properties as LocalEmulatorProperties).pairedGlassesInfos
          infos.size == 1 && infos.first().mac == "11:22:33:44:55:66"
        }

        val anotherGlassesId = DeviceId("AnotherGlasses", false, "abcd")
        phone.addPairedGlasses(anotherGlassesId, null)
        phone.stateFlow.first { (it.properties as LocalEmulatorProperties).pairedGlassesInfos.size == 2 }

        phone.removePairedGlasses(glasses.id)
        phone.stateFlow.first {
          val infos = (it.properties as LocalEmulatorProperties).pairedGlassesInfos
          infos.size == 1 && infos[0].id == anotherGlassesId
        }

        phone.removePairedGlasses(anotherGlassesId)
        phone.stateFlow.first { (it.properties as LocalEmulatorProperties).pairedGlassesInfos.isEmpty() }
      }
    }
}

private class NullAvdScanner(coroutineScope: CoroutineScope) : AbstractAvdScanner(coroutineScope) {
  override fun scanAvds(): List<AvdInfo> = emptyList()

  override fun logError(message: String, exception: Throwable) = throw exception
}

private class FakeProcess : Process() {
  private val onExitFuture = CompletableFuture<Process>()
  @Volatile private var exitCode: Int? = null

  fun terminate(code: Int) {
    exitCode = code
    onExitFuture.complete(this)
  }

  override fun onExit(): CompletableFuture<Process> = onExitFuture

  override fun exitValue(): Int = exitCode ?: throw IllegalThreadStateException("Process has not exited")

  override fun waitFor(): Int = onExitFuture.get().exitValue()

  override fun destroy() {
    terminate(0)
  }

  override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

  override fun getInputStream(): InputStream = InputStream.nullInputStream()

  override fun getErrorStream(): InputStream = InputStream.nullInputStream()
}

private class AvdScannerImpl(coroutineScope: CoroutineScope, val avdManager: AvdManager) : AbstractAvdScanner(coroutineScope) {
  override fun scanAvds(): List<AvdInfo> {
    avdManager.reloadAvds()
    return avdManager.allAvds
  }

  override fun logError(message: String, exception: Throwable) {
    throw exception
  }
}

fun testContext(testScope: TestScope) =
  LocalEmulatorContext(
    FakeAdbLoggerFactory().createClassLogger(LocalEmulatorProvisionerPlugin::class.java),
    DeviceIcons(EmptyIcon.DEFAULT, EmptyIcon.DEFAULT, EmptyIcon.DEFAULT, EmptyIcon.DEFAULT, EmptyIcon.DEFAULT, EmptyIcon.DEFAULT),
    clock =
      object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(testScope.currentTime)
      },
  )

private fun MutableStateFlow<Map<Path, RunningAvd>>.track(runningAvd: RunningAvd) {
  value = value + (runningAvd.avdDataFolder to runningAvd)
  runningAvd.processHandle.onExit().thenRun { value = value.filterKeys { it != runningAvd.avdDataFolder } }
}
