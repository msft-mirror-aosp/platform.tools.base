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
import com.android.adblib.SocketSpec
import com.android.adblib.shellAsText
import com.android.adblib.syncSend
import com.android.tools.ui.inspector.common.ProtocolConstants
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/** The name of the agent binary file. */
private const val AGENT_FILE_NAME = "lib_ui_inspector_agent.so"

/** The name of the service jar file. */
private const val SERVICE_JAR_FILE_NAME = "lib_ui_inspector_service.jar"

/**
 * A globally writable directory on the device used as a staging area for pushing agent binaries before they are moved to the app's private
 * directory.
 */
private const val DEVICE_TMP_DIR = "/data/local/tmp"

/** The name of the payload jar file. */
private const val PAYLOAD_JAR_FILE_NAME = "lib_ui_inspector_payload.jar"

/** The relative path to the payload jar in the runfiles. */
private const val HOST_PAYLOAD_JAR_PATH = "tools/base/ui-inspector/agent/payload/$PAYLOAD_JAR_FILE_NAME"

/** The temporary path on the device where the payload jar is first pushed. */
private const val DEVICE_TMP_PAYLOAD_JAR_PATH = "$DEVICE_TMP_DIR/$PAYLOAD_JAR_FILE_NAME"

/** The relative path to the agent directory in the source tree or runfiles. */
private const val HOST_AGENT_PATH = "tools/base/ui-inspector/agent/native/$AGENT_FILE_NAME"

/** The relative path to the service jar in the runfiles. */
private const val HOST_SERVICE_JAR_PATH = "tools/base/ui-inspector/agent/service/$SERVICE_JAR_FILE_NAME"

// TODO: Consider using unique names or subdirectories to avoid race conditions if multiple instances run concurrently on the same device.
/** The temporary path on the device where the agent is first pushed. */
private const val DEVICE_TMP_AGENT_PATH = "$DEVICE_TMP_DIR/$AGENT_FILE_NAME"

