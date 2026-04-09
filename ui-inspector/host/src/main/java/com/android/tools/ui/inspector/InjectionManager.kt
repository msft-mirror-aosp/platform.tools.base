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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The name of the agent binary file. */
private const val AGENT_FILE_NAME = "lib_ui_inspector_agent.so"

/** The name of the service jar file. */
private const val SERVICE_JAR_FILE_NAME = "lib_ui_inspector_service.jar"

/** The relative path to the agent directory in the source tree or runfiles. */
private const val HOST_AGENT_PATH = "tools/base/ui-inspector/agent/native/$AGENT_FILE_NAME"

/** The relative path to the service jar in the runfiles. */
private const val HOST_SERVICE_JAR_PATH = "tools/base/ui-inspector/agent/service/$SERVICE_JAR_FILE_NAME"

// TODO: Consider using unique names or subdirectories to avoid race conditions if multiple instances run concurrently on the same device.
/** The temporary path on the device where the agent is first pushed. */
private const val DEVICE_TMP_AGENT_PATH = "/data/local/tmp/$AGENT_FILE_NAME"

/** The temporary path on the device where the service jar is first pushed. */
private const val DEVICE_TMP_SERVICE_JAR_PATH = "/data/local/tmp/$SERVICE_JAR_FILE_NAME"

/** Default resolver that locates the agent binary in the Bazel runfiles directory. It uses the device ABI to find the correct binary. */
private val DEFAULT_AGENT_PATH_RESOLVER: (String) -> Path = { abi -> Paths.get(HOST_AGENT_PATH, abi, AGENT_FILE_NAME) }

/**
 * Manages the lifecycle of injecting and attaching the native agent to a target application.
 *
 * @param adbSession The [AdbSession] to use for device communication.
 * @param agentPathResolver A function that takes a device ABI string and returns the [Path] to the agent binary on the host.
 */
class InjectionManager(
  private val adbSession: AdbSession,
  private val agentPathResolver: (String) -> Path = DEFAULT_AGENT_PATH_RESOLVER,
  private val serviceJarPath: Path = Paths.get(HOST_SERVICE_JAR_PATH),
) {

  /**
   * Push the agent to the device and attach it to the specified app.
   *
   * @param serial The device serial number.
   * @param packageName The package name of the app to attach the agent to.
   */
  suspend fun injectAndAttach(serial: String, packageName: String) = coroutineScope {
    val deviceSelector = DeviceSelector.fromSerialNumber(serial)

    val deviceAbi = getDeviceAbi(deviceSelector)
    val agentLocalPath = getAgentLocalPath(deviceAbi)
    val serviceJarLocalPath = getServiceJarLocalPath()

    val agentPush = async { pushFileToDevice(deviceSelector, agentLocalPath, DEVICE_TMP_AGENT_PATH) }
    val jarPush = async { pushFileToDevice(deviceSelector, serviceJarLocalPath, DEVICE_TMP_SERVICE_JAR_PATH) }

    val agentRemoteTmpPath = agentPush.await()
    val serviceJarRemoteTmpPath = jarPush.await()

    copyAndSetupFiles(deviceSelector, packageName, agentRemoteTmpPath, serviceJarRemoteTmpPath)

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

  /** Resolves the local path to the service jar. */
  private fun getServiceJarLocalPath(): Path {
    if (!serviceJarPath.toFile().exists()) {
      throw IllegalStateException("Service JAR not found at $serviceJarPath")
    }
    return serviceJarPath
  }

  /** Copies files from staging to app directory and sets permissions in a single atomic operation. */
  private suspend fun copyAndSetupFiles(
    deviceSelector: DeviceSelector,
    packageName: String,
    agentRemoteTmpPath: String,
    serviceJarRemoteTmpPath: String,
  ) {
    val setupCmd =
      "run-as $packageName sh -c '" +
        // Delete previous versions of the files
        "rm -f $AGENT_FILE_NAME $SERVICE_JAR_FILE_NAME && " +
        // Copy new versions from tmp
        "cat $agentRemoteTmpPath > $AGENT_FILE_NAME && " +
        "cat $serviceJarRemoteTmpPath > $SERVICE_JAR_FILE_NAME && " +
        // Set permissions to 444
        "chmod 444 $AGENT_FILE_NAME && " +
        "chmod 444 $SERVICE_JAR_FILE_NAME'"
    runShellCommand(deviceSelector, setupCmd)
  }

  /** Pushes a file to a temporary location on the device. */
  // TODO: Add a check to verify file hash on device before pushing to avoid redundant pushes if the file is already there.
  private suspend fun pushFileToDevice(deviceSelector: DeviceSelector, localPath: Path, remoteTmpPath: String): String {
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

  /** Attaches the agent to the running application via JVMTI. */
  private suspend fun attachAgent(deviceSelector: DeviceSelector, packageName: String) {
    val appPath = "/data/data/$packageName/$AGENT_FILE_NAME"
    val appJarPath = "/data/data/$packageName/$SERVICE_JAR_FILE_NAME"
    val attachCmd = "cmd activity attach-agent $packageName $appPath=$appJarPath"
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
