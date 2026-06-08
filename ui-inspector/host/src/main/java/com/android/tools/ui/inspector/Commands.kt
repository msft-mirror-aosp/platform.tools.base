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

package com.android.tools.ui.inspector

import com.android.adblib.AdbHostServices
import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.shellAsText
import java.io.File

/**
 * Discovers and prints the serial number of all currently connected Android devices.
 *
 * @param adbSession The [AdbSession] to communicate with the local ADB server.
 */
suspend fun doListDevices(adbSession: AdbSession) {
  val devices = adbSession.hostServices.devices(AdbHostServices.DeviceInfoFormat.SHORT_FORMAT)
  if (devices.isEmpty()) {
    println("No devices connected.")
  } else {
    devices.forEach { println(it.serialNumber) }
  }
}

/**
 * Scans running processes on the device and prints the package names of all debuggable applications.
 *
 * @param adbSession The [AdbSession] to communicate with the local ADB server.
 * @param serial The serial number of the target device.
 */
suspend fun doListPackages(adbSession: AdbSession, serial: String) {
  val selector = DeviceSelector.fromSerialNumber(serial)
  // List all active processes on the device. The last column contains the process/package name.
  val psOutput = adbSession.deviceServices.shellAsText(selector, "ps -A").stdout.trim()
  if (psOutput.isEmpty()) {
    println("No processes found.")
    return
  }

  // Parse package names
  val candidatePackages =
    psOutput
      .split("\n")
      .drop(1)
      .map { line -> line.split("\\s+".toRegex()).last() }
      .filter { it.contains(".") && !it.startsWith("/") }
      .distinct()

  val debuggablePackages = mutableListOf<String>()
  candidatePackages.forEach { pkg ->
    try {
      // An application is debuggable if run-as succeeds for its package name.
      val runAsResult = adbSession.deviceServices.shellAsText(selector, "run-as $pkg id")
      if (runAsResult.exitCode == 0) {
        debuggablePackages.add(pkg)
      }
    } catch (ignored: Exception) {}
  }

  if (debuggablePackages.isEmpty()) {
    println("No debuggable packages found.")
  } else {
    debuggablePackages.sorted().forEach { println(it) }
  }
}

/**
 * Injects the UI Inspector agent into the target application, attaches to its layout inspector service, and dumps the unified View and
 * Compose tree structure to the console.
 *
 * @param adbSession The [AdbSession] to communicate with the local ADB server.
 * @param serial The serial number of the target device.
 * @param packageName The application package name to dump.
 * @param includeAttributes If true, includes view attributes in the dump output.
 * @param includeResolutionStack If true, includes attribute resolution stacks in the dump output.
 * @param includeSystemComposables If true, includes system/framework composable nodes.
 * @param includeSemantics If true, includes accessibility semantics in the Compose dump.
 * @param composeInspectorJarPath Optional path to a local Compose Inspector JAR file.
 */
suspend fun doDumpUi(
  adbSession: AdbSession,
  serial: String,
  packageName: String,
  includeAttributes: Boolean,
  includeResolutionStack: Boolean,
  includeSystemComposables: Boolean,
  includeSemantics: Boolean,
  composeInspectorJarPath: String?,
) {
  try {
    val injectionManager = InjectionManager(adbSession, serial, packageName)
    val port = injectionManager.injectAndAttach()
    CommandSender(host = "localhost", port = port.toInt()).use { commandSender ->
      createViewInspector(commandSender, injectionManager)

      val localJarProvider =
        composeInspectorJarPath?.let { path ->
          { _: String ->
            val file = File(path)
            if (!file.exists() || !file.isFile) {
              throw IllegalArgumentException("Specified Compose Inspector JAR does not exist: $path")
            }
            file
          }
        }

      val composeInspectorConnected =
        if (localJarProvider != null) {
          createComposeInspector(commandSender, injectionManager, localJarProvider)
        } else {
          createComposeInspector(commandSender, injectionManager)
        }

      dumpUiTree(
        commandSender = commandSender,
        includeAttributes = includeAttributes,
        includeResolutionStack = includeResolutionStack,
        composeInspectorConnected = composeInspectorConnected,
        skipSystemComposables = !includeSystemComposables,
        includeSemantics = includeSemantics,
      )
    }
  } catch (e: EmptyViewRootsException) {
    throw Exception(
      "No active window roots found for package '$packageName'. Please make sure the app is in the foreground and has visible layout views."
    )
  }
}

/** Dumps the View tree, enriches it with Compose if active, and prints the unified tree to console. */
internal suspend fun dumpUiTree(
  commandSender: CommandSender,
  includeAttributes: Boolean,
  includeResolutionStack: Boolean,
  composeInspectorConnected: Boolean,
  skipSystemComposables: Boolean,
  includeSemantics: Boolean,
) {
  val result = dumpViews(commandSender, includeAttributes, includeResolutionStack)
  val viewRoots = result.roots
  if (viewRoots.isEmpty()) {
    throw EmptyViewRootsException()
  }
  val configuration = result.configuration
  val stringTable = result.stringTable

  if (composeInspectorConnected) {
    fetchAndMergeComposeTrees(commandSender, viewRoots, includeAttributes, skipSystemComposables, includeSemantics)
  }
  configuration?.let { printDeviceConfiguration(it, stringTable) }
  viewRoots.forEach { printUiTree(it, 0, includeAttributes, includeSemantics) }
}

/** Queries the Compose Layout Inspector on the device and merges its trees into [viewRoots] in-place. */
private suspend fun fetchAndMergeComposeTrees(
  commandSender: CommandSender,
  viewRoots: List<UiNode.ViewNode>,
  includeParameters: Boolean,
  skipSystemComposables: Boolean,
  includeSemantics: Boolean,
) {
  viewRoots.forEach { viewRoot ->
    // In the compose inspector, standard parameters and semantics (accessibility properties) are fetched together with a single command.
    val fetchComposeDetails = includeParameters || includeSemantics

    val composeResult =
      queryComposeTree(
        commandSender = commandSender,
        rootViewId = viewRoot.id,
        includeParameters = fetchComposeDetails,
        skipSystemComposables = skipSystemComposables,
      )
    if (composeResult != null) {
      val (roots, stringsMap) = composeResult

      val composeParameters =
        if (fetchComposeDetails) {
          queryComposeParameters(commandSender, viewRoot.id, skipSystemComposables)
        } else {
          null
        }

      roots.forEach { composeRoot ->
        attachComposeTree(
          viewNode = viewRoot,
          targetViewId = composeRoot.viewId,
          composeNodes = composeRoot.nodesList,
          stringTable = stringsMap,
          viewsToSkip = composeRoot.viewsToSkipList,
          parameters = composeParameters,
        )
      }
    }
  }
}

private class EmptyViewRootsException : Exception()
