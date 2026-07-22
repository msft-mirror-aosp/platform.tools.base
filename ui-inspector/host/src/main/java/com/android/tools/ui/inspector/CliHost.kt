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
import com.android.tools.ui.inspector.printer.json.JsonUiDumpPrinter
import java.util.concurrent.Callable
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option

private const val EXIT_OK = 0
private const val EXIT_ERROR = 1

/** Factory for creating [AdbSession]. Can be overridden in tests. */
var sessionFactory: () -> AdbSession = { createStandaloneSession(NO_LOGGING) }

@Command(name = "ui-inspector", mixinStandardHelpOptions = true, version = ["1.0"], description = ["UI Inspector CLI"])
class UiInspectorCommand : Callable<Int> {
  override fun call(): Int {
    CommandLine.usage(this, System.err)
    return EXIT_ERROR
  }
}

@Command(name = "list-devices", description = ["List serial numbers of connected devices"])
class ListDevicesCommand : Callable<Int> {
  override fun call(): Int {
    val adbSession = sessionFactory()
    try {
      runBlocking { doListDevices(adbSession) }
      return EXIT_OK
    } catch (e: Exception) {
      System.err.println("Error listing devices: ${e.message}")
      return EXIT_ERROR
    }
  }
}

@Command(name = "list-packages", description = ["List debuggable application package names on the device"])
class ListPackagesCommand : Callable<Int> {
  @Option(names = ["--device"], required = true, description = ["The device serial number"]) var device: String = ""

  override fun call(): Int {
    val adbSession = sessionFactory()
    try {
      runBlocking { doListPackages(adbSession, device) }
      return EXIT_OK
    } catch (e: Exception) {
      System.err.println("Error listing packages: ${e.message}")
      return EXIT_ERROR
    }
  }
}

/** Base class containing common command line options for subcommands that query layout trees. */
open class UiInspectorDumpCommand : Callable<Int> {
  @Option(names = ["--device"], required = true, description = ["The device serial number"]) var device: String = ""
  @Option(names = ["--package"], required = true, description = ["App package name"]) var packageName: String = ""
  @Option(names = ["--include-attributes"], description = ["Include view attributes in the dump"]) var includeAttributes: Boolean = false
  @Option(names = ["--include-resolution-stack"], description = ["Include attribute resolution stack in the dump"])
  var includeResolutionStack: Boolean = false
  @Option(names = ["--include-system-composables"], description = ["Include system/framework Composable nodes in the dump"])
  var includeSystemComposables: Boolean = false
  @Option(names = ["--include-semantics"], description = ["Include Compose accessibility/semantics properties in the dump"])
  var includeSemantics: Boolean = false
  @Option(names = ["--pretty", "-p"], description = ["Pretty-print the returned JSON"]) var prettyPrint: Boolean = false
  @Option(
    names = ["--compose-inspector"],
    description = ["Path to a local Compose Inspector JAR file to use instead of the one from maven"],
  )
  var composeInspectorJarPath: String? = null

  override fun call(): Int = EXIT_OK
}

@Command(name = "dump-ui", description = ["Dump UI hierarchy"])
class DumpUiCommand : UiInspectorDumpCommand() {
  override fun call(): Int {
    System.err.println("Executing dump-ui for package: $packageName on device: $device")
    val adbSession = sessionFactory()
    try {
      runBlocking {
        doDumpUi(
          adbSession = adbSession,
          serial = device,
          packageName = packageName,
          includeAttributes = includeAttributes,
          includeResolutionStack = includeResolutionStack,
          includeSystemComposables = includeSystemComposables,
          includeSemantics = includeSemantics,
          composeInspectorJarPath = composeInspectorJarPath,
          printer = JsonUiDumpPrinter(out = System.out, prettyPrint = prettyPrint),
        )
      }
      return EXIT_OK
    } catch (e: Exception) {
      System.err.println("Error: ${e.message}")
      return EXIT_ERROR
    }
  }
}

@Command(name = "track-changes", description = ["Track UI hierarchy changes over time by sampling"])
class TrackChangesCommand : UiInspectorDumpCommand() {
  @Option(names = ["--interval"], description = ["Sampling interval in milliseconds"], defaultValue = "100") var intervalMs: Long = 100
  @Option(names = ["--duration"], description = ["Sampling duration in seconds"], defaultValue = "5") var durationSec: Long = 5

  override fun call(): Int {
    System.err.println("Executing track-changes for package: $packageName on device: $device")
    val adbSession = sessionFactory()
    try {
      runBlocking {
        doTrackChanges(
          adbSession = adbSession,
          serial = device,
          packageName = packageName,
          intervalMs = intervalMs,
          durationSec = durationSec,
          includeAttributes = includeAttributes,
          includeResolutionStack = includeResolutionStack,
          includeSystemComposables = includeSystemComposables,
          includeSemantics = includeSemantics,
          composeInspectorJarPath = composeInspectorJarPath,
          printer = JsonUiDumpPrinter(out = System.out, prettyPrint = prettyPrint),
        )
      }
      return EXIT_OK
    } catch (e: Exception) {
      System.err.println("Error: ${e.message}")
      return EXIT_ERROR
    }
  }
}

fun main(args: Array<String>) {
  val exitCode =
    CommandLine(UiInspectorCommand())
      .addSubcommand("dump-ui", DumpUiCommand())
      .addSubcommand("track-changes", TrackChangesCommand())
      .addSubcommand("list-devices", ListDevicesCommand())
      .addSubcommand("list-packages", ListPackagesCommand())
      .execute(*args)
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
