/*
 * Copyright (C) 2017 The Android Open Source Project
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

import com.android.fakeadbserver.DeviceFileState
import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.FakeAdbServer
import com.android.fakeadbserver.ShellProtocolType
import com.android.fakeadbserver.services.ShellCommandOutput
import com.android.fakeadbserver.services.StatusWriter
import com.android.fakeadbserver.services.readStdinByte
import java.nio.file.attribute.PosixFilePermission
import kotlin.text.Charsets.UTF_8

class ScreenRecordCommandHandler(shellProtocolType: ShellProtocolType) : SimpleShellHandler(shellProtocolType, "screenrecord") {

  override fun execute(
    fakeAdbServer: FakeAdbServer,
    statusWriter: StatusWriter,
    shellCommandOutput: ShellCommandOutput,
    device: DeviceState,
    shellCommand: String,
    shellCommandArgs: String?,
  ) {
    statusWriter.writeOk()
    if (device.apiLevel <= 18) {
      shellCommandOutput.writeStderr("/system/bin/sh: screenrecord: not found\n")
      shellCommandOutput.writeExitCode(255)
      return
    }

    if (shellCommandArgs == null) {
      // Simulate behavior for `adb shell screenrecord` without an output file name specified
      shellCommandOutput.writeStderr("Must specify output file (see --help).")
      shellCommandOutput.writeExitCode(2)
      return
    }
    val parameters = shellCommandArgs.split(" ")
    val outputPath = parameters.last()

    createDeviceFile(device, outputPath, UNFINISHED_RECORDING_CONTENTS)

    // Wait until "Ctrl-C" is returned, then write file contents
    while (true) {
      val inputChar = shellCommandOutput.readStdinByte()
      if (inputChar == 0x03) {
        createDeviceFile(device, outputPath, FINISHED_RECORDING_CONTENTS)
        break
      } else {
        println("Ignoring character from `stdin`: $inputChar")
      }
    }
  }

  private fun createDeviceFile(device: DeviceState, outputPath: String, bytes: ByteArray) {
    DeviceFileState(path = outputPath, permissions = arrayOf(PosixFilePermission.OWNER_READ), modifiedDate = 0, bytes = bytes).also {
      device.createFile(it)
    }
  }

  companion object {
    val UNFINISHED_RECORDING_CONTENTS = "unfinishedFiled".toByteArray(UTF_8)
    val FINISHED_RECORDING_CONTENTS = "finished_video_file".toByteArray(UTF_8)
  }
}
