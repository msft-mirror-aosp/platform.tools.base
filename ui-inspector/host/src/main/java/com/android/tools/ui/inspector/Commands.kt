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
import com.android.tools.ui.inspector.client.CommandSender
import com.android.tools.ui.inspector.client.InspectorCrashException
import com.android.tools.ui.inspector.client.createComposeInspector
import com.android.tools.ui.inspector.client.createViewInspector
import com.android.tools.ui.inspector.client.dumpViews
import com.android.tools.ui.inspector.client.queryComposeParameters
import com.android.tools.ui.inspector.client.queryComposeTree
import com.android.tools.ui.inspector.deploy.InjectionManager
import com.android.tools.ui.inspector.deploy.InjectionMode
import com.android.tools.ui.inspector.deploy.InjectionResult
import com.android.tools.ui.inspector.device.PackageUid
import com.android.tools.ui.inspector.device.TOP_ACTIVITY_SHELL_COMMAND
import com.android.tools.ui.inspector.device.UidResolver
import com.android.tools.ui.inspector.device.parseTopActivityProcesses
import com.android.tools.ui.inspector.model.UiDump
import com.android.tools.ui.inspector.model.UiNode
import com.android.tools.ui.inspector.model.UiWindow
import com.android.tools.ui.inspector.printer.UiDumpPrinter
import com.android.tools.ui.inspector.tree.attachComposeTree
import com.android.tools.ui.inspector.tree.stripSystemComposables
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol

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
 * Resolves the package name of the application to target. A [requested] package is returned unchanged; when omitted, the package of the app
 * currently hosting the top (foreground) activity is used.
 *
 * @throws IllegalStateException when no package is requested and the foreground app cannot be determined unambiguously.
 */
internal suspend fun resolveTargetPackage(adbSession: AdbSession, serial: String, requested: String?): String {
  // A requested package is used as-is: whether it exists, is running, and is debuggable is established downstream
  // during injection, which owns those checks for resolved packages as well.
  if (requested != null) return requested
  val selector = DeviceSelector.fromSerialNumber(serial)
  val uidResolver = UidResolver(adbSession, selector)
  val foregroundUids = queryForegroundUids(adbSession, selector, uidResolver)
  if (foregroundUids.isEmpty()) {
    // dumpsys reported no process hosting a top activity: nothing is in the foreground to resolve (locked or transitioning screen).
    throw foregroundAppResolutionException()
  }
  val packagesByUid = uidResolver.allPackageUids().groupBy { it.uid }
  // Distinct UIDs always resolve to distinct packages: several foreground UIDs means several apps are in the foreground (split screen).
  val packages = foregroundUids.map { uid -> singlePackageForUid(uid, packagesByUid) }
  return when {
    packages.size == 1 -> packages.single()
    else -> throw IllegalStateException("Multiple foreground apps found: ${packages.sorted().joinToString()}. Select one with --package.")
  }
}

/** Returns the UID of each app currently hosting a top (foreground) activity: one normally, several in split screen. */
private suspend fun queryForegroundUids(adbSession: AdbSession, selector: DeviceSelector, uidResolver: UidResolver): List<Int> {
  val output = adbSession.deviceServices.shellAsText(selector, TOP_ACTIVITY_SHELL_COMMAND).stdout
  return parseTopActivityProcesses(output)
    .map { it.pid }
    .distinct()
    .map { pid ->
      // A PID from the top-activity snapshot that no longer resolves to a live process means the foreground is mid-transition; resolving
      // from the remaining processes could pick the wrong app.
      uidResolver.processUid(pid) ?: throw foregroundAppResolutionException()
    }
    .distinct()
}

/**
 * Returns the single package that owns [uid], looked up in [packagesByUid]. Throws when no package owns the UID, or when several share it
 * (legacy sharedUserId): a UID alone cannot tell which of them is in the foreground.
 */
private fun singlePackageForUid(uid: Int, packagesByUid: Map<Int, List<PackageUid>>): String {
  val candidates = packagesByUid[uid].orEmpty().map { it.packageName }.distinct().sorted()
  return when {
    candidates.isEmpty() -> throw foregroundAppResolutionException()
    candidates.size > 1 ->
      throw IllegalStateException(
        "The foreground process UID matches multiple packages: ${candidates.joinToString()}. Select one with --package."
      )
    else -> candidates.single()
  }
}

private fun foregroundAppResolutionException() =
  IllegalStateException(
    "Could not determine the foreground app. Unlock the device and bring the target app to the foreground, or select the app with --package."
  )

