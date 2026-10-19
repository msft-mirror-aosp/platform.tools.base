/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.deployer

import com.android.tools.deploy.proto.Deploy.Arch
import com.android.tools.deployer.common.AdbClient
import com.android.tools.deployer.common.DeviceHolder
import com.android.utils.ILogger
import org.junit.Assert
import org.junit.Test
import org.mockito.Mockito

class AdbClientTest {

  @Test
  fun testGetPids() {
    val deviceHolder = Mockito.mock(DeviceHolder::class.java)
    val logger = Mockito.mock(ILogger::class.java)

    Mockito.`when`(deviceHolder.isRealPkgNameSupported).thenReturn(true)
    Mockito.`when`(deviceHolder.getPidsForPackageName("com.example.app")).thenReturn(listOf(101, 102))

    val adbClient = AdbClient(deviceHolder, logger)
    val pids = adbClient.getPids("com.example.app")

    Assert.assertEquals(listOf(101, 102), pids)
    Mockito.verify(deviceHolder).getPidsForPackageName("com.example.app")
  }

  @Test
  fun testGetPidsThrows_whenRealPkgNameNotSupported() {
    val deviceHolder = Mockito.mock(DeviceHolder::class.java)
    val logger = Mockito.mock(ILogger::class.java)

    Mockito.`when`(deviceHolder.isRealPkgNameSupported).thenReturn(false)
    Mockito.`when`(deviceHolder.serialNumber).thenReturn("serial-abc")

    val adbClient = AdbClient(deviceHolder, logger)
    val exception = Assert.assertThrows(IllegalStateException::class.java) { adbClient.getPids("com.example.app") }

    Assert.assertTrue(exception.message!!.contains("serial-abc"))
    Assert.assertTrue(exception.message!!.contains("does not support REAL_PKG_NAME"))
  }

  @Test
  fun testGetArch() {
    val deviceHolder = Mockito.mock(DeviceHolder::class.java)
    val logger = Mockito.mock(ILogger::class.java)

    Mockito.`when`(deviceHolder.getArchForPid(101)).thenReturn(Arch.ARCH_64_BIT)
    Mockito.`when`(deviceHolder.getArchForPid(102)).thenReturn(Arch.ARCH_32_BIT)

    val adbClient = AdbClient(deviceHolder, logger)
    val arch = adbClient.getArch(listOf(101, 102))

    Assert.assertEquals(Arch.ARCH_64_BIT, arch)
    Mockito.verify(deviceHolder).getArchForPid(101)
    Mockito.verify(deviceHolder).getArchForPid(102)

    // Verify warning is logged due to mixed ABIs
    Mockito.verify(logger).warning(Mockito.anyString(), Mockito.eq(Arch.ARCH_64_BIT), Mockito.eq(Arch.ARCH_32_BIT))
  }
}
