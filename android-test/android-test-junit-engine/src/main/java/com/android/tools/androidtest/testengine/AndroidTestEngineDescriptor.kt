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

import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.support.hierarchical.Node

/**
 * Root descriptor for [AndroidTestEngine].
 *
 * This descriptor manages the high-level lifecycle of an Android instrumentation test run. It sets up the [AndroidTestRunner], handles APK
 * installation, and launches the instrumentation process. It uses a [AndroidDeviceDescriptor.Listener] to dynamically populate the test
 * hierarchy as events are reported from the device.
 */
class AndroidTestEngineDescriptor(uniqueId: UniqueId) :
  EngineDescriptor(uniqueId, "Android Test Engine"), Node<AndroidTestExecutionContext> {

  override fun mayRegisterTests(): Boolean = true

  /**
   * Orchestrates the test execution.
   *
   * This method initializes the [AndroidDeviceDescriptor] for each device and triggers the test run.
   */
  override fun execute(context: AndroidTestExecutionContext, dynamicTestExecutor: Node.DynamicTestExecutor): AndroidTestExecutionContext {
    val config = context.configuration

    val devicesUniqueId = uniqueId.append("container", "devices")
    val devicesContainer = AndroidDevicesContainer(devicesUniqueId)
    devicesContainer.setParent(this)
    dynamicTestExecutor.execute(devicesContainer)

    config.deviceSerials.forEach { deviceSerial ->
      val deviceUniqueId = devicesUniqueId.append("device", deviceSerial)
      val deviceDescriptor = AndroidDeviceDescriptor(deviceUniqueId, deviceSerial)
      devicesContainer.addDevice(deviceDescriptor)
    }

    devicesContainer.finish()

    return context
  }
}