/**
 * Ensures the UI Inspector agent is running in the target application (injecting it, or reconnecting to an already-running server of the
 * same version), and dumps the unified View and Compose tree structure to the console.
 *
 * @param adbSession The [AdbSession] to communicate with the local ADB server.
 * @param serial The serial number of the target device.
 * @param packageName The application package name to dump.
 * @param includeAttributes If true, includes view attributes in the dump output.
 * @param includeResolutionStack If true, includes attribute resolution stacks in the dump output.
 * @param includeSystemComposables If true, includes system/framework composable nodes.
 * @param includeSemantics If true, includes accessibility semantics in the Compose dump.
 * @param composeInspectorJarPath Optional path to a local Compose Inspector JAR file.
 * @param injectionManagerFactory Creates the [InjectionManager].
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
  injectionManagerFactory: (AdbSession, String, String, Path?) -> InjectionManager = { session, serial, pkg, overridePath ->
    InjectionManager(session, serial, pkg, overridePath)
  },
) {
  val composeInspectorOverrideJarPath = composeInspectorJarPath?.let(Paths::get)
  runWithConnectedInspectors(
    adbSession,
    serial,
    packageName,
    includeResolutionStack,
    composeInspectorOverrideJarPath,
    injectionManagerFactory,
  ) { commandSender, composeInspectorConnected ->
    dumpUi(
      commandSender = commandSender,
      includeAttributes = includeAttributes,
      includeResolutionStack = includeResolutionStack,
      composeInspectorConnected = composeInspectorConnected,
      includeSystemComposables = includeSystemComposables,
      includeSemantics = includeSemantics,
      printer = printer,
    )
  }
}

/**
 * Connects to the device, ensures the inspector agent is running in the target app (reusing an already-running server when possible),
 * starts the View and Compose inspectors, and runs [block] with the active connection.
 *
 * A reused server can prove stale — see [connectAndRunInspectors] — in which case a single retry performs a full injection. A failure of
 * the retry attempt carries the stale-server failure as a suppressed exception.
 */
internal suspend fun runWithConnectedInspectors(
  adbSession: AdbSession,
  serial: String,
  packageName: String,
  needsDebugViewAttributes: Boolean,
  composeInspectorOverrideJarPath: Path?,
  injectionManagerFactory: (AdbSession, String, String, Path?) -> InjectionManager,
  block: suspend (CommandSender, Boolean) -> Unit,
) {
  val injectionManager = injectionManagerFactory(adbSession, serial, packageName, composeInspectorOverrideJarPath)
  try {
    try {
      connectAndRunInspectors(
        injectionManager,
        InjectionMode.RECONNECT_IF_AVAILABLE,
        needsDebugViewAttributes,
        composeInspectorOverrideJarPath,
        block,
      )
    } catch (stale: StaleReconnectException) {
      System.err.println("The running UI Inspector server did not respond (${stale.cause?.message}); injecting a fresh agent.")
      try {
        connectAndRunInspectors(
          injectionManager,
          InjectionMode.FORCE_FULL_INJECTION,
          needsDebugViewAttributes,
          composeInspectorOverrideJarPath,
          block,
        )
      } catch (e: Throwable) {
        e.addSuppressed(stale)
        throw e
      }
    }
  } catch (e: EmptyViewRootsException) {
    val userFacing =
      Exception(
        "No active window roots found for package '$packageName'. Please make sure the app is in the foreground and has visible layout views."
      )
    // The empty-roots failure may have happened on the forced retry, whose suppressed stale-server diagnostic must survive the rewording.
    e.suppressed.forEach(userFacing::addSuppressed)
    throw userFacing
  }
}

/**
 * One connection attempt: obtains an agent server per [mode], connects to it, starts the View and Compose inspectors, and runs [block]. The
 * attempt owns its adb forward and removes it when done — forwards outlive the CLI process, so every attempt removes its own on success,
 * failure, and cancellation alike.
 *
 * When the server was reused ([InjectionResult.Reconnected]), its liveness is only established by the first command round trip: the probed
 * socket may have shut down since the probe (inactivity timeout), and adb creates the device-side leg of a forward lazily, so even a
 * successful TCP connect proves nothing. A transport failure ([IOException]) up to and including that first round trip is therefore
 * reported as [StaleReconnectException]. Anything after the first response, and any [InspectorCrashException] (an explicit agent-side
 * report, so the server is alive), propagates unchanged. A freshly injected server ([InjectionResult.Injected]) never reports staleness.
 */
private suspend fun connectAndRunInspectors(
  injectionManager: InjectionManager,
  mode: InjectionMode,
  needsDebugViewAttributes: Boolean,
  composeInspectorOverrideJarPath: Path?,
  block: suspend (CommandSender, Boolean) -> Unit,
) = coroutineScope {
  try {
    val injection = injectionManager.injectAndAttach(needsDebugViewAttributes, mode)
    val commandSender =
      try {
        CommandSender.connect(host = "127.0.0.1", port = injection.forwardedPort.toInt(), scope = this)
      } catch (e: IOException) {
        // A cancelled scope can surface as an IOException from the connection; cancellation must propagate, not trigger a retry.
        ensureActive()
        if (injection is InjectionResult.Reconnected) throw StaleReconnectException(e)
        throw e
      }
    commandSender.use {
      val viewInspectorDexPath = injectionManager.stageViewInspectorPayload()
      try {
        createViewInspector(commandSender, viewInspectorDexPath)
      } catch (e: InspectorCrashException) {
        throw e
      } catch (e: IOException) {
        // A cancelled scope can surface as an IOException from the connection; cancellation must propagate, not trigger a retry.
        ensureActive()
        if (injection is InjectionResult.Reconnected) throw StaleReconnectException(e)
        throw e
      }

      val localJarProvider = composeInspectorOverrideJarPath?.let { path -> { _: String -> path.toFile() } }

      val composeInspectorConnected =
        if (localJarProvider != null) {
          createComposeInspector(commandSender, injectionManager, localJarProvider)
        } else {
          createComposeInspector(commandSender, injectionManager)
        }

      block(commandSender, composeInspectorConnected)
    }
  } finally {
    withContext(NonCancellable) { injectionManager.removeAdbForward() }
  }
}

