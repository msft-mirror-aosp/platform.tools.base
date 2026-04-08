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

import java.util.concurrent.Callable
import kotlin.system.exitProcess
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

  override fun call(): Int {
    System.err.println("Executing dump-ui for package: $packageName on device: $serial")
    return EXIT_OK
  }
}

fun main(args: Array<String>) {
  val exitCode = CommandLine(UiInspectorCommand()).addSubcommand("dump-ui", DumpUiCommand()).execute(*args)
  exitProcess(exitCode)
}
