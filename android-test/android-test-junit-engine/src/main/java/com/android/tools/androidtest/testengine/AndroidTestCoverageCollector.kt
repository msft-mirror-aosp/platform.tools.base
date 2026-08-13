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

import java.io.File
import java.util.logging.Level
import java.util.logging.Logger

/** A collector that retrieves test coverage data files (.ec) from a device to a host machine. */
class AndroidTestCoverageCollector(
  private val adbController: AdbController,
  private val deviceSerial: String,
  private val coverageDirOnHost: File?,
  private val coverageFileOnDevice: String?,
  private val coverageDirOnDevice: String?,
  private val useTestStorageService: Boolean,
  private val additionalTestOutputCollector: AndroidAdditionalTestOutputCollector,
  private val runAsPackageName: String? = null,
  private val agentFilesystemInfo: CoverageAgentFilesystemInfo = CoverageAgentFilesystemInfo(),
  private val logger: Logger = Logger.getLogger(AndroidTestCoverageCollector::class.java.name),
  private val deviceApiLevelProvider: DeviceApiLevelProvider? = null,
) {

  private var effectiveUseTestStorageService: Boolean = false

  /** Prepares directories on host and device before test execution. */
  fun prepare() {
    logger.info("Preparing code coverage collector. hostDir=$coverageDirOnHost, singleFile=$coverageFileOnDevice, dir=$coverageDirOnDevice")

    val isTestServiceInstalled = isTestServiceInstalled()
    if (useTestStorageService && !isTestServiceInstalled) {
      logger.warning("useTestStorageService is requested but TestStorageService is not installed on device.")
    }

    effectiveUseTestStorageService = useTestStorageService && isTestServiceInstalled
    if (effectiveUseTestStorageService) {
      val apiLevel = getApiLevel()
      if (apiLevel >= 30) {
        // Grant MANAGE_EXTERNAL_STORAGE permission to androidx.test.services so that it
        // can write test artifacts in external storage.
        adbController.runAdbShellCommand(
          deviceSerial,
          listOf("appops", "set", "androidx.test.services", "MANAGE_EXTERNAL_STORAGE", "allow"),
        )
      }
    }

    coverageDirOnHost?.let { createEmptyDirectoryOnHost(it) }
    cleanPreviousCodeCoverageOnDevice()
  }

  /** Creates an empty directory. If a directory exists at the given path, it removes all contents in the directory. */
  private fun createEmptyDirectoryOnHost(directory: File) {
    val p = directory.toPath().toAbsolutePath()
    // Defense-in-depth: Ensure the path is lexically normalized to prevent
    // directory traversal via deleteRecursively() (b/509645146).
    require(p == p.normalize()) { "Refusing deleteRecursively() on un-normalised path: $p" }
    if (directory.exists()) {
      directory.deleteRecursively()
    }
    directory.mkdirs()
  }

  /** Removes code coverages data on device from previous runs if exists. */
  private fun cleanPreviousCodeCoverageOnDevice() {
    val storageDir = AndroidAdditionalTestOutputCollector.TEST_STORAGE_SERVICE_INTERNAL_OUTPUT_DIR.removeSuffix("/")

    val dataDir = agentFilesystemInfo.dataDirectoryOnDevice
    if (dataDir != null) {
      runShellCommandWithRunAs(listOf("rm", "-rf", dataDir))
      runShellCommandWithRunAs(listOf("mkdir", "-p", dataDir))
      return
    }

    if (!coverageFileOnDevice.isNullOrBlank()) {
      val devicePath =
        if (effectiveUseTestStorageService) {
          "$storageDir/${coverageFileOnDevice.removePrefix("/")}"
        } else {
          coverageFileOnDevice
        }
      runShellCommandWithRunAs(listOf("rm", "-f", devicePath))
    } else if (!coverageDirOnDevice.isNullOrBlank()) {
      val devicePath =
        if (effectiveUseTestStorageService) {
          "$storageDir/${coverageDirOnDevice.removePrefix("/")}"
        } else {
          coverageDirOnDevice
        }
      runShellCommandWithRunAs(listOf("rm", "-rf", devicePath))
      runShellCommandWithRunAs(listOf("mkdir", "-p", devicePath))
    }
  }

  private fun runShellCommandWithRunAs(commands: List<String>): AdbController.CommandResult {
    val lastArg = commands.lastOrNull()
    return if (!runAsPackageName.isNullOrBlank() && lastArg != null && lastArg.startsWith("/data/")) {
      adbController.runAdbShellCommand(deviceSerial, listOf("run-as", runAsPackageName) + commands)
    } else {
      adbController.runAdbShellCommand(deviceSerial, commands)
    }
  }

  /** Collects code coverage data from the device after test execution. */
  fun collect() {
    logger.info("Collecting code coverage data.")
    if (coverageDirOnHost == null) return
    try {
      val storageDir = AndroidAdditionalTestOutputCollector.TEST_STORAGE_SERVICE_INTERNAL_OUTPUT_DIR.removeSuffix("/")

      // On-the-fly coverage artifacts from the agent.
      val agentDataDir = agentFilesystemInfo.dataDirectoryOnDevice
      if (agentDataDir != null) {
        logger.info("Pulling coverage artifacts from: $agentDataDir")
        additionalTestOutputCollector.pullDirectory(agentDataDir, coverageDirOnHost, ".pb")
        return
      }

      if (!coverageFileOnDevice.isNullOrBlank()) {
        val devicePath =
          if (effectiveUseTestStorageService) {
            "$storageDir/${coverageFileOnDevice.removePrefix("/")}"
          } else {
            coverageFileOnDevice
          }
        val hostFile = File(coverageDirOnHost, File(devicePath).name)
        logger.info("Pulling coverage file: $devicePath to ${hostFile.absolutePath}")
        additionalTestOutputCollector.pullFile(devicePath, hostFile)
      } else if (!coverageDirOnDevice.isNullOrBlank()) {
        val devicePath =
          if (effectiveUseTestStorageService) {
            "$storageDir/${coverageDirOnDevice.removePrefix("/")}"
          } else {
            coverageDirOnDevice
          }
        logger.info("Pulling coverage directory: $devicePath to ${coverageDirOnHost.absolutePath}")
        additionalTestOutputCollector.pullDirectory(devicePath, coverageDirOnHost, ".ec")
      }
    } catch (e: Exception) {
      logger.log(Level.WARNING, "Failed to retrieve code coverage data from device.", e)
    }
  }

  private fun isTestServiceInstalled(): Boolean {
    return adbController
      .runAdbShellCommand(deviceSerial, listOf("pm", "list", "packages", "androidx.test.services"))
      .output
      .contains("package:androidx.test.services")
  }

  private fun getApiLevel(): Int {
    return deviceApiLevelProvider?.deviceApiLevel
      ?: adbController.runAdbShellCommand(deviceSerial, listOf("getprop", "ro.build.version.sdk")).output.trim().toIntOrNull()
      ?: 0
  }
}