/** Dumps the View tree, enriches it with Compose if active, and prints the unified tree to console. */
internal suspend fun dumpUi(
  commandSender: CommandSender,
  includeAttributes: Boolean,
  includeResolutionStack: Boolean,
  composeInspectorConnected: Boolean,
  includeSystemComposables: Boolean,
  includeSemantics: Boolean,
  printer: UiDumpPrinter,
) {
  val uiDump = fetchUiDump(commandSender, includeAttributes, includeResolutionStack, composeInspectorConnected, includeSemantics)
  if (uiDump.windows.isEmpty()) {
    throw EmptyViewRootsException()
  }

  val outputDump = if (includeSystemComposables) uiDump else stripSystemComposables(uiDump)
  printer.printDump(outputDump)
}

internal suspend fun fetchUiDump(
  commandSender: CommandSender,
  includeAttributes: Boolean,
  includeResolutionStack: Boolean,
  composeInspectorConnected: Boolean,
  includeSemantics: Boolean,
): UiDump {
  val result = dumpViews(commandSender, includeAttributes, includeResolutionStack)
  if (composeInspectorConnected) {
    fetchAndMergeComposeTrees(commandSender, result.windows, includeAttributes, includeSemantics)
  }
  return result
}

/** Queries the Compose Layout Inspector on the device and merges its trees into [windows] in-place. */
private suspend fun fetchAndMergeComposeTrees(
  commandSender: CommandSender,
  windows: List<UiWindow>,
  includeParameters: Boolean,
  includeSemantics: Boolean,
) {
  windows.forEach { window ->
    val viewRoot = window.root
    // In the compose inspector, standard parameters and semantics (accessibility properties) are fetched together with a single command.
    // Each facet is still an independent demand, so the conversion below only copies the requested ones into the tree.
    val fetchComposeDetails = includeParameters || includeSemantics

    val composeResult =
      queryComposeTree(commandSender = commandSender, rootViewId = viewRoot.id, extractAllParameters = fetchComposeDetails)
    if (composeResult != null) {
      val stringsMap = composeResult.stringsList.associate { it.id to it.str }
      val roots = composeResult.rootsList

      val composeParameters =
        if (fetchComposeDetails) {
          // An explicit --include facet is a demand: if the details it needs cannot be fetched, fail loudly instead of
          // silently emitting a dump that is missing exactly what was asked for.
          queryComposeParameters(commandSender, viewRoot.id)
            ?: throw IllegalStateException("The requested attributes/semantics facets could not be fetched from the Compose inspector.")
        } else {
          null
        }

      mergeComposeRoots(
        viewRoot = viewRoot,
        composeRoots = roots,
        stringTable = stringsMap,
        parameters = composeParameters,
        includeParameters = includeParameters,
        includeSemantics = includeSemantics,
      )
    }
  }
}

/** Grafts each Compose root under [viewRoot], warning on stderr when a root's target view is not present in the tree. */
internal fun mergeComposeRoots(
  viewRoot: UiNode.ViewNode,
  composeRoots: List<LayoutInspectorComposeProtocol.ComposableRoot>,
  stringTable: Map<Int, String>,
  parameters: LayoutInspectorComposeProtocol.GetAllParametersResponse?,
  includeParameters: Boolean,
  includeSemantics: Boolean,
) {
  composeRoots.forEach { composeRoot ->
    val attached =
      attachComposeTree(
        viewNode = viewRoot,
        targetViewId = composeRoot.viewId,
        composeNodes = composeRoot.nodesList,
        stringTable = stringTable,
        viewsToSkip = composeRoot.viewsToSkipList,
        parameters = parameters,
        includeParameters = includeParameters,
        includeSemantics = includeSemantics,
      )
    if (!attached) {
      System.err.println(
        "Warning: could not attach a Compose tree (target view id ${composeRoot.viewId}) under view root ${viewRoot.id}; " +
          "the dump may be incomplete."
      )
    }
  }
}

private class EmptyViewRootsException : Exception()

/** A server reused via [InjectionResult.Reconnected] proved unreachable; [cause] is the transport failure that revealed it. */
private class StaleReconnectException(cause: IOException) : Exception(cause)
