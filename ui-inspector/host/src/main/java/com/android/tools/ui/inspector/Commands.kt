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
import com.android.adblib.DeviceState
import com.android.adblib.shellAsText
import com.android.tools.ui.inspector.printer.UiDumpPrinter
import java.io.File
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

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
 * Resolves the serial number of the device to target. A [requested] serial is returned unchanged; when omitted, the serial of the only
 * online device is used.
 *
 * @throws IllegalStateException when no serial is requested and there is not exactly one online device.
 */
internal suspend fun resolveDeviceSerial(adbSession: AdbSession, requested: String?): String {
  // A requested serial is deliberately not validated against the device list: the list is only a snapshot (a device
  // can change state right after the check), and the adb server already rejects unusable serials authoritatively
  // when the first command reaches it. This matches adb's own behavior with -s.
  if (requested != null) return requested
  val devices = adbSession.hostServices.devices(AdbHostServices.DeviceInfoFormat.SHORT_FORMAT)
  val onlineDevices = devices.filter { it.deviceState == DeviceState.ONLINE }
  return when {
    onlineDevices.size == 1 -> onlineDevices.single().serialNumber
    devices.isEmpty() -> throw IllegalStateException("No connected devices found. Connect a device or select one with --device.")
    onlineDevices.isEmpty() -> {
      val states = devices.sortedBy { it.serialNumber }.joinToString { "${it.serialNumber} (${it.deviceStateString})" }
      throw IllegalStateException("No online devices found. Connected devices: $states.")
    }
    else -> {
      val serials = onlineDevices.map { it.serialNumber }.sorted().joinToString()
      throw IllegalStateException("Multiple online devices found: $serials. Select one with --device.")
    }
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
internal suspend fun doDumpUi(
  adbSession: AdbSession,
  serial: String,
  packageName: String,
  includeAttributes: Boolean,
  includeResolutionStack: Boolean,
  includeSystemComposables: Boolean,
  includeSemantics: Boolean,
  composeInspectorJarPath: String?,
  printer: UiDumpPrinter,
) {
  runWithConnectedInspectors(adbSession, serial, packageName, composeInspectorJarPath) { commandSender, composeInspectorConnected ->
    dumpUi(
      commandSender = commandSender,
      includeAttributes = includeAttributes,
      includeResolutionStack = includeResolutionStack,
      composeInspectorConnected = composeInspectorConnected,
      skipSystemComposables = !includeSystemComposables,
      includeSemantics = includeSemantics,
      printer = printer,
    )
  }
}

/**
 * Injects the UI Inspector agent into the target application, attaches to its layout inspector service, and samples the UI hierarchy
 * changes over time.
 *
 * @param adbSession The [AdbSession] to communicate with the local ADB server.
 * @param serial The serial number of the target device.
 * @param packageName The target application package name.
 * @param intervalMs The sampling interval in milliseconds.
 * @param durationSec The total duration of tracking in seconds.
 * @param includeAttributes If true, includes view attributes in the sampled dumps.
 * @param includeResolutionStack If true, includes attribute resolution stacks in the sampled dumps.
 * @param includeSystemComposables If true, includes system/framework composable nodes.
 * @param includeSemantics If true, includes accessibility semantics in the Compose sampled dumps.
 * @param composeInspectorJarPath Optional path to a local Compose Inspector JAR file.
 */
internal suspend fun doTrackChanges(
  adbSession: AdbSession,
  serial: String,
  packageName: String,
  intervalMs: Long,
  durationSec: Long,
  includeAttributes: Boolean,
  includeResolutionStack: Boolean,
  includeSystemComposables: Boolean,
  includeSemantics: Boolean,
  composeInspectorJarPath: String?,
  printer: UiDumpPrinter,
) {
  runWithConnectedInspectors(adbSession, serial, packageName, composeInspectorJarPath) { commandSender, composeInspectorConnected ->
    System.err.println("Sampling UI hierarchy for ${durationSec}s every ${intervalMs}ms...")

    val interval = intervalMs.milliseconds
    val duration = durationSec.seconds
    val timeSource = TimeSource.Monotonic
    val startTime = timeSource.markNow()
    val endTime = startTime + duration
    // Stores each collected UI tree sample mapped to the elapsed duration since the start of tracking.
    val samples = mutableListOf<TimedUiDump>()

    var sampleCount = 0
    while (timeSource.markNow() < endTime) {
      val sampleStart = timeSource.markNow()

      val uiDump =
        fetchUiDump(
          commandSender = commandSender,
          includeAttributes = includeAttributes,
          includeResolutionStack = includeResolutionStack,
          composeInspectorConnected = composeInspectorConnected,
          skipSystemComposables = !includeSystemComposables,
          includeSemantics = includeSemantics,
        )
      val elapsedSinceStart = startTime.elapsedNow()
      samples.add(TimedUiDump(elapsedSinceStart, uiDump))
      sampleCount++

      val elapsedForSample = sampleStart.elapsedNow()
      // Adjust sleep time to compensate for the round-trip overhead of fetching the UI tree.
      val remainingWait = interval - elapsedForSample
      if (remainingWait > Duration.ZERO) {
        delay(remainingWait)
      }
    }
    System.err.println("Sampling complete. Collected $sampleCount samples. Analyzing...")

    printer.printTrackedChanges(samples)
  }
}

/** Connects to the device, injects inspector agents, starts View and Compose inspectors, and runs [block] with the active connection. */
private suspend fun runWithConnectedInspectors(
  adbSession: AdbSession,
  serial: String,
  packageName: String,
  composeInspectorJarPath: String?,
  block: suspend (CommandSender, Boolean) -> Unit,
) = coroutineScope {
  try {
    val injectionManager = InjectionManager(adbSession, serial, packageName)
    val port = injectionManager.injectAndAttach()
    CommandSender.connect(host = "127.0.0.1", port = port.toInt(), scope = this).use { commandSender ->
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

      block(commandSender, composeInspectorConnected)
    }
  } catch (e: EmptyViewRootsException) {
    throw Exception(
      "No active window roots found for package '$packageName'. Please make sure the app is in the foreground and has visible layout views."
    )
  }
}

/** Dumps the View tree, enriches it with Compose if active, and prints the unified tree to console. */
internal suspend fun dumpUi(
  commandSender: CommandSender,
  includeAttributes: Boolean,
  includeResolutionStack: Boolean,
  composeInspectorConnected: Boolean,
  skipSystemComposables: Boolean,
  includeSemantics: Boolean,
  printer: UiDumpPrinter,
) {
  val uiDump =
    fetchUiDump(
      commandSender,
      includeAttributes,
      includeResolutionStack,
      composeInspectorConnected,
      skipSystemComposables,
      includeSemantics,
    )
  if (uiDump.roots.isEmpty()) {
    throw EmptyViewRootsException()
  }

  printer.printDump(uiDump)
}

internal suspend fun fetchUiDump(
  commandSender: CommandSender,
  includeAttributes: Boolean,
  includeResolutionStack: Boolean,
  composeInspectorConnected: Boolean,
  skipSystemComposables: Boolean,
  includeSemantics: Boolean,
): UiDump {
  val result = dumpViews(commandSender, includeAttributes, includeResolutionStack)
  if (composeInspectorConnected) {
    fetchAndMergeComposeTrees(commandSender, result.roots, includeAttributes, skipSystemComposables, includeSemantics)
  }
  return result
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
      val stringsMap = composeResult.stringsList.associate { it.id to it.str }
      val roots = composeResult.rootsList

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
