/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.fakeadbserver.devicecommandhandlers

import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.FakeAdbServer
import com.android.fakeadbserver.ShellProtocolType
import com.android.fakeadbserver.ShellV2Protocol
import com.android.fakeadbserver.services.NoOperationStatusWriter
import com.android.fakeadbserver.services.ShellCommandOutput
import com.android.fakeadbserver.services.ShellCommandOutputWithCachedExitCode
import com.android.fakeadbserver.services.StatusWriter
import com.android.fakeadbserver.shellcommandhandlers.ExitCommandHandler
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.CoroutineScope

private val ADB_CHARSET: Charset = StandardCharsets.UTF_8

/**
 * [InteractiveShellHandler] handles interactive terminal sessions for [ShellProtocolType.SHELL]. See [InteractiveShellV2Handler] for
 * additional information.
 *
 * The implementation supports all device commands [DeviceCommandHandler] where the command has all the needed arguments to execute before
 * the next newline (`\n`) or carriage return (`\r`).
 *
 * Note that the implementation ignore additional [ShellOptions], e.g. always separates stdout and stderr.
 *
 * Supported Example (All arguments before newline):
 * ```
 *     coroutineScope {
 *         val input = session.channelFactory.createPipedChannel()
 *         launch {
 *             input.pipeSource.writeText("cat file.txt\n")
 *             input.pipeSource.writeText("exit\r")
 *         }
 *        shellTerminal(deviceSelector, TextShellCollector(), ShellOptions(), stdinChannel = input)
 *          .collect{println(it)}
 *     }
 * ```
 *
 * Unsupported Example (Arguments after newline):
 * ```
 *     coroutineScope {
 *         val input = session.channelFactory.createPipedChannel()
 *         launch {
 *             input.pipeSource.writeText("cat\n")
 *             input.pipeSource.writeText("one\n")
 *             input.pipeSource.writeText("two\n")
 *             input.pipeSource.writeText("three\n")
 *             input.pipeSource.writeText("exit\r")
 *         }
 *         // This scenario is not supported as the arguments are provided after the initial command.
 *      }
 *
 * ```
 *
 * @throws IllegalStateException when can't find command handler registered in [FakeAdbServer].
 * @see [AdbDeviceServices.shellTerminal]
 */
class InteractiveShellHandler : DeviceCommandHandler("") {

  override fun accept(
    server: FakeAdbServer,
    socketScope: CoroutineScope,
    socket: Socket,
    device: DeviceState,
    command: String,
    args: String,
    statusWriter: StatusWriter,
    shellCommandOutputProvider: (() -> ShellCommandOutput)?,
  ): Boolean {
    if (args.isNotEmpty() || !isShellV1Command(command)) {
      return false
    }
    execute(server, socketScope, socket, device, statusWriter)
    return true
  }

  fun execute(server: FakeAdbServer, socketScope: CoroutineScope, socket: Socket, device: DeviceState, statusWriter: StatusWriter) {
    statusWriter.writeOk()

    val shellCommandOutput = ShellCommandOutputWithCachedExitCode(ShellProtocolType.SHELL.createServiceOutput(socket, device))

    while (true) {
      val bytes = socket.inputStream.readAllBytes()
      if (bytes.isEmpty()) {
        // shell v1 has no exit code
        break
      }
      val commands = bytes.toCommands()
      val result = executeCommands(commands, shellCommandOutput, ShellProtocolType.SHELL, server, socketScope, socket, device)
      if (result == ExecuteCommandsResult.Exit) {
        break
      }
    }
  }
}

/**
 * [InteractiveShellV2Handler] handle interactive terminal sessions for [ShellProtocolType.SHELL_V2]. See [InteractiveShellHandler] for
 * additional information.
 *
 * The implementation supports all device commands [DeviceCommandHandler] where the command has all the needed arguments to execute before
 * the next newline (`\n`) or carriage return (`\r`).
 *
 * Window size changes are captured and forwarded to stdout in the format: `"%dx%d,%dx%d"` (rowCount, columnCount, xPixelCount,
 * yPixelCount).
 *
 * Note that the implementation ignore additional [ShellOptions], e.g. always separates stdout and stderr.
 *
 * Supported Example (All arguments before newline):
 * ```
 *     coroutineScope {
 *         val input = session.channelFactory.createPipedChannel()
 *         launch {
 *             input.pipeSource.writeText("cat file.txt\n")
 *             input.pipeSource.writeText("exit\r")
 *         }
 *         val stdout = shellTerminalSession(device)
 *             .withCollector(TextShellV2Collector())
 *             .withStdin(input)
 *             .execute()
 *             .first()
 *             .stdout
 *     }
 * ```
 *
 * Unsupported Example (Arguments after newline):
 * ```
 *     coroutineScope {
 *         val input = session.channelFactory.createPipedChannel()
 *         launch {
 *             input.pipeSource.writeText("cat\n")
 *             input.pipeSource.writeText("one\n")
 *             input.pipeSource.writeText("two\n")
 *             input.pipeSource.writeText("three\n")
 *             input.pipeSource.writeText("exit\r")
 *         }
 *         // This scenario is not supported as the arguments are provided after the initial command.
 *      }
 *
 * ```
 *
 * @throws IllegalStateException when can't find command handler registered in [FakeAdbServer].
 * @see [AdbDeviceServices.shellTerminalSession]
 * @see [AdbDeviceServices.shellV2Terminal]
 */
