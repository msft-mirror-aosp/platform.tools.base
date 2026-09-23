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
import com.android.prefs.AndroidLocationsSingleton
import com.android.tools.ui.inspector.printer.json.withJsonPrinter
import java.io.PrintWriter
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.Callable
import kotlin.system.exitProcess
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option

private const val EXIT_OK = 0
private const val EXIT_ERROR = 1

private const val DEVICE_OPTION_DESCRIPTION =
  "The device serial number. Defaults to the only online device; required when multiple online devices are connected"

/** Where the CLI keeps the Compose inspector jars it downloads, on the host cache. */
private const val COMPOSE_INSPECTOR_CACHE_PATH = "ui-inspector/cache"

@Command(name = "ui-inspector", mixinStandardHelpOptions = true, version = ["1.0"], description = ["UI Inspector CLI"])
internal class UiInspectorCommand : Callable<Int> {
  @CommandLine.Spec lateinit var spec: CommandLine.Model.CommandSpec

  override fun call(): Int {
    spec.commandLine().usage(spec.commandLine().err)
    return EXIT_ERROR
  }
}

/** The `dump-ui` command; [sessionFactory] opens the adb session a run uses. */
@Command(name = "dump-ui", description = ["Dump UI hierarchy"])
internal class DumpUiCommand(private val sessionFactory: () -> AdbSession) : Callable<Int> {
  @CommandLine.Spec lateinit var spec: CommandLine.Model.CommandSpec
  @Option(names = ["-h", "--help"], usageHelp = true, description = ["Show this help message and exit"]) private var helpRequested = false
  @Option(names = ["--device"], description = [DEVICE_OPTION_DESCRIPTION]) var device: String? = null
  @Option(names = ["--package"], description = ["The app package name. Defaults to the app currently in the foreground"])
  var packageName: String? = null
  @Option(
    names = ["--include"],
    split = ",",
    converter = [IncludeFacetConverter::class],
    description =
      [
        "Data to include in the dump: attributes, semantics, resolution-stack, system-composables, or all. " +
          "Repeatable or comma-separated; resolution-stack implies attributes and, on first use for an app, " +
          "restarts its activities (enables a persistent device setting)"
      ],
  )
  internal var include: List<IncludeFacet> = emptyList()
  @Option(names = ["--pretty", "-p"], description = ["Pretty-print the returned JSON"]) var prettyPrint: Boolean = false
  @Option(names = ["-o", "--output"], description = ["Writes the output to the specified file. If omitted, prints to standard output"])
  var output: Path? = null
  @Option(
    names = ["--compose-inspector"],
    description = ["Path to a local Compose Inspector JAR file to use instead of the one from maven"],
  )
  var composeInspectorJarPath: String? = null

  override fun call(): Int {
    val adbSession = sessionFactory()
    try {
      val (serial, targetPackage) =
        runBlocking {
          val serial =
            device
              ?: runCatching { resolveSoleOnlineDevice(adbSession) }
                .getOrElse { throw IllegalStateException("${it.message} Specify a device with --device.", it) }
          val targetPackage =
            packageName
              ?: runCatching { resolveForegroundPackage(adbSession, serial) }
                .getOrElse { throw IllegalStateException("${it.message} Specify a package with --package.", it) }
          serial to targetPackage
        }
      val err = spec.commandLine().err
      err.println("Executing dump-ui for package: $targetPackage on device: $serial")
      val inspector =
        UiInspector(
          adbSession,
          cacheDir = AndroidLocationsSingleton.prefsLocation.resolve(COMPOSE_INSPECTOR_CACHE_PATH),
          composeInspectorJar = composeInspectorJarPath?.let(Paths::get),
          logger = stderrLogger(err),
        )
      // The output is opened before the dump runs, so an unwritable path fails before the agent is injected.
      withJsonPrinter(output, prettyPrint) { printer ->
        printer.printDump(runBlocking { inspector.dump(serial, targetPackage, requestedFacets(include)) })
      }
      return EXIT_OK
    } catch (e: Exception) {
      spec.commandLine().err.println("Error: ${e.message}")
      return EXIT_ERROR
    }
  }
}

/**
 * Creates the fully configured command line used by [main], whose commands open their adb session with [sessionFactory]. Tests use it too,
 * so production command registration is what gets exercised.
 */
internal fun createCommandLine(sessionFactory: () -> AdbSession): CommandLine =
  CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand(sessionFactory))

fun main(args: Array<String>) {
  exitProcess(createCommandLine { createStandaloneSession(NO_LOGGING) }.execute(*args))
}

/** The CLI shows every log message on [err], warnings marked as such. */
internal fun stderrLogger(err: PrintWriter) = Logger { level, message ->
  err.println(if (level == LogLevel.WARNING) "Warning: $message" else message)
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
