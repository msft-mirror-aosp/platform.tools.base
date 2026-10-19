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
import com.android.tools.ui.inspector.model.ParameterReference
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
private const val PACKAGE_OPTION_DESCRIPTION = "The app package name. Defaults to the app currently in the foreground"
private const val PRETTY_OPTION_DESCRIPTION = "Pretty-print the returned JSON"
private const val OUTPUT_OPTION_DESCRIPTION = "Writes the output to the specified file. If omitted, prints to standard output"
private const val COMPOSE_INSPECTOR_OPTION_DESCRIPTION = "Path to a local Compose Inspector JAR file to use instead of the one from maven"

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
@Command(
  name = "dump-ui",
  description =
    [
      "Dump UI hierarchy.",
      "Composable parameter and semantics values stop after 2 levels of nesting and 5 elements per collection. Such values have " +
        "\"isTruncated\": true and a \"continuationToken\" that get-parameter takes to fetch the rest.",
    ],
)
internal class DumpUiCommand(private val sessionFactory: () -> AdbSession) : Callable<Int> {
  @CommandLine.Spec lateinit var spec: CommandLine.Model.CommandSpec
  @Option(names = ["-h", "--help"], usageHelp = true, description = ["Show this help message and exit"]) private var helpRequested = false
  @Option(names = ["--device"], description = [DEVICE_OPTION_DESCRIPTION]) var device: String? = null
  @Option(names = ["--package"], description = [PACKAGE_OPTION_DESCRIPTION]) var packageName: String? = null
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
  @Option(names = ["--pretty", "-p"], description = [PRETTY_OPTION_DESCRIPTION]) var prettyPrint: Boolean = false
  @Option(names = ["-o", "--output"], description = [OUTPUT_OPTION_DESCRIPTION]) var output: Path? = null
  @Option(names = ["--compose-inspector"], description = [COMPOSE_INSPECTOR_OPTION_DESCRIPTION]) var composeInspectorJarPath: String? = null

  override fun call(): Int {
    val adbSession = sessionFactory()
    try {
      val (serial, targetPackage) = resolveTarget(adbSession, device, packageName)
      val err = spec.commandLine().err
      err.println("Executing dump-ui for package: $targetPackage on device: $serial")
      val inspector = createUiInspector(adbSession, composeInspectorJarPath, err)
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

/** The `get-parameter` command; [sessionFactory] opens the adb session a run uses. */
@Command(
  name = "get-parameter",
  description =
    [
      "Fetch a Compose parameter value that dump-ui or get-parameter left out. A value with \"isTruncated\": true has a " +
        "\"continuationToken\"; pass it to --continuation-token. Prints the value as JSON, in the format of dump-ui parameters.",
      "A collection is returned with all its elements from the token's position to the end, in one call. Its \"value\" " +
        "(e.g. \"List[100000]\") tells the size before you fetch it, except for \"Iterable\" and \"Sequence\": write large ones to " +
        "a file with --output.",
      "Values nested inside the result are not followed. Like in dump-ui, nesting stops 2 levels below the requested value and " +
        "nested collections show 5 elements; those values have \"isTruncated\" and their own \"continuationToken\".",
      "A collection too large for one call (e.g. an endless Sequence) is cut off, again with \"isTruncated\" and a " +
        "\"continuationToken\" to continue.",
      "Tokens are valid only while the UI is unchanged: after a change, run dump-ui again. A token whose value is gone, or now has " +
        "another name or \"value\" (e.g. a list with another size), fails with \"no longer available\" and exit code 1; one leading " +
        "to a value with the same name and \"value\" (e.g. a list of the same size with other elements, or a Sequence, whose " +
        "\"value\" has no size) returns that value.",
    ],
)
internal class GetParameterCommand(private val sessionFactory: () -> AdbSession) : Callable<Int> {
  @CommandLine.Spec lateinit var spec: CommandLine.Model.CommandSpec
  @Option(names = ["-h", "--help"], usageHelp = true, description = ["Show this help message and exit"]) private var helpRequested = false
  @Option(names = ["--device"], description = [DEVICE_OPTION_DESCRIPTION]) var device: String? = null
  @Option(names = ["--package"], description = [PACKAGE_OPTION_DESCRIPTION]) var packageName: String? = null
  @Option(
    names = ["--continuation-token"],
    required = true,
    description =
      ["The \"continuationToken\" of a value with \"isTruncated\": true in the output of dump-ui or get-parameter, passed unchanged"],
  )
  lateinit var continuationToken: String
  @Option(names = ["--pretty", "-p"], description = [PRETTY_OPTION_DESCRIPTION]) var prettyPrint: Boolean = false
  @Option(names = ["-o", "--output"], description = [OUTPUT_OPTION_DESCRIPTION]) var output: Path? = null
  @Option(names = ["--compose-inspector"], description = [COMPOSE_INSPECTOR_OPTION_DESCRIPTION]) var composeInspectorJarPath: String? = null

  override fun call(): Int {
    try {
      // Parsed before connecting to the device, so that a mistyped token fails fast.
      val parameterReference = ParameterReference.parse(continuationToken)
      val adbSession = sessionFactory()
      val (serial, targetPackage) = resolveTarget(adbSession, device, packageName)
      val err = spec.commandLine().err
      err.println("Executing get-parameter for package: $targetPackage on device: $serial")
      val inspector = createUiInspector(adbSession, composeInspectorJarPath, err)
      // The output is opened before the parameter is fetched, so an unwritable path fails before the agent is injected.
      withJsonPrinter(output, prettyPrint) { printer ->
        printer.printParameter(runBlocking { inspector.getParameter(serial, targetPackage, parameterReference) })
      }
      return EXIT_OK
    } catch (e: Exception) {
      spec.commandLine().err.println("Error: ${e.message}")
      return EXIT_ERROR
    }
  }
}

/** Returns the device serial and the package name a command targets: [device] and [packageName], or the defaults their options document. */
private fun resolveTarget(adbSession: AdbSession, device: String?, packageName: String?): Pair<String, String> = runBlocking {
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

/** Creates the [UiInspector] of a command, which logs to [err] and uses the Compose inspector at [composeInspectorJarPath] if set. */
private fun createUiInspector(adbSession: AdbSession, composeInspectorJarPath: String?, err: PrintWriter) =
  UiInspector(
    adbSession,
    cacheDir = AndroidLocationsSingleton.prefsLocation.resolve(COMPOSE_INSPECTOR_CACHE_PATH),
    composeInspectorJar = composeInspectorJarPath?.let(Paths::get),
    logger = stderrLogger(err),
  )

/**
 * Creates the fully configured command line used by [main], whose commands open their adb session with [sessionFactory]. Tests use it too,
 * so production command registration is what gets exercised.
 */
internal fun createCommandLine(sessionFactory: () -> AdbSession): CommandLine =
  CommandLine(UiInspectorCommand())
    .addSubcommand("dump-ui", DumpUiCommand(sessionFactory))
    .addSubcommand("get-parameter", GetParameterCommand(sessionFactory))

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
