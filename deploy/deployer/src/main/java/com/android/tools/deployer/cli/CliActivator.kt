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
package com.android.tools.deployer.cli

import com.android.adblib.DeviceSelector
import com.android.adblib.ShellCommandOutputElement
import com.android.adblib.shellAsLines
import com.android.adblib.tools.createStandaloneSession
import com.android.tools.deployer.model.App
import com.android.tools.deployer.model.activate.ActivationCommandResultChecker
import com.android.tools.deployer.model.component.AppComponent
import com.android.tools.deployer.model.component.ComponentType
import com.android.utils.StdLogger
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import kotlinx.coroutines.runBlocking

/**
 * Prototype / Proof of concept of what a CLI activator would look like.
 *
 * It is here just to demonstrate how much dependencies we need and how big the deploy_jar might be.
 */
class CliActivator {
  val apkPaths = mutableListOf<Path>()
  var deviceSerial: String? = null
  var componentType: ComponentType = ComponentType.ACTIVITY
  var mode: AppComponent.Mode = AppComponent.Mode.RUN
  var activityName: String? = null

  private fun parse(args: Array<String>) {
    var i = 0
    while (i < args.size) {
      val arg = args[i]
      if (arg.startsWith("--apks=")) {
        val paths = arg.substring("--apks=".length).split(",")
        paths.forEach { apkPaths.add(Paths.get(it)) }
      } else if (arg == "--apks") {
        if (i + 1 < args.size) {
          val paths = args[++i].split(",")
          paths.forEach { apkPaths.add(Paths.get(it)) }
        }
      } else if (arg.startsWith("--device=")) {
        deviceSerial = arg.substring("--device=".length)
      } else if (arg == "--device") {
        if (i + 1 < args.size) {
          deviceSerial = args[++i]
        }
      } else if (arg.startsWith("--type=")) {
        componentType = ComponentType.valueOf(arg.substring("--type=".length).uppercase(Locale.US))
      } else if (arg == "--type") {
        if (i + 1 < args.size) {
          componentType = ComponentType.valueOf(args[++i].uppercase(Locale.US))
        }
      } else if (arg.startsWith("--activity=")) {
        activityName = arg.substring("--activity=".length)
      } else if (arg == "--activity") {
        if (i + 1 < args.size) {
          activityName = args[++i]
        }
      } else if (arg == "--debug") {
        mode = AppComponent.Mode.DEBUG
      }
      i++
    }
  }

  companion object {
    @JvmStatic
    fun main(args: Array<String>) {
      val activator = CliActivator()
      activator.parse(args)

      if (activator.apkPaths.isEmpty()) {
        println("No apks specified. Use --apks <path1,path2,...>")
        return
      }

      val logger = StdLogger(StdLogger.Level.VERBOSE)
      try {
        val app = App.fromPaths(activator.apkPaths)
        println("App loaded: ${app.appId}")
        println("Debuggable: ${app.isDebuggable}")

        var matching = app.getMatchingComponents(activator.componentType, logger)
        if (activator.activityName != null) {
          val searchName =
            if (activator.activityName!!.startsWith(".")) {
              app.appId + activator.activityName
            } else {
              activator.activityName!!
            }
          matching = matching.filter { it.info.qualifiedName == searchName }
        }

        if (matching.isEmpty()) {
          println(
            "No matching components found for type ${activator.componentType}${if (activator.activityName != null) " with name ${activator.activityName}" else ""}"
          )
          return
        }

        if (matching.size > 1) {
          println("Multiple candidates found for type ${activator.componentType}:")
          matching.forEach { println("  - ${it.info.qualifiedName}") }
          println("Please specify component using --activity <name>")
          return
        }

        val component = matching[0]
        println("Selected component: ${component.info.qualifiedName}")

        val commands = component.getActivationCommands("", activator.mode)
        if (commands == null) {
          println("No activation commands for this component")
          return
        }

        val session = createStandaloneSession()
        val serial = activator.deviceSerial ?: ""
        val deviceSelector = if (serial.isEmpty()) DeviceSelector.any() else DeviceSelector.fromSerialNumber(serial)

        runBlocking {
          for (cmd in commands) {
            println("Executing: ${cmd.status ?: cmd.command}")
            session.deviceServices.shellAsLines(deviceSelector, cmd.command).collect { line ->
              when (line) {
                is ShellCommandOutputElement.StdoutLine -> cmd.checker.processLines(arrayOf(line.contents))
                is ShellCommandOutputElement.StderrLine -> cmd.checker.processLines(arrayOf(line.contents))
                is ShellCommandOutputElement.ExitCode -> {}
              }
            }
            val result = cmd.checker.check()
            if (result == ActivationCommandResultChecker.Status.ERROR) {
              println("Error executing command: ${cmd.command}")
              return@runBlocking
            }
          }
          println("Activation completed successfully")
        }
      } catch (e: Exception) {
        println("Error during activation: ${e.message}")
        e.printStackTrace()
      }
    }
  }
}
