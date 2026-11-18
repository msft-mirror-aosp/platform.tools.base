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

package com.android.tools.androidtest.testengine.collector

import com.android.tools.androidtest.testengine.adb.AdbController
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.logging.Logger

/**
 * Puts the file that lists the classes for the on-device coverage agent to instrument into the agent's data directory, where the agent
 * reads it when it attaches.
 *
 * As Android Studio uploads files to the data of an app, the file is pushed to a temporary location and copied with `run-as`. The copy is
 * renamed into place, and its size is checked, so that the agent doesn't see a partial file. Any problem, such as a failed command or an
 * exception from adb, leaves no list behind, so the agent instruments the classes under its package prefix as before: it never fails the
 * test run.
 */
class CoverageClassListInstaller(
  private val adbController: AdbController,
  private val deviceSerial: String,
  private val logger: Logger = Logger.getLogger(CoverageClassListInstaller::class.java.name),
) {

  /**
   * Puts [classList] into [dataDir], the agent's data directory in the data of [runAsPackageName], or removes any list there if [classList]
   * is null or missing. Returns whether the list is in place.
   */
  fun install(classList: File?, runAsPackageName: String, dataDir: String): Boolean {
    if (classList == null || !classList.isFile) {
      remove(runAsPackageName, dataDir)
      return false
    }
    val listPath = "$dataDir/$FILE_NAME"
    val partialPath = "$listPath.tmp"
    val tempPath = "$TEMP_DIR/coverage_classes_${runAsPackageName}_${UUID.randomUUID()}.txt"
    try {
      val push = adb { adbController.push(deviceSerial, classList.absolutePath, tempPath) }
      if (push.exitCode != 0) {
        return fail("Couldn't push ${classList.path}: ${push.errorOutput}", runAsPackageName, dataDir)
      }
      // The copy runs as the app, which reads the file through its permissions for others.
      shell(listOf("chmod", "644", tempPath))
      val copy =
        shell(
          listOf(
            "run-as",
            runAsPackageName,
            "sh",
            "-c",
            "\"rm -f '$listPath' '$partialPath'; cp '$tempPath' '$partialPath' && mv -f '$partialPath' '$listPath'\"",
          )
        )
      if (copy.exitCode != 0) {
        return fail("Couldn't copy the class list to $listPath: ${copy.errorOutput}", runAsPackageName, dataDir)
      }
      val size = shell(listOf("run-as", runAsPackageName, "stat", "-c", "%s", listPath)).output.trim()
      if (size != classList.length().toString()) {
        return fail("The class list at $listPath has $size bytes instead of ${classList.length()}", runAsPackageName, dataDir)
      }
      logger.info("Installed the coverage class list at $listPath")
      return true
    } finally {
      shell(listOf("rm", "-f", tempPath))
    }
  }

  /** Removes the list from [dataDir], the agent's data directory in the data of [runAsPackageName], if there is one. */
  fun remove(runAsPackageName: String, dataDir: String) {
    val listPath = "$dataDir/$FILE_NAME"
    shell(listOf("run-as", runAsPackageName, "rm", "-f", listPath, "$listPath.tmp"))
  }

  private fun fail(message: String, runAsPackageName: String, dataDir: String): Boolean {
    logger.warning("$message. The coverage agent instruments the classes under its package prefix instead.")
    remove(runAsPackageName, dataDir)
    return false
  }

  private fun shell(args: List<String>): AdbController.CommandResult = adb { adbController.runAdbShellCommand(deviceSerial, args) }

  /** Runs an adb [command]. An exception from adb itself, such as when adb can't start, counts as a failed command. */
  private fun adb(command: () -> AdbController.CommandResult): AdbController.CommandResult =
    try {
      command()
    } catch (e: IOException) {
      AdbController.CommandResult(-1, "", e.toString())
    }

  companion object {
    /**
     * The instrumentation argument in which AGP passes the path of the file on the host. It's for the engine only, so the engine doesn't
     * pass it on to the device.
     */
    const val INSTRUMENTATION_ARG = "com.android.tools.coverage.classList"

    /** The name of the file in the agent's data directory, where the agent looks for it. */
    const val FILE_NAME = "coverage_classes.txt"

    private const val TEMP_DIR = "/data/local/tmp"
  }
}
