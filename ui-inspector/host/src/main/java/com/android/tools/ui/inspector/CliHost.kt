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
import com.android.tools.ui.inspector.printer.json.withJsonPrinter
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import picocli.CommandLine
import picocli.CommandLine.ArgGroup
import picocli.CommandLine.Command
import picocli.CommandLine.Model.CommandSpec
import picocli.CommandLine.Option
import picocli.CommandLine.ParameterException
import picocli.CommandLine.Spec

private const val EXIT_OK = 0
private const val EXIT_ERROR = 1

private const val DEVICE_OPTION_DESCRIPTION =
  "The device serial number. Defaults to the only online device; required when multiple online devices are connected"

private val DEFAULT_RECORD_INTERVAL = 100.milliseconds

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
  @Option(names = ["--device"], description = [DEVICE_OPTION_DESCRIPTION]) var device: String? = null

  override fun call(): Int {
    val adbSession = sessionFactory()
    try {
      runBlocking { doListPackages(adbSession, resolveDeviceSerial(adbSession, device)) }
      return EXIT_OK
    } catch (e: Exception) {
      System.err.println("Error listing packages: ${e.message}")
      return EXIT_ERROR
    }
  }
}

@Command(name = "dump-ui", description = ["Dump UI hierarchy"])
class DumpUiCommand : Callable<Int> {
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
  @ArgGroup(exclusive = false, heading = "Record a bounded window:%n") var recordOptions: RecordOptions? = null

  /** Options for recording UI changes over a bounded window. Grouped so picocli enforces and documents them as a unit. */
  class RecordOptions {
    @Option(
      names = ["--record"],
      required = true,
      description = ["Sample the UI over a bounded duration and report the observed changes, e.g. to inspect an animation"],
    )
    var record: Boolean = false
    @Option(
      names = ["--duration"],
      required = true,
      converter = [DurationConverter::class],
      description = ["Total sampling duration, e.g. 5s or 1m"],
    )
    var duration: Duration? = null
    @Option(
      names = ["--interval"],
      converter = [DurationConverter::class],
      description = ["Sampling interval, e.g. 100ms or 1s. Defaults to 100ms"],
    )
    var interval: Duration? = null
  }

  @Spec lateinit var spec: CommandSpec

  override fun call(): Int {
    // The group guarantees --duration when any recording option is present, but a boolean option still accepts an
    // explicit "=false", which would contradict the other recording options rather than disable them.
    recordOptions?.let {
      if (!it.record) {
        throw ParameterException(spec.commandLine(), "--record=false cannot be combined with recording options")
      }
    }
    val adbSession = sessionFactory()
    try {
      val (serial, targetPackage) =
        runBlocking {
          val serial = resolveDeviceSerial(adbSession, device)
          serial to resolveTargetPackage(adbSession, serial, packageName)
        }
      System.err.println("Executing dump-ui for package: $targetPackage on device: $serial")
      val facets = expandIncludeFacets(include)
      withJsonPrinter(output, prettyPrint) { printer ->
        runBlocking {
          val recordOptions = recordOptions
          if (recordOptions != null) {
            doTrackChanges(
              adbSession = adbSession,
              serial = serial,
              packageName = targetPackage,
              interval = recordOptions.interval ?: DEFAULT_RECORD_INTERVAL,
              duration = requireNotNull(recordOptions.duration),
              includeAttributes = IncludeFacet.ATTRIBUTES in facets,
              includeResolutionStack = IncludeFacet.RESOLUTION_STACK in facets,
              includeSystemComposables = IncludeFacet.SYSTEM_COMPOSABLES in facets,
              includeSemantics = IncludeFacet.SEMANTICS in facets,
              composeInspectorJarPath = composeInspectorJarPath,
              printer = printer,
            )
          } else {
            doDumpUi(
              adbSession = adbSession,
              serial = serial,
              packageName = targetPackage,
              includeAttributes = IncludeFacet.ATTRIBUTES in facets,
              includeResolutionStack = IncludeFacet.RESOLUTION_STACK in facets,
              includeSystemComposables = IncludeFacet.SYSTEM_COMPOSABLES in facets,
              includeSemantics = IncludeFacet.SEMANTICS in facets,
              composeInspectorJarPath = composeInspectorJarPath,
              printer = printer,
            )
          }
        }
      }
      return EXIT_OK
    } catch (e: Exception) {
      System.err.println("Error: ${e.message}")
      return EXIT_ERROR
    }
  }
}

/**
 * Creates the fully configured command line used by [main]. Tests use it too, so production command registration is what gets exercised.
 */
internal fun createCommandLine(): CommandLine =
  CommandLine(UiInspectorCommand())
    .addSubcommand("dump-ui", DumpUiCommand())
    .addSubcommand("list-devices", ListDevicesCommand())
    .addSubcommand("list-packages", ListPackagesCommand())

fun main(args: Array<String>) {
  exitProcess(createCommandLine().execute(*args))
}

/** Converts humane duration values such as `100ms`, `5s`, or `1m` for picocli options. */
private class DurationConverter : CommandLine.ITypeConverter<Duration> {
  override fun convert(value: String): Duration {
    val duration =
      try {
        Duration.parse(value)
      } catch (_: IllegalArgumentException) {
        throw CommandLine.TypeConversionException(invalidDurationMessage(value))
      }
    if (!duration.isPositive() || duration.isInfinite()) {
      throw CommandLine.TypeConversionException(invalidDurationMessage(value))
    }
    return duration
  }

  private fun invalidDurationMessage(value: String) = "Invalid duration: '$value'. Expected a positive duration such as 100ms, 5s, or 1m."
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
