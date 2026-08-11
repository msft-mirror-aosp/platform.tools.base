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

package com.android.tools.androidtest.testengine.instrument

import com.android.tools.androidtest.testengine.CoverageAgentFilesystemInfo
import java.io.File
import java.util.logging.Logger

/**
 * Executes Android instrumentation tests on a given device via the `am instrument` command.
 *
 * This class builds and runs the `am instrument -r -w` command, capturing and parsing the raw output in real-time to report test events.
 *
 * @param adb The ADB executable [File].
 * @param deviceSerial The serial number of the target Android device.
 * @param instrumentationRunnerClass The fully qualified name of the instrumentation runner (e.g.,
 *   `androidx.test.runner.AndroidJUnitRunner`).
 * @param testPackageId The package ID of the test APK containing the instrumentation runner.
 * @param listeners A set of [AmInstrumentationListener]s to receive test events.
 * @param logger An optional [Logger] for recording command outputs and warnings.
 * @param processBuilder A factory for creating [ProcessBuilder] instances, primarily exposed for testing purposes to allow mocking of
 *   process execution.
 */
class AmInstrumentationRunner(
  private val adb: File,
  private val deviceSerial: String,
  private val instrumentationRunnerClass: String,
  private val testPackageId: String,
  private val instrumentationTargetPackageId: String,
  private val executionMode: String? = null,
  private val instrumentationArgs: Map<String, String> = emptyMap(),
  private val listeners: Set<AmInstrumentationListener> = emptySet(),
  private val agentFilesystemInfo: CoverageAgentFilesystemInfo = CoverageAgentFilesystemInfo(),
  private val logger: Logger = Logger.getLogger(AmInstrumentationRunner::class.java.name),
  private val processBuilder: (command: List<String>) -> ProcessBuilder = { ProcessBuilder(it) },
) {

  /**
   * Runs the `am instrument` command for the configured target.
   *
   * This method constructs the command, launches the process, and synchronously captures and parses the output until the process
   * terminates. Standard output is parsed as test events, while standard error is logged as warnings.
   */
  fun runAmInstrumentCommand() {
    val command = getAmInstrumentCmd()
    val shellIdx = command.indexOf("shell")
    val adbPath = command.first()
    val adbArgs = command.subList(1, shellIdx)
    val shellCommand = command.subList(shellIdx + 1, command.size)

    logger.info("Running instrumentation: $adbPath ${adbArgs.joinToString(" ")} shell \"${shellCommand.joinToString(" ")}\"")
    val process = processBuilder(command).start()
    val parser = AmInstrumentationParser(listeners = listeners)
    val outThread =
      Thread(
        { process.inputStream.bufferedReader().useLines { lines -> lines.forEach { parser.parse(it) } } },
        "AmInstrumentationRunner-out",
      )
    val errThread =
      Thread(
        { process.errorStream.bufferedReader().useLines { lines -> lines.forEach { logger.warning(it) } } },
        "AmInstrumentationRunner-err",
      )

    outThread.start()
    errThread.start()

    outThread.join()
    errThread.join()

    parser.done()
  }

  private fun getAmInstrumentCmd(): List<String> {
    val builder =
      AmInstrumentCommandBuilder()
        .setAdbPath(adb.absolutePath)
        .setDeviceSerial(deviceSerial)
        .setInstrumentationRunner(testPackageId, instrumentationRunnerClass)
        .setExecutionMode(executionMode)
        .addInstrumentationArgs(instrumentationArgs)

    val agentPath = agentFilesystemInfo.agentBinaryPathOnDevice
    val dataDir = agentFilesystemInfo.dataDirectoryOnDevice

    if (agentPath != null && dataDir != null) {
      builder.addInstrumentationArg("coverage", "true")

      // The agent expects options in the format: "package_name,prefix,data_dir"
      val prefix =
        instrumentationArgs["com.android.tools.coverage.prefixes"]
          ?: run {
            val targetPackage = instrumentationArgs["targetPackage"] ?: instrumentationTargetPackageId
            targetPackage.replace(".", "/")
          }

      val options = "$testPackageId,$prefix,$dataDir"
      val config = "$agentPath=$options"

      // Append our Java-API attacher to the listener list.
      val existingListeners = instrumentationArgs["listener"]
      val updatedListeners =
        if (existingListeners.isNullOrBlank()) {
          "com.android.tools.coverage.CoverageAgentAttacher"
        } else {
          "$existingListeners,com.android.tools.coverage.CoverageAgentAttacher"
        }

      builder.addInstrumentationArg("listener", updatedListeners)

      // Pass the configuration for the Java API attacher.
      builder.addInstrumentationArg("coverage-agent-config", config)
    }

    return builder.build()
  }
}
