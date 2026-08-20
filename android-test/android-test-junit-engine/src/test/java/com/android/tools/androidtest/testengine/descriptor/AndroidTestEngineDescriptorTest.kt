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

package com.android.tools.androidtest.testengine.descriptor

import com.android.tools.androidtest.testengine.AndroidTestExecutionContext
import com.android.tools.androidtest.testengine.config.AndroidTestConfiguration
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.hierarchical.Node
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AndroidTestEngineDescriptorTest {

  @Test
  fun `execute executes AndroidDeviceDescriptor for each serial`() {
    val uniqueId = UniqueId.forEngine("android-test-engine")
    val descriptor = AndroidTestEngineDescriptor(uniqueId)

    val context = mock<AndroidTestExecutionContext>()
    val configuration = mock<AndroidTestConfiguration>()
    whenever(context.configuration).thenReturn(configuration)
    whenever(configuration.deviceSerials).thenReturn(listOf("serial1", "serial2"))
    whenever(configuration.adb).thenReturn(File("adb"))

    val dynamicTestExecutor = mock<Node.DynamicTestExecutor>()

    descriptor.execute(context, dynamicTestExecutor)

    val captor = argumentCaptor<AndroidDeviceDescriptor>()
    verify(dynamicTestExecutor, times(2)).execute(captor.capture())

    val descriptors = captor.allValues
    assertThat(descriptors[0].deviceSerial).isEqualTo("serial1")
    // Android Studio expects the device serial in the UniqueId to match results
    // with its internal device model.
    assertThat(descriptors[0].uniqueId.segments.last().value).isEqualTo("serial1")
    assertThat(descriptors[0].parent.get()).isSameInstanceAs(descriptor)

    assertThat(descriptors[1].deviceSerial).isEqualTo("serial2")
    assertThat(descriptors[1].uniqueId.segments.last().value).isEqualTo("serial2")
    assertThat(descriptors[1].parent.get()).isSameInstanceAs(descriptor)
  }

  @Test
  fun `execute sanitizes device serials and versions with unsafe characters`() {
    val uniqueId = UniqueId.forEngine("android-test-engine")
    val descriptor = AndroidTestEngineDescriptor(uniqueId)

    val context = mock<AndroidTestExecutionContext>()
    val configuration = mock<AndroidTestConfiguration>()
    whenever(context.configuration).thenReturn(configuration)
    whenever(configuration.deviceSerials).thenReturn(listOf("../../../evil serial"))
    whenever(configuration.adb).thenReturn(File("adb"))

    val dynamicTestExecutor = mock<Node.DynamicTestExecutor>()

    descriptor.execute(context, dynamicTestExecutor)

    val captor = argumentCaptor<AndroidDeviceDescriptor>()
    verify(dynamicTestExecutor).execute(captor.capture())

    val deviceDescriptor = captor.firstValue
    assertThat(deviceDescriptor.deviceSerial).isEqualTo("../../../evil serial")
    assertThat(deviceDescriptor.deviceId).isEqualTo(".._.._.._evil serial")
    assertThat(deviceDescriptor.deviceDisplayName).isEqualTo(".._.._.._evil serial")
  }

  @Test
  fun `execute sanitizes device serials and versions when empty or all dots`() {
    val uniqueId = UniqueId.forEngine("android-test-engine")
    val descriptor = AndroidTestEngineDescriptor(uniqueId)

    // Test Case A: All dots ("..") -> should sanitize to underscores ("__") to prevent collision
    val contextA = mock<AndroidTestExecutionContext>()
    val configurationA = mock<AndroidTestConfiguration>()
    whenever(contextA.configuration).thenReturn(configurationA)
    whenever(configurationA.deviceSerials).thenReturn(listOf(".."))
    whenever(configurationA.adb).thenReturn(File("adb"))

    val dynamicTestExecutorA = mock<Node.DynamicTestExecutor>()
    descriptor.execute(contextA, dynamicTestExecutorA)

    val captorA = argumentCaptor<AndroidDeviceDescriptor>()
    verify(dynamicTestExecutorA).execute(captorA.capture())
    assertThat(captorA.firstValue.deviceId).isEqualTo("__")

    // Test Case B: Empty/Blank custom device ID ("   ") -> should fall back to "device"
    val contextB = mock<AndroidTestExecutionContext>()
    val configurationB = mock<AndroidTestConfiguration>()
    whenever(contextB.configuration).thenReturn(configurationB)
    whenever(configurationB.deviceSerials).thenReturn(listOf("serial1"))
    whenever(configurationB.getDeviceId("serial1")).thenReturn("   ")
    whenever(configurationB.adb).thenReturn(File("adb"))

    val dynamicTestExecutorB = mock<Node.DynamicTestExecutor>()
    descriptor.execute(contextB, dynamicTestExecutorB)

    val captorB = argumentCaptor<AndroidDeviceDescriptor>()
    verify(dynamicTestExecutorB).execute(captorB.capture())
    assertThat(captorB.firstValue.deviceId).isEqualTo("device")
  }

  @Test
  fun `execute sanitizes custom deviceIds retrieved from configuration`() {
    val uniqueId = UniqueId.forEngine("android-test-engine")
    val descriptor = AndroidTestEngineDescriptor(uniqueId)

    val context = mock<AndroidTestExecutionContext>()
    val configuration = mock<AndroidTestConfiguration>()
    whenever(context.configuration).thenReturn(configuration)
    whenever(configuration.deviceSerials).thenReturn(listOf("serial1"))
    whenever(configuration.getDeviceId("serial1")).thenReturn("../../evil_custom_id")
    whenever(configuration.adb).thenReturn(File("adb"))

    val dynamicTestExecutor = mock<Node.DynamicTestExecutor>()

    descriptor.execute(context, dynamicTestExecutor)

    val captor = argumentCaptor<AndroidDeviceDescriptor>()
    verify(dynamicTestExecutor).execute(captor.capture())

    val deviceDescriptor = captor.firstValue
    assertThat(deviceDescriptor.deviceSerial).isEqualTo("serial1")
    assertThat(deviceDescriptor.deviceId).isEqualTo(".._.._evil_custom_id")
    assertThat(deviceDescriptor.deviceDisplayName).isEqualTo(".._.._evil_custom_id (serial1)")
  }
}
