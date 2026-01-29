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
package com.android.tools.journeys.testengine.resolver

import com.android.tools.journeys.testengine.JourneysTestEngineInput
import com.android.tools.journeys.testengine.descriptor.DeviceDescriptor
import com.android.tools.journeys.testengine.selector.DeviceSelector
import com.android.tools.journeys.testengine.selector.DeviceSpecificDirectorySelector
import java.util.Optional
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.discovery.ClassSelector
import org.junit.platform.engine.support.discovery.SelectorResolver
import org.junit.platform.engine.support.discovery.SelectorResolver.Match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution.match
import org.junit.platform.engine.support.discovery.SelectorResolver.Resolution.selectors

class DeviceSelectorResolver : SelectorResolver {

  override fun resolve(selector: ClassSelector, context: SelectorResolver.Context): Resolution {
    // Gradle's Test task only supports class-selector. To work around the limitation,
    // we use the "JourneysEntryPoint" class as an entry point and delegate the resolution
    // to DeviceSelector.
    return if (selector.className == "JourneysEntryPoint" && JourneysTestEngineInput.testDeviceIds.isNotEmpty()) {
      val deviceIds = JourneysTestEngineInput.testDeviceIds.split(",")
      val deviceNames = JourneysTestEngineInput.testDeviceDisplayNames.split(",")

      val deviceSelectors =
        deviceIds
          .mapIndexed { index, deviceId ->
            val deviceName = deviceNames.getOrNull(index)
            DeviceSelector(deviceId, deviceName)
          }
          .toSet()

      selectors(deviceSelectors)
    } else {
      Resolution.unresolved()
    }
  }

  override fun resolve(selector: DiscoverySelector, context: SelectorResolver.Context): Resolution {
    return if (selector is DeviceSelector) {
      resolve(selector, context)
    } else {
      super.resolve(selector, context)
    }
  }

  private fun resolve(selector: DeviceSelector, context: SelectorResolver.Context): Resolution {
    return context
      .addToParent { parent -> Optional.of(DeviceDescriptor(parent.uniqueId, selector.deviceSerialId, selector.deviceName)) }
      .map {
        match(
          Match.exact(it) {
            setOf(
              // We use a `DeviceSpecificDirectorySelector` instead of a simple `DirectorySelector`
              // to ensure JUnit 5 resolves tests uniquely for each device.
              //
              // Consider the scenario with two devices (Device1, Device2) and a single
              // `DirectorySelector` pointing to `/path/to/journeysTest/`.
              // If this `DirectorySelector` is resolved as a child for Device1,
              // JUnit 5 will not resolve it again for Device2, as it considers it already
              // processed. This prevents the tests in the directory from running on Device2.
              //
              // By creating unique `DeviceSpecificDirectorySelector` instances (one per device),
              // we ensure that tests within a directory are resolved and executed
              // for every target device.
              DeviceSpecificDirectorySelector(selector.deviceSerialId, JourneysTestEngineInput.journeysInputDir)
            )
          }
        )
      }
      .orElse(Resolution.unresolved())
  }
}
