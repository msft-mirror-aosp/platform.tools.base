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
import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
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
          createViewInspector(commandSender, injectionManager)
          viewInspectorHelloWorld(commandSender)
        }
      }
      return EXIT_OK
    } catch (e: Exception) {
      System.err.println("Error: ${e.message}")
      return EXIT_ERROR
    }
  }
}

/** Sends a command to the agent to load and create the view inspector dynamically. */
private suspend fun createViewInspector(commandSender: CommandSender, injectionManager: InjectionManager) {
  val inspectorMetadata = InspectorRegistry.VIEW_INSPECTOR

  // Push payload jar on demand and get the remote path
  val dexPath = injectionManager.pushInspectorPayload(inspectorMetadata)
  val createCommand =
    UiInspectorProtocol.Command.newBuilder()
      .setCreateInspector(
        UiInspectorProtocol.CreateInspectorCommand.newBuilder().setInspectorId(inspectorMetadata.id).setDexPath(dexPath).build()
      )
      .build()

  val createResponse = commandSender.sendMessage(createCommand)
  if (createResponse.status != UiInspectorProtocol.Response.Status.SUCCESS) {
    throw IllegalStateException("Failed to create inspector: ${createResponse.errorMessage}")
  }
}

/** Sends a hello command to the view inspector and prints the response. */
private suspend fun viewInspectorHelloWorld(commandSender: CommandSender) {
  val viewInspectorCommand =
    ViewInspectorProtocol.Command.newBuilder().setHelloCommand(ViewInspectorProtocol.HelloCommand.getDefaultInstance()).build()

  val responsePayload = commandSender.sendInspectorCommand(ProtocolConstants.VIEW_INSPECTOR_ID, viewInspectorCommand.toByteArray())
  val viewInspectorResponse = ViewInspectorProtocol.Response.parseFrom(responsePayload)

  System.out.println("Sent: hello command")
  System.out.println("Received: ${viewInspectorResponse.helloResponse}")
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
