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

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.hierarchical.Node

/** A container for multiple Android devices. */
class AndroidDevicesContainer(uniqueId: UniqueId) : AbstractTestDescriptor(uniqueId, "Devices"), Node<AndroidTestExecutionContext> {

  private val devices = Channel<AndroidDeviceDescriptor>(Channel.UNLIMITED)

  override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER

  override fun execute(context: AndroidTestExecutionContext, dynamicTestExecutor: Node.DynamicTestExecutor): AndroidTestExecutionContext {
    runBlocking {
      for (device in devices) {
        dynamicTestExecutor.execute(device)
      }
    }
    return context
  }

  fun addDevice(device: AndroidDeviceDescriptor) {
    device.setParent(this)
    devices.trySend(device).getOrThrow()
  }

  fun finish() {
    devices.close()
  }
}
