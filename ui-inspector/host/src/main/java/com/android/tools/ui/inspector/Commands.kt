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

import com.android.adblib.AdbSession
import com.android.tools.ui.inspector.client.CommandSender
import com.android.tools.ui.inspector.client.InspectorCrashException
import com.android.tools.ui.inspector.client.createComposeInspector
import com.android.tools.ui.inspector.client.createViewInspector
import com.android.tools.ui.inspector.client.dumpViews
import com.android.tools.ui.inspector.client.mavenComposeInspectorResolver
import com.android.tools.ui.inspector.client.queryComposeParameters
import com.android.tools.ui.inspector.client.queryComposeTree
import com.android.tools.ui.inspector.deploy.InjectionManager
import com.android.tools.ui.inspector.deploy.InjectionMode
import com.android.tools.ui.inspector.deploy.InjectionResult
import com.android.tools.ui.inspector.model.UiDump
import com.android.tools.ui.inspector.model.UiNode
import com.android.tools.ui.inspector.model.UiWindow
import com.android.tools.ui.inspector.tree.attachComposeTree
import com.android.tools.ui.inspector.tree.stripSystemComposables
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol

/** Creates the [InjectionManager] for a dump: session, serial, package, Compose inspector override jar, logger. */
internal typealias InjectionManagerFactory = (AdbSession, String, String, Path?, Logger) -> InjectionManager

/**
 * Connects to the device, ensures the inspector agent is running in the target app (reusing an already-running server when possible),
 * starts the View and Compose inspectors, and returns what [block] produces with the active connection.
 *
 * A reused server can prove stale — see [connectAndRunInspectors] — in which case a single retry performs a full injection. A failure of
 * the retry attempt carries the stale-server failure as a suppressed exception.
 */