class InteractiveShellV2Handler : DeviceCommandHandler("") {

  override fun accept(
    server: FakeAdbServer,
    socketScope: CoroutineScope,
    socket: Socket,
    device: DeviceState,
    command: String,
    args: String,
    statusWriter: StatusWriter,
    shellCommandOutputProvider: (() -> ShellCommandOutput)?,
  ): Boolean {
    if (args.isNotEmpty() || !isShellV2Command(command)) {
      return false
    }
    execute(server, socketScope, socket, device, statusWriter)
    return true
  }

  fun execute(server: FakeAdbServer, socketScope: CoroutineScope, socket: Socket, device: DeviceState, statusWriter: StatusWriter) {
    statusWriter.writeOk()

    val protocol = ShellV2Protocol(socket)
    val shellCommandOutput = ShellCommandOutputWithCachedExitCode(ShellProtocolType.SHELL_V2.createServiceOutput(socket, device))
    while (true) {
      // (Optionally) handle leftover bytes from the previous `STDIN` packet first
      val stdinAvailable = shellCommandOutput.availableStdinByteCount()
      if (stdinAvailable > 0) {
        val stdinBytes = ByteArray(stdinAvailable)
        shellCommandOutput.readStdin(stdinBytes, 0, stdinAvailable)
        val commands = stdinBytes.toCommands()
        val result = executeCommands(commands, shellCommandOutput, ShellProtocolType.SHELL_V2, server, socketScope, socket, device)
        if (result == ExecuteCommandsResult.Exit) {
          break
        }
      }

      // Handle next V2 protocol packet
      val packet = protocol.readPacket()
      when (packet.kind) {
        ShellV2Protocol.PacketKind.STDIN -> {
          val commands = packet.bytes.toCommands()
          val result = executeCommands(commands, shellCommandOutput, ShellProtocolType.SHELL_V2, server, socketScope, socket, device)
          if (result == ExecuteCommandsResult.Exit) {
            break
          }
        }

        ShellV2Protocol.PacketKind.CLOSE_STDIN -> {
          protocol.writeExitCode(shellCommandOutput.exitCode)
          break
        }

        ShellV2Protocol.PacketKind.WINDOW_SIZE_CHANGE -> {
          val windowSizePayload = String(ADB_CHARSET.decode(ByteBuffer.wrap(packet.bytes)).array())
          protocol.writeStdout(windowSizePayload + "\n")
        }

        ShellV2Protocol.PacketKind.STDOUT,
        ShellV2Protocol.PacketKind.STDERR,
        ShellV2Protocol.PacketKind.EXIT_CODE,
        ShellV2Protocol.PacketKind.INVALID -> {
          throw IllegalStateException("unable to handle received packet ${packet.kind}")
        }
      }
    }

    // Write "EXIT_CODE" packet before closing connection
    protocol.writeExitCode(shellCommandOutput.exitCode)
  }
}

private fun ByteArray.toCommands(): List<String> {
  val commandsString = String(ADB_CHARSET.decode(ByteBuffer.wrap(this)).array())
  return commandsString.trim().split("\r", "\n").toList().filter { it.isNotEmpty() }
}

private enum class ExecuteCommandsResult {
  Exit,
  Continue,
}

private fun executeCommands(
  commands: List<String>,
  shellCommandOutput: ShellCommandOutput,
  shellProtocolType: ShellProtocolType,
  server: FakeAdbServer,
  socketScope: CoroutineScope,
  socket: Socket,
  device: DeviceState,
): ExecuteCommandsResult {
  for (rawCommand in commands) {
    shellCommandOutput.writeStdout("$ $rawCommand\n")
    // Special case: if command ends with `|| exit`, force shell session to terminate
    // TODO: see b/409350504 for proper handling of pipes
    val exitOnCommandFailure = rawCommand.endsWith("|| exit")
    val command =
      if (exitOnCommandFailure) {
        rawCommand.removeSuffix("|| exit")
      } else {
        rawCommand
      }

    var commandHandled = false
    for (handler in server.handlers) {
      val accepted =
        handler.accept(
          server,
          socketScope,
          socket,
          device,
          shellProtocolType.command,
          command,
          statusWriter = NoOperationStatusWriter(),
          { shellCommandOutput },
        )
      if (accepted) {
        if (exitOnCommandFailure && shellCommandOutput.exitCode != null && shellCommandOutput.exitCode != 0) {
          return ExecuteCommandsResult.Exit
        }
        // Special case: `exit` forces the shell session to terminate
        if (handler is ExitCommandHandler) {
          return ExecuteCommandsResult.Exit
        }
        commandHandled = true
        break
      }
    }
    if (!commandHandled) {
      throw IllegalStateException("Couldn't find command handler for `$command` while in interactive terminal session")
    }
  }
  return ExecuteCommandsResult.Continue
}

private fun isShellV1Command(command: String): Boolean {
  return command.startsWith("shell") && !command.startsWith("shell,v2")
}

private fun isShellV2Command(command: String): Boolean {
  return command.startsWith("shell,v2")
}
