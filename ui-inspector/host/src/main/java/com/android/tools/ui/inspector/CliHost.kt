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

import com.android.adblib.AdbLogger
import com.android.adblib.AdbLoggerFactory
import com.android.adblib.AdbSession
import com.android.adblib.tools.createStandaloneSession
import java.util.concurrent.Callable
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option

private const val EXIT_OK = 0
private const val EXIT_ERROR = 1

@Command(name = "ui-inspector", mixinStandardHelpOptions = true, version = ["1.0"], description = ["UI Inspector CLI"])
class UiInspectorCommand : Callable<Int> {
  override fun call(): Int {
    CommandLine.usage(this, System.err)
    return EXIT_ERROR
  }
}

@Command(name = "dump-ui", description = ["Dump UI hierarchy"])
class DumpUiCommand : Callable<Int> {

  @Option(names = ["--serial"], required = true, description = ["Device serial number"]) var serial: String = ""
  @Option(names = ["--package"], required = true, description = ["App package name"]) var packageName: String = ""
  @Option(names = ["--include-attributes"], description = ["Include view attributes in the dump"]) var includeAttributes: Boolean = false
  @Option(names = ["--include-resolution-stack"], description = ["Include attribute resolution stack in the dump"])
  var includeResolutionStack: Boolean = false
  @Option(names = ["--include-system-composables"], description = ["Include system/framework Composable nodes in the dump"])
  var includeSystemComposables: Boolean = false

  companion object {
    /** Factory for creating [AdbSession]. Can be overridden in tests. */
    var sessionFactory: () -> AdbSession = { createStandaloneSession(NO_LOGGING) }
  }

  override fun call(): Int {
    System.err.println("Executing dump-ui for package: $packageName on device: $serial")

    val adbSession = sessionFactory()

    try {
      runBlocking {
        val injectionManager = InjectionManager(adbSession, serial, packageName)
        val port = injectionManager.injectAndAttach()

        CommandSender(host = "localhost", port = port.toInt()).use { commandSender ->
          // TODO: consider running in parallel
          createViewInspector(commandSender, injectionManager)
          val composeInspectorConnected = createComposeInspector(commandSender, injectionManager)

          dumpUiTree(
            commandSender = commandSender,
            includeAttributes = includeAttributes,
            includeResolutionStack = includeResolutionStack,
            composeInspectorConnected = composeInspectorConnected,
            skipSystemComposables = !includeSystemComposables,
          )
        }
      }
      return EXIT_OK
    } catch (e: Exception) {
      System.err.println("Error: ${e.message}")
      return EXIT_ERROR
    }
  }
}

/** Dumps the View tree, enriches it with Compose if active, and prints the unified tree to console. */
internal suspend fun dumpUiTree(
  commandSender: CommandSender,
  includeAttributes: Boolean,
  includeResolutionStack: Boolean,
  composeInspectorConnected: Boolean,
  skipSystemComposables: Boolean,
) {
  val viewRoots = fetchViewTree(commandSender, includeAttributes, includeResolutionStack)
  if (composeInspectorConnected) {
    fetchAndMergeComposeTrees(commandSender, viewRoots, includeAttributes, skipSystemComposables)
  }
  viewRoots.forEach { printUiTree(it, 0) }
}

/** Queries the Compose Layout Inspector on the device and merges its trees into [viewRoots] in-place. */
internal suspend fun fetchAndMergeComposeTrees(
  commandSender: CommandSender,
  viewRoots: List<UiNode.ViewNode>,
  includeParameters: Boolean,
  skipSystemComposables: Boolean,
) {
  viewRoots.forEach { viewRoot ->
    val composeResult = queryComposeTree(commandSender, viewRoot.id, includeParameters, skipSystemComposables)
    if (composeResult != null) {
      val (roots, stringsMap) = composeResult

      val composeParameters =
        if (includeParameters) {
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

fun main(args: Array<String>) {
  val exitCode = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand()).execute(*args)
  exitProcess(exitCode)
}

/** A logger factory that silences all adblib logs to keep the CLI output clean. */
private val NO_LOGGING =
  object : AdbLoggerFactory {
    override val logger =
      object : AdbLogger() {
        override val minLevel = Level.ERROR

        override fun log(level: Level, message: String) = Unit

        override fun log(level: Level, exception: Throwable?, message: String) = Unit
      }

    override fun createLogger(cls: Class<*>) = logger

    override fun createLogger(category: String) = logger
  }