internal suspend fun <T> runWithConnectedInspectors(
  adbSession: AdbSession,
  serial: String,
  packageName: String,
  needsDebugViewAttributes: Boolean,
  composeInspectorOverrideJarPath: Path?,
  composeInspectorCacheDir: Path,
  logger: Logger,
  injectionManagerFactory: InjectionManagerFactory,
  block: suspend (CommandSender, Boolean) -> T,
): T {
  val injectionManager = injectionManagerFactory(adbSession, serial, packageName, composeInspectorOverrideJarPath, logger)
  val resolveComposeInspectorJar =
    composeInspectorOverrideJarPath?.let { path -> { _: String -> path.toFile() } }
      ?: mavenComposeInspectorResolver(composeInspectorCacheDir)
  return try {
    try {
      connectAndRunInspectors(
        injectionManager,
        InjectionMode.RECONNECT_IF_AVAILABLE,
        needsDebugViewAttributes,
        resolveComposeInspectorJar,
        logger,
        block,
      )
    } catch (stale: StaleReconnectException) {
      logger.log(LogLevel.PROGRESS, "The running UI Inspector server did not respond (${stale.cause?.message}); injecting a fresh agent.")
      try {
        connectAndRunInspectors(
          injectionManager,
          InjectionMode.FORCE_FULL_INJECTION,
          needsDebugViewAttributes,
          resolveComposeInspectorJar,
          logger,
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
private suspend fun <T> connectAndRunInspectors(
  injectionManager: InjectionManager,
  mode: InjectionMode,
  needsDebugViewAttributes: Boolean,
  resolveComposeInspectorJar: (version: String) -> File,
  logger: Logger,
  block: suspend (CommandSender, Boolean) -> T,
): T = coroutineScope {
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

      val composeInspectorConnected = createComposeInspector(commandSender, injectionManager, logger, resolveComposeInspectorJar)

      block(commandSender, composeInspectorConnected)
    }
  } finally {
    withContext(NonCancellable) { injectionManager.removeAdbForward() }
  }
}

/** Dumps the View tree, enriches it with Compose if active, and returns the unified tree. */
internal suspend fun dumpUi(
  commandSender: CommandSender,
  includeAttributes: Boolean,
  includeResolutionStack: Boolean,
  composeInspectorConnected: Boolean,
  includeSystemComposables: Boolean,
  includeSemantics: Boolean,
  logger: Logger,
): UiDump {
  val uiDump = fetchUiDump(commandSender, includeAttributes, includeResolutionStack, composeInspectorConnected, includeSemantics, logger)
  if (uiDump.windows.isEmpty()) {
    throw EmptyViewRootsException()
  }

  return if (includeSystemComposables) uiDump else stripSystemComposables(uiDump)
}

internal suspend fun fetchUiDump(
  commandSender: CommandSender,
  includeAttributes: Boolean,
  includeResolutionStack: Boolean,
  composeInspectorConnected: Boolean,
  includeSemantics: Boolean,
  logger: Logger,
): UiDump {
  val result = dumpViews(commandSender, includeAttributes, includeResolutionStack)
  if (!composeInspectorConnected) return result
  val windows = fetchAndMergeComposeTrees(commandSender, result.windows, includeAttributes, includeSemantics, logger)
  return result.copy(windows = windows)
}

/** Queries the Compose Layout Inspector on the device and returns [windows] with its trees merged into their roots. */
private suspend fun fetchAndMergeComposeTrees(
  commandSender: CommandSender,
  windows: List<UiWindow>,
  includeParameters: Boolean,
  includeSemantics: Boolean,
  logger: Logger,
): List<UiWindow> = windows.map { window ->
  val viewRoot = window.root
  // In the compose inspector, standard parameters and semantics (accessibility properties) are fetched together with a single command.
  // Each facet is still an independent demand, so the conversion below only copies the requested ones into the tree.
  val fetchComposeDetails = includeParameters || includeSemantics

  val composeResult = queryComposeTree(commandSender = commandSender, rootViewId = viewRoot.id, extractAllParameters = fetchComposeDetails)
  val stringsMap = composeResult.stringsList.associate { it.id to it.str }
  val roots = composeResult.rootsList

  val composeParameters = if (fetchComposeDetails) queryComposeParameters(commandSender, viewRoot.id) else null

  val mergedRoot =
    mergeComposeRoots(
      viewRoot = viewRoot,
      composeRoots = roots,
      stringTable = stringsMap,
      parameters = composeParameters,
      includeParameters = includeParameters,
      includeSemantics = includeSemantics,
      logger = logger,
    )
  window.copy(root = mergedRoot)
}

/**
 * Returns [viewRoot] with each Compose root grafted under its target view, warning through [logger] for a root whose target view is not
 * present in the tree.
 */
internal fun mergeComposeRoots(
  viewRoot: UiNode.ViewNode,
  composeRoots: List<LayoutInspectorComposeProtocol.ComposableRoot>,
  stringTable: Map<Int, String>,
  parameters: LayoutInspectorComposeProtocol.GetAllParametersResponse?,
  includeParameters: Boolean,
  includeSemantics: Boolean,
  logger: Logger,
): UiNode.ViewNode =
  composeRoots.fold(viewRoot) { root, composeRoot ->
    attachComposeTree(
      root = root,
      targetViewId = composeRoot.viewId,
      composeNodes = composeRoot.nodesList,
      stringTable = stringTable,
      viewsToSkip = composeRoot.viewsToSkipList,
      parameters = parameters,
      includeParameters = includeParameters,
      includeSemantics = includeSemantics,
    )
      ?: root.also {
        logger.log(
          LogLevel.WARNING,
          "could not attach a Compose tree (target view id ${composeRoot.viewId}) under view root ${viewRoot.id}; the dump may be incomplete.",
        )
      }
  }

private class EmptyViewRootsException : Exception()

/** A server reused via [InjectionResult.Reconnected] proved unreachable; [cause] is the transport failure that revealed it. */
private class StaleReconnectException(cause: IOException) : Exception(cause)
