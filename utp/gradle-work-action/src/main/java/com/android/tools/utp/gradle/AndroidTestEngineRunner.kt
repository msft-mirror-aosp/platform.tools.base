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

package com.android.tools.utp.gradle

import com.android.tools.androidtest.listener.AndroidTestResultListener
import com.android.tools.androidtest.testengine.PathSafety
import com.android.tools.utp.gradle.api.RunUtpWorkParameters
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.gradle.api.logging.Logging
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.EngineFilter
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

/**
 * Runs Android instrumentation tests using the Android Test JUnit Engine programmatically via the JUnit Platform Launcher.
 *
 * This is an interim solution to run the new Android Test Engine without requiring API-breaking changes in AGP 9.x (such as migrating to
 * the Test Suite API). It bypasses the UTP binary execution by running the engine in-process within the Gradle Worker isolated ClassLoader.
 *
 * TODO(b/535331959): Remove this class in AGP 10.0 when we fully migrate to the Test Suite API.
 */
class AndroidTestEngineRunner(
  private val launcherExecutor: (LauncherDiscoveryRequest, TestExecutionListener) -> Map<String, Boolean> = { request, listener ->
    val trackingListener = DeviceTrackingListener()
    val launcher =
      LauncherFactory.create(
        LauncherConfig.builder()
          .enableTestExecutionListenerAutoRegistration(false)
          .addTestExecutionListeners(listener, trackingListener)
          .build()
      )
    launcher.execute(request)
    trackingListener.perDeviceAllTestsPassed
  }
) {

  companion object {
    private val logger = Logging.getLogger(AndroidTestEngineRunner::class.java)
  }

  fun execute(
    parameters: RunUtpWorkParameters,
    resultsProtoOutputFiles: List<File>,
    mergedResultProtoOutputFile: File,
    exitCodeFile: File,
  ) {
    val utpRunConfigs = parameters.utpRunConfigs.get()
    val deviceShardNames = utpRunConfigs.map { it.deviceShardName.get() }
    logger.lifecycle("Running tests on devices: ${deviceShardNames.joinToString(", ")}")

    var allPassed = true

    // Run launcher once for all devices
    try {
      val request = LauncherDiscoveryRequestBuilder.request().filters(EngineFilter.includeEngines("android-test-engine")).build()
      val listener = AndroidTestResultListener()
      val perDeviceAllTestsPassed = launcherExecutor(request, listener)
      val serials = utpRunConfigs.map { it.deviceSerialNumber.get() }
      allPassed = serials.all { perDeviceAllTestsPassed[it] ?: false }
    } catch (t: Throwable) {
      logger.error("Failed to execute launcher", t)
      allPassed = false
    }

    // Map, copy and merge results
    try {
      val resultsMerger = UtpTestSuiteResultMerger()
      val resultsDir = parameters.xmlTestReportOutputDirectory.get().asFile
      val utpRunConfigs = parameters.utpRunConfigs.get()

      for (config in utpRunConfigs) {
        val deviceId = config.deviceId.get()
        val sanitizedDeviceId = PathSafety.sanitizeDisplayName(deviceId)

        // Find file under resultsDir where parent directory contains sanitizedDeviceId
        val file =
          resultsDir.walkTopDown().filter { it.name == "test-result.pb" && it.parentFile.name.contains(sanitizedDeviceId) }.firstOrNull()

        if (file != null) {
          val targetFile = config.utpResultProtoOutputFile.get().asFile
          targetFile.parentFile?.mkdirs()
          file.copyTo(targetFile, overwrite = true)

          targetFile.inputStream().use {
            val result = TestSuiteResult.parseFrom(it)
            resultsMerger.merge(result)
          }
          // Clean up the original file and its parent if empty
          file.delete()
          file.parentFile.delete()
        } else {
          logger.warn("Could not find test-result.pb for device: $deviceId in $resultsDir")
        }

        // The Android Test Engine writes JUnit XML reports (TEST-*.xml) to the device-specific
        // output directory (resultsDir/device_name) because it is configured with per-device
        // results directories to avoid write conflicts.
        // However, the DeviceProviderInstrumentTestTask's TestReport generator only looks for
        // XML files in the root resultsDir directly and does not scan subdirectories.
        // Therefore, we must copy all generated XML reports to the base resultsDir so that
        // the HTML test report can be generated successfully.
        val deviceName = config.deviceName.get()

        val deviceOutputDir = config.outputDir.get().asFile
        deviceOutputDir.listFiles()?.forEach { xmlFile ->
          if (xmlFile.name.startsWith("TEST-") && xmlFile.extension == "xml") {
            val expectedXmlName = "TEST-${deviceName}.xml"
            val targetXmlFile = File(resultsDir, expectedXmlName)
            if (xmlFile.absolutePath != targetXmlFile.absolutePath) {
              xmlFile.copyTo(targetXmlFile, overwrite = true)
              xmlFile.delete()
            }
          }
        }
      }

      mergedResultProtoOutputFile.parentFile?.mkdirs()
      mergedResultProtoOutputFile.outputStream().use { resultsMerger.result.writeTo(it) }
    } catch (t: Throwable) {
      logger.error("Failed to merge test results", t)
      allPassed = false
    }

    // Write exit code
    try {
      exitCodeFile.parentFile?.mkdirs()
      exitCodeFile.writeText(if (allPassed) "0" else "1")
    } catch (t: Throwable) {
      logger.error("Failed to write exit code file", t)
    }
  }
}

private class DeviceTrackingListener : TestExecutionListener {
  val perDeviceAllTestsPassed = ConcurrentHashMap<String, Boolean>()

  override fun executionStarted(testIdentifier: TestIdentifier) {
    val deviceId = testIdentifier.getDeviceId() ?: ""
    if (testIdentifier.isContainer && deviceId.isNotEmpty()) {
      perDeviceAllTestsPassed.putIfAbsent(deviceId, true)
    }
  }

  override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
    val deviceId = testIdentifier.getDeviceId() ?: ""
    if (testIdentifier.isTest && deviceId.isNotEmpty()) {
      if (testExecutionResult.status == TestExecutionResult.Status.FAILED) {
        perDeviceAllTestsPassed[deviceId] = false
      }
    }
  }

  private fun TestIdentifier.getDeviceId(): String? {
    val devicePart = uniqueId.substringAfterLast("[device:", "")
    return if (devicePart.isNotEmpty()) {
      devicePart.substringBefore("]")
    } else {
      null
    }
  }
}
