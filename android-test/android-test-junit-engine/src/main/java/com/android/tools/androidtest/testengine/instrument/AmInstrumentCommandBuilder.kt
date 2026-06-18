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

/** A builder class for constructing `am instrument` commands. */
class AmInstrumentCommandBuilder {
  private var adbPath: String? = null
  private var deviceSerial: String? = null
  private var targetPackage: String? = null
  private var runnerClass: String? = null
  private var executionMode: String? = null
  private val instrumentationArgs = mutableMapOf<String, String>()
  private var isRaw: Boolean = true
  private var isWait: Boolean = true

  /** Sets the path to the ADB executable. */
  fun setAdbPath(adbPath: String) = apply { this.adbPath = adbPath }

  /** Sets the serial number of the target device. */
  fun setDeviceSerial(deviceSerial: String) = apply { this.deviceSerial = deviceSerial }

  /** Sets the instrumentation runner and target package. */
  fun setInstrumentationRunner(targetPackage: String, runnerClass: String) = apply {
    this.targetPackage = targetPackage
    this.runnerClass = runnerClass
  }

  /** Sets the execution mode (e.g., ANDROIDX_TEST_ORCHESTRATOR). */
  fun setExecutionMode(executionMode: String?) = apply { this.executionMode = executionMode }

  /** Adds a single instrumentation argument. */
  fun addInstrumentationArg(key: String, value: String) = apply { instrumentationArgs[key] = value }

  /** Adds multiple instrumentation arguments. */
  fun addInstrumentationArgs(args: Map<String, String>) = apply { instrumentationArgs.putAll(args) }

  /** Sets whether to output results in raw format (-r). Defaults to true. */
  fun setRaw(raw: Boolean) = apply { this.isRaw = raw }

  /** Sets whether to wait for instrumentation to terminate (-w). Defaults to true. */
  fun setWait(wait: Boolean) = apply { this.isWait = wait }

  /**
   * Builds the command as a list of strings.
   *
   * @return The constructed command list.
   * @throws IllegalStateException if any required parameters are missing.
   */
  fun build(): List<String> {
    val adb = adbPath ?: throw IllegalStateException("adbPath must be set")
    val serial = deviceSerial ?: throw IllegalStateException("deviceSerial must be set")
    val pkg = targetPackage ?: throw IllegalStateException("targetPackage must be set")
    val runner = runnerClass ?: throw IllegalStateException("runnerClass must be set")

    val isAndroidxOrchestrator = executionMode?.equals("ANDROIDX_TEST_ORCHESTRATOR", ignoreCase = true) ?: false
    val isLegacyOrchestrator = executionMode?.equals("ANDROID_TEST_ORCHESTRATOR", ignoreCase = true) ?: false
    val isOrchestrator = isAndroidxOrchestrator || isLegacyOrchestrator
    val useAndroidx = isAndroidxOrchestrator

    return mutableListOf<String>().apply {
      add(adb)
      add("-s")
      add(serial)
      add("shell")

      if (isOrchestrator) {
        val servicesPackage = if (useAndroidx) "androidx.test.services" else "android.support.test.services"
        val shellMainClass =
          if (useAndroidx) "androidx.test.services.shellexecutor.ShellMain" else "android.support.test.services.shellexecutor.ShellMain"

        add("CLASSPATH=$(pm path $servicesPackage)")
        add("app_process")
        add("/")
        add(shellMainClass)
      }

      add("am")
      add("instrument")
      if (isRaw) add("-r")
      if (isWait) add("-w")

      instrumentationArgs.forEach { (key, value) ->
        add("-e")
        add(key)
        add(value)
      }

      if (isOrchestrator) {
        val orchestratorPackage = if (useAndroidx) "androidx.test.orchestrator" else "android.support.test.orchestrator"
        val orchestratorClass =
          if (useAndroidx) "androidx.test.orchestrator.AndroidTestOrchestrator"
          else "android.support.test.orchestrator.AndroidTestOrchestrator"

        add("-e")
        add("targetInstrumentation")
        add("$pkg/$runner")
        add("$orchestratorPackage/$orchestratorClass")
      } else {
        add("$pkg/$runner")
      }
    }
  }
}
