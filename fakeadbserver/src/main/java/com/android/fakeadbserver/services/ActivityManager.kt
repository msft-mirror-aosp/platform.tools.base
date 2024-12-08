/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.fakeadbserver.services

import com.android.fakeadbserver.DeviceState
import com.android.server.adb.protos.AmCapabilitiesProto

class ActivityManager(private val deviceState: DeviceState) : Service {

    companion object {
        const val SERVICE_NAME = "activity"
    }

    override fun process(args: List<String>, shellCommandOutput: ShellCommandOutput) {
        val cmd = args[0]

        when (cmd) {
            "force-stop" -> {
                if (args.size <= 1) {
                    shellCommandOutput.writeStderr(
                        "Exception occurred while executing 'force-stop':\n"
                                + "java.lang.IllegalArgumentException: Argument expected after \"force-stop\"")
                    shellCommandOutput.writeExitCode(0)
                    return
                }
                val packageName = args[1]
                deviceState.stopClients(packageName)
                shellCommandOutput.writeExitCode(0)
            }
            "crash" -> {
                if (args.size <= 1) {
                    shellCommandOutput.writeStderr(
                        "Exception occurred while executing 'crash':\n"
                                + "java.lang.IllegalArgumentException: Argument expected after \"crash\"")
                    shellCommandOutput.writeExitCode(0)
                    return
                }
                val packageName = args[1]
                deviceState.stopClients(packageName)
                shellCommandOutput.writeExitCode(0)
            }
            "capabilities" -> {
                // See Android platform implementation here:
                // https://cs.android.com/android/platform/superproject/main/+/1b409eb6cacc9508e6f415353ddcacdcb6bdaf26:frameworks/base/services/core/java/com/android/server/am/ActivityManagerShellCommand.java;l=480
                val deviceCapabilities = deviceState.deviceCapabilities
                if (deviceCapabilities == null) {
                    // Simulate behavior of older devices (api <= 33)
                    writeUnknownCommand(shellCommandOutput, cmd)
                } else {
                    val protobufOutput = (args.size > 1) && (args[1] == "--protobuf")
                    if (protobufOutput) {
                        val proto = AmCapabilitiesProto.Capabilities.newBuilder().also { capabilitiesProto ->
                            capabilitiesProto.addAllValues(
                                deviceCapabilities.capabilities.map {
                                    AmCapabilitiesProto.Capability.newBuilder().setName(it).build()
                                })
                            capabilitiesProto.addAllVmCapabilities(
                                deviceCapabilities.vmCapabilities.map {
                                    AmCapabilitiesProto.VMCapability.newBuilder()
                                        .setName(it)
                                        .build()
                                })
                            capabilitiesProto.addAllFrameworkCapabilities(
                                deviceCapabilities.frameworkCapabilities.map {
                                    AmCapabilitiesProto.FrameworkCapability.newBuilder()
                                        .setName(it)
                                        .build()
                                })
                            capabilitiesProto.setVmInfo(
                                AmCapabilitiesProto.VMInfo.newBuilder().also { vmInfoProto ->
                                    deviceCapabilities.vmInfo?.name?.also {
                                        vmInfoProto.setName(it)
                                    }
                                    deviceCapabilities.vmInfo?.version?.also {
                                        vmInfoProto.setVersion(it)
                                    }
                                }
                            )
                        }.build().toByteArray()
                        shellCommandOutput.writeStdout(proto)
                        shellCommandOutput.writeExitCode(0)
                    } else {
                        shellCommandOutput.writeStdout("Format: 2")
                        deviceCapabilities.capabilities.forEach {
                            shellCommandOutput.writeStdout(it)
                        }
                        deviceCapabilities.vmCapabilities.forEach {
                            shellCommandOutput.writeStdout("vm:$it")
                        }
                        deviceCapabilities.frameworkCapabilities.forEach {
                            shellCommandOutput.writeStdout("framework:$it")
                        }
                        deviceCapabilities.vmInfo?.also {
                            shellCommandOutput.writeStdout("vm_name:${it.name}")
                            shellCommandOutput.writeStdout("vm_version:${it.version}")
                        }
                        shellCommandOutput.writeExitCode(0)
                    }
                }
            }
            else -> {
                writeUnknownCommand(shellCommandOutput, cmd)
            }
        }
    }

    private fun writeUnknownCommand(shellCommandOutput: ShellCommandOutput, command: String) {
        // This behavior matches devices that don't support a command, i.e. they write
        // a specific error message to stderr, but at the same time return an exitcode of "0"
        shellCommandOutput.writeStderr("Unknown command: $command")
        shellCommandOutput.writeExitCode(0)
    }
}
