/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.fakeadbserver.shellcommandhandlers

import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.FakeAdbServer
import com.android.fakeadbserver.ShellProtocolType
import com.android.fakeadbserver.devicecommandhandlers.DeviceCommandHandler
import com.android.fakeadbserver.services.ShellCommandOutput
import com.android.fakeadbserver.services.ShellCommandOutputWithDefaultExitCode
import com.android.fakeadbserver.services.StatusWriter
import java.net.Socket
import kotlinx.coroutines.CoroutineScope

/**
 * ShellHandler is a pre-supplied convenience construct to plug in and handle general shell commands. This reflects the "shell,v2:command"
 * local service as stated in the ADB protocol.
 *
 * TODO: Rename to reflect this can handle both "shell" and "shell,v2" protocol.
 */
abstract class ShellHandler protected constructor(protected val shellProtocolType: ShellProtocolType) :
  DeviceCommandHandler(shellProtocolType.command) {

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
    if (this.command != command) {
      return false
    }
    val split = args.trim().split(" ", limit = 2)
    val shellCommand = split[0]
    val shellCommandArgs = if (split.size > 1) split[1] else null
    if (shouldExecute(shellCommand, shellCommandArgs)) {
      val shellCommandOutput =
        ShellCommandOutputWithDefaultExitCode(shellCommandOutputProvider?.invoke() ?: shellProtocolType.createServiceOutput(socket, device))
      execute(server, statusWriter, shellCommandOutput, device, shellCommand, shellCommandArgs)
      statusWriter.verifyStatusWritten()
      shellCommandOutput.writeDefaultExitCode()
      return true
    }
    return false
  }

  /**
   * Return true if the derived class will be able to act on this shell command
   *
   * @param shellCommand Shell command, e.g. for "adb shell ls -l" [shellCommand] would be "ls"
   * @param shellCommandArgs Arguments for the command, e.g. for "adb shell ls -l" [shellCommandArgs] would be "-l"
   */
  abstract fun shouldExecute(shellCommand: String, shellCommandArgs: String?): Boolean

  /**
   * This is the main execution method of the command.
   *
   * @param fakeAdbServer Fake ADB Server itself.
   * @param shellCommandOutput Shell protocol for standard in/out
   * @param device Target device for the command, if any.
   * @param shellCommand Shell command, e.g. for "adb shell ls -l" [shellCommand] would be "ls"
   * @param shellCommandArgs Arguments for the command, e.g. for "adb shell ls -l" [shellCommandArgs] would be "-l"
   */
  abstract fun execute(
    fakeAdbServer: FakeAdbServer,
    statusWriter: StatusWriter,
    shellCommandOutput: ShellCommandOutput,
    device: DeviceState,
    shellCommand: String,
    shellCommandArgs: String?,
  )
}