/** The temporary path on the device where the service jar is first pushed. */
private const val DEVICE_TMP_SERVICE_JAR_PATH = "$DEVICE_TMP_DIR/$SERVICE_JAR_FILE_NAME"

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
  val serial: String,
  val packageName: String,
  private val agentPathResolver: (String) -> Path = DEFAULT_AGENT_PATH_RESOLVER,
  private val serviceJarPath: Path = Paths.get(HOST_SERVICE_JAR_PATH),
  private val payloadJarPath: Path = Paths.get(HOST_PAYLOAD_JAR_PATH),
) {

  private val deviceSelector = DeviceSelector.fromSerialNumber(serial)

  /**
   * The absolute path to the application's private data directory on the device, queried via `run-as`. This is queried at runtime to
   * correctly support multi-user environments (e.g. /data/user/10/) where the standard /data/data/ prefix is not applicable.
   */
  @Volatile private var appDataDir: String? = null

  /**
   * Push the agent to the device and attach it to the specified app.
   *
   * @param serial The device serial number.
   * @param packageName The package name of the app to attach the agent to.
   * @return The forwarded TCP port number on the host. Connect to this port to communicate with the agent.
   */
  suspend fun injectAndAttach(): String = coroutineScope {
    // Query app data dir and pid synchronously at the beginning to verify that the app is installed and running
    appDataDir = queryAppDataDir(deviceSelector, packageName)
    val pid = getPid(deviceSelector, packageName)

    // Enable debug view attributes before attaching.
    // This is needed for the platform to expose attribute resolution traces.
    // We don't clean this up because changing this flag causes the activity to restart. We do it once so the activity doesn't need to
    // restart each time.
    val flagSet = async { adbSession.deviceServices.shellAsText(deviceSelector, "settings put global debug_view_attributes 1") }

    val abiDeferred = async { getDeviceAbi(deviceSelector) }

    val deviceAbi = abiDeferred.await()
    val agentLocalPath = getAgentLocalPath(deviceAbi)
    val serviceJarLocalPath = getServiceJarLocalPath()
    val payloadJarLocalPath = getPayloadJarLocalPath()

    val agentPush = async { pushFileToDevice(deviceSelector, agentLocalPath, DEVICE_TMP_AGENT_PATH) }
    val jarPush = async { pushFileToDevice(deviceSelector, serviceJarLocalPath, DEVICE_TMP_SERVICE_JAR_PATH) }
    val payloadPush = async { pushFileToDevice(deviceSelector, payloadJarLocalPath, DEVICE_TMP_PAYLOAD_JAR_PATH) }

    val agentRemoteTmpPath = agentPush.await()
    val serviceJarRemoteTmpPath = jarPush.await()
    val payloadRemoteTmpPath = payloadPush.await()
    flagSet.await()

    copyAndSetupFiles(deviceSelector, packageName, agentRemoteTmpPath, serviceJarRemoteTmpPath, payloadRemoteTmpPath)

    attachAgent(deviceSelector, packageName, pid)

    val socketName = ProtocolConstants.getSocketName(pid)
    waitForAgentSocket(deviceSelector, socketName)

    setupAdbForward(deviceSelector, socketName)
  }

  /** Sets up adb port forwarding to the agent. */
  private suspend fun setupAdbForward(deviceSelector: DeviceSelector, socketName: String): String {
    val localSpec = SocketSpec.Tcp()
    val remoteSpec = SocketSpec.LocalAbstract(socketName)
    val port = adbSession.hostServices.forward(deviceSelector, localSpec, remoteSpec)
    return port ?: throw IllegalStateException("Failed to set up adb forward")
  }

  /**
   * Waits for the abstract socket to appear in /proc/net/unix. Since the agent acts as the server, we must wait for it to create the socket
   * before the host can connect to it.
   */
  private suspend fun waitForAgentSocket(deviceSelector: DeviceSelector, socketName: String) {
    val maxAttempts = 10
    var attempts = 0
    var delayMs = 100L
    while (attempts < maxAttempts) {
      val cmd = "cat /proc/net/unix | grep $socketName || true"
      val output = runShellCommand(deviceSelector, cmd).stdout
      if (output.contains(socketName)) {
        return
      }
      attempts++
      delay(delayMs)
      delayMs = (delayMs * 2).coerceAtMost(1000L)
    }
    throw IllegalStateException("Timed out waiting for agent socket $socketName")
  }

  /** Queries the device for the PID of the specified package. */
  private suspend fun getPid(deviceSelector: DeviceSelector, packageName: String): String {
    val result = adbSession.deviceServices.shellAsText(deviceSelector, "pidof $packageName")
    val stdout = result.stdout.trim()
    if (result.exitCode != 0 || stdout.isEmpty()) {
      throw IllegalStateException("The application '$packageName' is not running on the device. Please start the app and try again.")
    }
    // pidof can return multiple PIDs if there are multiple processes.
    // We take the first one, which is usually the main process.
    // TODO: Handle multi-process apps more robustly.
    return stdout.split(" ")[0]
  }

  /** Queries the device for the absolute path to the app's data directory. */
  private suspend fun queryAppDataDir(deviceSelector: DeviceSelector, packageName: String): String {
    val result = adbSession.deviceServices.shellAsText(deviceSelector, "run-as $packageName pwd")
    val stdout = result.stdout.trim()
    if (result.exitCode != 0 || stdout.isEmpty()) {
      throw IllegalStateException(
        "Failed to access the application '$packageName'. Please make sure the app is installed, debuggable, and running under the current user."
      )
    }
    return stdout
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

  /** Resolves the local path to the payload jar. */
  private fun getPayloadJarLocalPath(): Path {
    if (!payloadJarPath.toFile().exists()) {
      throw IllegalStateException("Payload JAR not found at $payloadJarPath")
    }
    return payloadJarPath
  }

  private suspend fun copyAndSetupFiles(
    deviceSelector: DeviceSelector,
    packageName: String,
    agentRemoteTmpPath: String,
    serviceJarRemoteTmpPath: String,
    payloadRemoteTmpPath: String,
  ) {
    val setupCmd =
      "run-as $packageName sh -c '" +
        // Delete previous versions of the files
        "rm -f $AGENT_FILE_NAME $SERVICE_JAR_FILE_NAME $PAYLOAD_JAR_FILE_NAME && " +
        // Copy new versions from tmp
        "cat $agentRemoteTmpPath > $AGENT_FILE_NAME && " +
        "cat $serviceJarRemoteTmpPath > $SERVICE_JAR_FILE_NAME && " +
        "cat $payloadRemoteTmpPath > $PAYLOAD_JAR_FILE_NAME && " +
        // Set permissions to 444
        "chmod 444 $AGENT_FILE_NAME && " +
        "chmod 444 $SERVICE_JAR_FILE_NAME && " +
        "chmod 444 $PAYLOAD_JAR_FILE_NAME'"
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

  /**
   * Pushes an inspector payload jar to the device and sets it up in the app's data directory.
   *
   * @param inspector The metadata of the inspector to push.
   * @return The full remote path to the pushed jar file on the device.
   */
  suspend fun pushInspectorPayload(inspector: InspectorMetadata): String {
    val remoteFileName = inspector.localJarPath.fileName.toString()
    val remoteTmpPath = "$DEVICE_TMP_DIR/$remoteFileName"

    // Push to tmp folder
    pushFileToDevice(deviceSelector, inspector.localJarPath, remoteTmpPath)

    // Copy to app dir via run-as
    val setupCmd =
      "run-as $packageName sh -c '" +
        "rm -f $remoteFileName && " +
        "cat $remoteTmpPath > $remoteFileName && " +
        "chmod 444 $remoteFileName'"
    runShellCommand(deviceSelector, setupCmd)

    return "$appDataDir/$remoteFileName"
  }

  private suspend fun attachAgent(deviceSelector: DeviceSelector, packageName: String, pid: String) {
    val appPath = "$appDataDir/$AGENT_FILE_NAME"
    val appJarPath = "$appDataDir/$SERVICE_JAR_FILE_NAME"
    val appPayloadJarPath = "$appDataDir/$PAYLOAD_JAR_FILE_NAME"
    val attachCmd = "cmd activity attach-agent $packageName \"$appPath=$appJarPath;$appPayloadJarPath;$pid\""
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
