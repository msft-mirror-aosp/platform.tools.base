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
import com.android.tools.androidtest.testengine.adb.AdbController
import com.android.tools.androidtest.testengine.collector.CoverageAgentExtractor
import com.android.tools.androidtest.testengine.config.AndroidTestConfiguration
import com.android.tools.androidtest.testengine.util.PathSafety
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
class AndroidTestEngineDescriptor(
  uniqueId: UniqueId,
  private val deviceDescriptorFactory:
    (
      uniqueId: UniqueId,
      deviceSerial: String,
      deviceId: String,
      deviceDisplayName: String,
      jvmtiCodeCoverageAgentPathProvider: () -> Pair<String, String>?,
    ) -> AndroidDeviceDescriptor =
    { uid, serial, id, displayName, jvmtiProvider ->
      AndroidDeviceDescriptor(uid, serial, id, displayName, jvmtiCodeCoverageAgentPathProvider = jvmtiProvider)
    },
) : EngineDescriptor(uniqueId, "Android Test Engine"), Node<AndroidTestExecutionContext> {

  override fun mayRegisterTests(): Boolean = true

  /**
   * Orchestrates the test execution.
   *
   * This method initializes the [AndroidDeviceDescriptor] for each device and triggers the test run.
   */
  override fun execute(context: AndroidTestExecutionContext, dynamicTestExecutor: Node.DynamicTestExecutor): AndroidTestExecutionContext {
    val config = context.configuration
    val adbController = AdbController(config.adb)

    val deviceDescriptors =
      config.deviceSerials.map { deviceSerial ->
        val androidVersion = getAndroidVersion(adbController, deviceSerial)
        val rawDefaultDisplayName = if (androidVersion.isNotEmpty()) "$deviceSerial - $androidVersion" else deviceSerial
        val defaultDisplayName = PathSafety.sanitizeDisplayName(rawDefaultDisplayName)
        val rawDeviceId = config.getDeviceId(deviceSerial)
        val deviceId = if (rawDeviceId != null) PathSafety.sanitizeDisplayName(rawDeviceId) else defaultDisplayName
        // Android Studio expects the device serial in the UniqueId to match results
        // with its internal device model.
        val deviceUniqueId = uniqueId.append("device", deviceSerial)
        val deviceDisplayName = if (deviceId != defaultDisplayName) "$deviceId ($defaultDisplayName)" else defaultDisplayName

        val extractor = CoverageAgentExtractor(adbController, deviceSerial)
        val deviceDescriptor =
          deviceDescriptorFactory(
            deviceUniqueId,
            deviceSerial,
            deviceId,
            deviceDisplayName,
          ) {
            if (config.coverageType == AndroidTestConfiguration.CoverageType.ON_THE_FLY) {
              extractor.extractAgentIfNeeded(config.testPackageId, config.instrumentationTargetPackageId)
            } else {
              null
            }
          }
        deviceDescriptor.setParent(this)
        deviceDescriptor
      }

    // 1. Start test execution on all devices in parallel background threads.
    deviceDescriptors.forEach { it.startRunner(context) }

    // 2. Report device results. If parallel reporting is enabled, execute devices concurrently;
    // otherwise, report sequentially to JUnit Platform / Gradle to avoid concurrent container issues.
    if (config.parallelTestResultReporting) {
      deviceDescriptors.forEach { deviceDescriptor ->
        dynamicTestExecutor.execute(deviceDescriptor)
      }
      dynamicTestExecutor.awaitFinished()
    } else {
      deviceDescriptors.forEach { deviceDescriptor ->
        dynamicTestExecutor.execute(deviceDescriptor)
        dynamicTestExecutor.awaitFinished()
      }
    }

    return context
  }

  private fun getAndroidVersion(adbController: AdbController, serial: String): String {
    return try {
      val result = adbController.runAdbShellCommand(serial, listOf("getprop", "ro.build.version.release"))
      if (result.exitCode == 0) result.output.trim() else ""
    } catch (t: Throwable) {
      ""
    }
  }
}
