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
import com.android.testutils.file.createInMemoryFileSystemAndFolder
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LocalEmulatorDeviceHandleTest {

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

    val activateJob = async(SupervisorJob()) { handle.activate { delay(Long.MAX_VALUE) } }

    handle.stateFlow.takeWhile { !it.isTransitioning }.collect()

    advanceTimeBy(10.minutes)

    handle.stateFlow.takeWhile { it.isTransitioning }.collect()

    assertThat(activateJob.getCompletionExceptionOrNull()).isInstanceOf(DeviceActionException::class.java)

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
