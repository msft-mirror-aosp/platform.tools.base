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

import java.util.logging.Logger

/** Responsible for extracting the native coverage agent from the test APK to the target app's internal storage on the device. */
class CoverageAgentExtractor(
  private val adbController: AdbController,
  private val deviceSerial: String,
  private val logger: Logger = Logger.getLogger(CoverageAgentExtractor::class.java.name),
) {

  /** Extracts the native agent if needed and returns the pair of (agentPath, dataDir) on device. */
  fun extractAgentIfNeeded(testPackageId: String, instrumentationTargetPackageId: String): Pair<String, String>? {
    // 1. Determine Device ABI
    val abi = adbController.runAdbShellCommand(deviceSerial, listOf("getprop", "ro.product.cpu.abi")).output.trim()
    if (abi.isBlank()) {
      logger.warning("Failed to detect device ABI for agent extraction")
      return null
    }

    // 2. Discovery Phase: Find Test APK path (where the agent is bundled)
    // We take the first line to handle split APKs and remove the 'package:' prefix.
    var apkPath =
      adbController
        .runAdbShellCommand(deviceSerial, listOf("pm", "path", testPackageId))
        .output
        .lines()
        .firstOrNull { it.startsWith("package:") }
        ?.substringAfter("package:")
        ?.trim() ?: ""

    // Fallback: Attempt to find APK path via 'pm dump' if 'pm path' was empty
    if (apkPath.isBlank()) {
      logger.info("pm path returned empty for $testPackageId. Trying pm dump fallback...")
      val dumpOutput = adbController.runAdbShellCommand(deviceSerial, listOf("pm", "dump", testPackageId)).output
      apkPath = Regex("codePath=(.+)").find(dumpOutput)?.groupValues?.get(1)?.trim() ?: ""
    }

    if (apkPath.isBlank()) {
      logger.warning("Extraction Failed - Could not locate APK path for $testPackageId using pm path or pm dump")
      return null
    }

    // 3. Extract to the App's Internal Private Data directory (Secure and production-ready)
    // We target the instrumentationTargetPackageId because the instrumented process runs as the target app.
    val dataDir =
      adbController.runAdbShellCommand(deviceSerial, listOf("run-as", instrumentationTargetPackageId, "sh", "-c", "pwd")).output.trim()

    if (dataDir.isBlank() || dataDir.contains("not debuggable")) {
      logger.warning("Extraction Failed - Could not resolve data directory for $instrumentationTargetPackageId via run-as.")
      return null
    }

    val targetDir = "$dataDir/code_cache"
    logger.info("Internal Extraction: Extracting $abi agent from $apkPath to $targetDir")

    // 4. Create directory and extract
    // We use run-as with the target package to ensure correct file ownership and permissions.
    // We MUST wrap the command in escaped double-quotes so the inner shell handles the redirection.
    val extractShellCmd =
      "\"mkdir -p code_cache 2>/dev/null; unzip -p '$apkPath' lib/$abi/coverage_agent.so > code_cache/coverage_agent.so\""
    val extractCmd = listOf("run-as", instrumentationTargetPackageId, "sh", "-c", extractShellCmd)

    val result = adbController.runAdbShellCommand(deviceSerial, extractCmd)
    if (result.exitCode == 0) {
      val verifyResult =
        adbController.runAdbShellCommand(
          deviceSerial,
          listOf("run-as", instrumentationTargetPackageId, "ls", "-l", "code_cache/coverage_agent.so"),
        )
      if (verifyResult.exitCode == 0) {
        logger.info("Agent extraction VERIFIED: ${verifyResult.output.trim()}")
        return Pair("$targetDir/coverage_agent.so", targetDir)
      } else {
        logger.warning("Agent extraction failed verification: ${verifyResult.errorOutput}")
      }
    } else {
      logger.warning(
        "Agent extraction failed with exit code ${result.exitCode}: ${result.errorOutput} - Command: ${extractCmd.joinToString(" ")}"
      )
    }
    return null
  }
}
