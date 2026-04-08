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

import com.android.adblib.AdbSession
import com.android.adblib.DeviceSelector
import com.android.adblib.RemoteFileMode
import com.android.adblib.ShellCommandOutput
import com.android.adblib.shellAsText
import com.android.adblib.syncSend
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission

/** The name of the agent binary file. */
private const val AGENT_FILE_NAME = "lib_ui_inspector_agent.so"

/** The relative path to the agent directory in the source tree or runfiles. */
private const val HOST_AGENT_PATH = "tools/base/ui-inspector/agent/native/$AGENT_FILE_NAME"

/** The temporary path on the device where the agent is first pushed. */
private const val DEVICE_TMP_AGENT_PATH = "/data/local/tmp/$AGENT_FILE_NAME"

/** Default resolver that locates the agent binary in the Bazel runfiles directory. It uses the device ABI to find the correct binary. */
private val DEFAULT_AGENT_PATH_RESOLVER: (String) -> Path = { abi -> Paths.get(HOST_AGENT_PATH, abi, AGENT_FILE_NAME) }

/**
 * Manages the lifecycle of injecting and attaching the native agent to a target application.
 *
 * @param adbSession The [AdbSession] to use for device communication.
 * @param agentPathResolver A function that takes a device ABI string and returns the [Path] to the agent binary on the host.
 */
class InjectionManager(private val adbSession: AdbSession, private val agentPathResolver: (String) -> Path = DEFAULT_AGENT_PATH_RESOLVER) {

  /**
   * Push the agent to the device and attach it to the specified app.
   *
   * @param serial The device serial number.
   * @param packageName The package name of the app to attach the agent to.
   */
  suspend fun injectAndAttach(serial: String, packageName: String) {
    val deviceSelector = DeviceSelector.fromSerialNumber(serial)

    val deviceAbi = getDeviceAbi(deviceSelector)
    val agentLocalPath = getAgentLocalPath(deviceAbi)
    val agentRemoteTmpPath = pushAgentToDevice(deviceSelector, agentLocalPath)
    copyAgentToAppDir(deviceSelector, packageName, agentRemoteTmpPath)
    setAgentPermissions(deviceSelector, packageName)
    attachAgent(deviceSelector, packageName)
  }

  /** Queries the device for its CPU ABI. */
  private suspend fun getDeviceAbi(deviceSelector: DeviceSelector): String {
    return runShellCommand(deviceSelector, "getprop ro.product.cpu.abi").stdout.trim()
  }

  /** Resolves the local path to the agent binary for the given ABI. */
  private fun getAgentLocalPath(deviceAbi: String): Path {
    val localPath = agentPathResolver(deviceAbi)
    if (!localPath.toFile().exists()) {
      throw IllegalStateException("Agent binary not found at $localPath")
    }
    return localPath
  }

  /** Pushes the agent binary to a temporary location on the device. */
  private suspend fun pushAgentToDevice(deviceSelector: DeviceSelector, localPath: Path): String {
    val remoteTmpPath = DEVICE_TMP_AGENT_PATH
    // App needs read permission to copy it from /data/local/tmp (run-as uses a different user)
    val permissions =
      RemoteFileMode.fromPosixPermissions(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.GROUP_READ,
        PosixFilePermission.OTHERS_READ,
      )
    adbSession.deviceServices.syncSend(deviceSelector, localPath, remoteTmpPath, permissions)
    return remoteTmpPath
  }

  /** Copies the agent from the temporary location to the app's private data directory. */
  private suspend fun copyAgentToAppDir(deviceSelector: DeviceSelector, packageName: String, remoteTmpPath: String) {
    runShellCommand(deviceSelector, "run-as $packageName sh -c 'cat $remoteTmpPath > $AGENT_FILE_NAME'")
  }

  /** Sets read-only permissions on the agent binary in the app's directory. */
  private suspend fun setAgentPermissions(deviceSelector: DeviceSelector, packageName: String) {
    runShellCommand(deviceSelector, "run-as $packageName chmod 444 $AGENT_FILE_NAME")
  }

  /** Attaches the agent to the running application via JVMTI. */
  private suspend fun attachAgent(deviceSelector: DeviceSelector, packageName: String) {
    val appPath = "/data/data/$packageName/$AGENT_FILE_NAME"
    val attachCmd = "cmd activity attach-agent $packageName $appPath"
    runShellCommand(deviceSelector, attachCmd)
  }

  /** Runs a shell command and throws an exception if it fails (exit code != 0). */
  private suspend fun runShellCommand(deviceSelector: DeviceSelector, command: String): ShellCommandOutput {
    val result = adbSession.deviceServices.shellAsText(deviceSelector, command)
    if (result.exitCode != 0) {
      throw IllegalStateException("Command '$command' failed with exit code ${result.exitCode}. Stderr: ${result.stderr}")
    }
    return result
  }
}
