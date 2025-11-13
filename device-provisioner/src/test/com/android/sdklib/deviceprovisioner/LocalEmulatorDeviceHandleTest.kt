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
import com.android.sdklib.internal.avd.BootMode
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.google.common.truth.Truth.assertThat
import java.awt.Component
import java.nio.file.Path
import java.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Test

class LocalEmulatorDeviceHandleTest {
  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun activationTimeout() = runTest {
    val avdManager =
      object : StubAvdManager() {
        override suspend fun startAvd(avdInfo: AvdInfo, bootMode: BootMode) {
          delay(Long.MAX_VALUE)
        }
      }
    val context = testContext(this, avdManager)
    val handle =
      LocalEmulatorDeviceHandle(
        context,
        {},
        MutableStateFlow(emptyList()),
        this.createChildScope(),
        emptyList(),
        makeAvdInfo(createInMemoryFileSystemAndFolder("avds"), 1),
      )

    val activateJob = async(SupervisorJob()) { handle.activationAction.activate() }

    handle.stateFlow.takeWhile { !it.isTransitioning }.collect()

    advanceTimeBy(10.minutes)

    handle.stateFlow.takeWhile { it.isTransitioning }.collect()

    assertThat(activateJob.getCompletionExceptionOrNull())
      .isInstanceOf(DeviceActionException::class.java)

    handle.scope.cancel()
  }

  class AvdManagerWrapper(val avdManager: AvdManager) : StubAvdManager() {
    override suspend fun rescanAvds(): List<AvdInfo> {
      avdManager.reloadAvds()
      return avdManager.allAvds
    }
  }

  /**
   * updatePaired{Glasses/Phone} should result in properties.paired{Glasses/Phone}Id being updated.
   */
  @Test
  fun updatePairedDevices(): Unit =
    runBlockingWithTimeout(Duration.ofSeconds(5)) {
      with(SdkFixture()) {
        avdManager.createAvd(
          avdManager.createAvdBuilder(deviceManager.getDevice("pixel_9", "Google")!!).apply {
            systemImage = testSystemImages.api36.image
          }
        )
        avdManager.createAvd(
          avdManager
            .createAvdBuilder(deviceManager.getDevice("ai_glasses_device", "Google")!!)
            .apply { systemImage = testSystemImages.aiGlasses.image }
        )

        val session = FakeAdbSession()

        val plugin =
          LocalEmulatorProvisionerPlugin(
            session.scope,
            session,
            AvdManagerWrapper(avdManager),
            emptyDeviceIcons,
            TestDefaultDeviceActionPresentation,
            Dispatchers.IO,
            Duration.ofMillis(100),
          )

        yieldUntil { plugin.devices.value.size == 2 }

        val devices = plugin.devices.value.map { it as LocalEmulatorDeviceHandle }
        val phone = devices.first { it.state.properties.deviceType == DeviceType.HANDHELD }
        val glasses = devices.first { it.state.properties.deviceType == DeviceType.AI_GLASSES }
        phone.updatePairedGlasses(glasses)
        glasses.updatePairedPhone(phone)

        phone.stateFlow.first { it.properties.pairedGlassesId != null }
        glasses.stateFlow.first { it.properties.pairedPhoneId != null }

        phone.updatePairedGlasses(null)
        glasses.updatePairedPhone(null)

        phone.stateFlow.first { it.properties.pairedGlassesId == null }
        glasses.stateFlow.first { it.properties.pairedPhoneId == null }
      }
    }
}

fun unsupportedOperation(): Nothing = throw UnsupportedOperationException()

open class StubAvdManager : LocalEmulatorProvisionerPlugin.AvdManager {

  override val runningAvdsFlow: StateFlow<Map<Path, RunningAvd>>
    get() = unsupportedOperation()

  override suspend fun rescanAvds(): List<AvdInfo> = unsupportedOperation()

  override suspend fun createAvd(parent: Component?): Boolean = unsupportedOperation()

  override suspend fun editAvd(parent: Component?, avdInfo: AvdInfo): Boolean =
    unsupportedOperation()

  override suspend fun unpairGlasses(handle: LocalEmulatorDeviceHandle) = unsupportedOperation()

  override suspend fun pairGlasses(
    parent: Component?,
    glassesHandle: LocalEmulatorDeviceHandle,
    deviceHandleFlow: Flow<List<LocalEmulatorDeviceHandle>>,
  ) = unsupportedOperation()

  override suspend fun startAvd(avdInfo: AvdInfo, bootMode: BootMode): Unit = unsupportedOperation()

  override suspend fun stopAvd(avdInfo: AvdInfo): Unit = unsupportedOperation()

  override suspend fun showOnDisk(avdInfo: AvdInfo): Unit = unsupportedOperation()

  override suspend fun duplicateAvd(parent: Component?, avdInfo: AvdInfo): Unit =
    unsupportedOperation()

  override suspend fun wipeData(avdInfo: AvdInfo): Unit = unsupportedOperation()

  override suspend fun deleteAvd(avdInfo: AvdInfo): Unit = unsupportedOperation()

  override suspend fun downloadAvdSystemImage(avdInfo: AvdInfo): Unit = unsupportedOperation()
}

private fun testContext(
  testScope: TestScope,
  avdManager: LocalEmulatorProvisionerPlugin.AvdManager,
) =
  LocalEmulatorContext(
    FakeAdbLoggerFactory().createClassLogger(LocalEmulatorProvisionerPlugin::class.java),
    DeviceIcons(
      EmptyIcon.DEFAULT,
      EmptyIcon.DEFAULT,
      EmptyIcon.DEFAULT,
      EmptyIcon.DEFAULT,
      EmptyIcon.DEFAULT,
      EmptyIcon.DEFAULT,
    ),
    defaultPresentation = TestDefaultDeviceActionPresentation,
    avdManager = avdManager,
    diskIoDispatcher = Dispatchers.IO,
    clock =
      object : Clock {
        override fun now() = Instant.fromEpochMilliseconds(testScope.currentTime)
      },
  )
