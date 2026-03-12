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

package com.android.tools.androidtest.testengine

import com.android.tools.androidtest.testengine.AdbController.CommandResult
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

/** Unit tests for the [AndroidTestDeviceInfoCollector] class. */
class AndroidTestDeviceInfoCollectorTest {

  @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

  @Mock private lateinit var adbController: AdbController

  private lateinit var collector: AndroidTestDeviceInfoCollector
  private val deviceSerial = "device-1234"

  @Before
  fun setUp() {
    collector = AndroidTestDeviceInfoCollector(adbController, deviceSerial)
  }

  @Test
  fun `collect returns correct device info`() {
    val getpropOutput =
      """
      [ro.build.version.sdk]: [34]
      [ro.product.cpu.abilist]: [arm64-v8a,armeabi-v7a,armeabi]
      [ro.product.manufacturer]: [Google]
      [ro.product.model]: [Pixel 8]
      [ro.boot.qemu.avd_name]: [Pixel_8_API_34]
      """
        .trimIndent()

    val meminfoOutput =
      """
      MemTotal:       16000000 kB
      MemFree:         1000000 kB
      """
        .trimIndent()

    val cpuinfoOutput =
      """
      processor	: 0
      model name	: ARMv8 Processor rev 2 (v8l)
      BogoMIPS	: 48.00
      Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 SVE asimdfhm dit uscat ilrcpc flagm
      CPU implementer	: 0x41
      CPU architecture: 8
      CPU variant	: 0x2
      CPU part	: 0xd05
      CPU revision	: 2

      processor	: 1
      model name	: ARMv8 Processor rev 2 (v8l)
      BogoMIPS	: 48.00
      Features	: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 SVE asimdfhm dit uscat ilrcpc flagm
      CPU implementer	: 0x41
      CPU architecture: 8
      CPU variant	: 0x2
      CPU part	: 0xd05
      CPU revision	: 2
      """
        .trimIndent()

    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop")), anyOrNull()))
      .thenReturn(CommandResult(0, getpropOutput, ""))
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("cat", "/proc/meminfo")), anyOrNull()))
      .thenReturn(CommandResult(0, meminfoOutput, ""))
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("cat", "/proc/cpuinfo")), anyOrNull()))
      .thenReturn(CommandResult(0, cpuinfoOutput, ""))

    val deviceInfo = collector.collect()

    assertThat(deviceInfo.name).isEqualTo("Pixel_8_API_34")
    assertThat(deviceInfo.apiLevel).isEqualTo("34")
    assertThat(deviceInfo.ramInBytes).isEqualTo(16000000000L)
    assertThat(deviceInfo.processorsList).containsExactly("ARMv8 Processor rev 2 (v8l)")
    assertThat(deviceInfo.abisList).containsExactly("arm64-v8a", "armeabi-v7a", "armeabi")
    assertThat(deviceInfo.manufacturer).isEqualTo("Google")
    assertThat(deviceInfo.serial).isEqualTo(deviceSerial)
    assertThat(deviceInfo.avdName).isEqualTo("Pixel_8_API_34")
    assertThat(deviceInfo.model).isEqualTo("Pixel 8")
  }

  @Test
  fun `collect handles missing properties gracefully`() {
    whenever(adbController.runAdbShellCommand(any(), any(), anyOrNull())).thenReturn(CommandResult(0, "", ""))

    val deviceInfo = collector.collect()

    assertThat(deviceInfo.name).isEqualTo(deviceSerial)
    assertThat(deviceInfo.apiLevel).isEmpty()
    assertThat(deviceInfo.ramInBytes).isEqualTo(0L)
    assertThat(deviceInfo.processorsList).isEmpty()
    assertThat(deviceInfo.abisList).isEmpty()
    assertThat(deviceInfo.manufacturer).isEmpty()
    assertThat(deviceInfo.serial).isEqualTo(deviceSerial)
    assertThat(deviceInfo.avdName).isEmpty()
    assertThat(deviceInfo.model).isEmpty()
  }

  @Test
  fun `getDeviceMemory handles different units`() {
    fun testUnit(output: String, expectedBytes: Long) {
      whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("cat", "/proc/meminfo")), anyOrNull()))
        .thenReturn(CommandResult(0, output, ""))
      whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("getprop")), anyOrNull())).thenReturn(CommandResult(0, "", ""))
      whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(listOf("cat", "/proc/cpuinfo")), anyOrNull()))
        .thenReturn(CommandResult(0, "", ""))

      val deviceInfo = collector.collect()
      assertThat(deviceInfo.ramInBytes).isEqualTo(expectedBytes)
    }

    testUnit("MemTotal: 100 kB", 100000L)
    testUnit("MemTotal: 100 MB", 100000000L)
    testUnit("MemTotal: 100 GB", 100000000000L)
  }

  @Test
  fun `getGradleDslDeviceName returns value from system property`() {
    val key = "com.android.junit.engine.gradleManagedDeviceDslName[$deviceSerial]"
    val expectedName = "myManagedDevice"
    System.setProperty(key, expectedName)
    try {
      whenever(adbController.runAdbShellCommand(any(), any(), anyOrNull())).thenReturn(CommandResult(0, "", ""))

      val deviceInfo = collector.collect()
      assertThat(deviceInfo.gradleDslDeviceName).isEqualTo(expectedName)
    } finally {
      System.clearProperty(key)
    }
  }
}
