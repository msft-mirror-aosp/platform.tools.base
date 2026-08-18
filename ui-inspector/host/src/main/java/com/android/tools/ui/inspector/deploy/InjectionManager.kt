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

package com.android.tools.ui.inspector.deploy

import com.android.adblib.AdbSession
import com.android.adblib.DevicePropertyNames
import com.android.adblib.DeviceSelector
import com.android.adblib.SocketSpec
import com.android.adblib.shellAsText
import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.device.AgentSocketChecker
import com.android.tools.ui.inspector.device.DebugViewAttributes
import com.android.tools.ui.inspector.device.TOP_ACTIVITY_SHELL_COMMAND
import com.android.tools.ui.inspector.device.UidResolver
import com.android.tools.ui.inspector.device.parseTopActivityProcesses
import com.android.tools.ui.inspector.device.shellAsTextOrThrow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** The name of the agent binary file. */
private const val AGENT_FILE_NAME = "lib_ui_inspector_agent.so"

/** The name of the service jar file. */
private const val SERVICE_JAR_FILE_NAME = "lib_ui_inspector_service.jar"

/** The name of the payload jar file. */
private const val PAYLOAD_JAR_FILE_NAME = "lib_ui_inspector_payload.jar"

/** The relative path to the payload jar in the runfiles. */
private const val HOST_PAYLOAD_JAR_PATH = "tools/base/ui-inspector/agent/payload/$PAYLOAD_JAR_FILE_NAME"

/** The relative path to the agent directory in the source tree or runfiles. */
private const val HOST_AGENT_PATH = "tools/base/ui-inspector/agent/native/$AGENT_FILE_NAME"

/** The relative path to the service jar in the runfiles. */
private const val HOST_SERVICE_JAR_PATH = "tools/base/ui-inspector/agent/service/$SERVICE_JAR_FILE_NAME"

/** Default resolver that locates the agent binary in the Bazel runfiles directory. It uses the device ABI to find the correct binary. */
private val DEFAULT_AGENT_PATH_RESOLVER: (String) -> Path = { abi -> Paths.get(HOST_AGENT_PATH, abi, AGENT_FILE_NAME) }

/**
 * Manages the lifecycle of injecting and attaching the native agent to a target application.
 *
 * @param adbSession The [AdbSession] to use for device communication.
 * @param composeInspectorOverrideJarPath A local Compose inspector jar substituting the maven-resolved one, or null when no override is
 *   configured. When present, its bytes join the server digest, so a changed override yields a new server instead of reconnecting to one
 *   that already loaded different inspector code.
 * @param agentPathResolver A function that takes a device ABI string and returns the [Path] to the agent binary on the host.
 * @param tempFileSuffixGenerator A function that generates unique suffixes for temporary files pushed to the device.
 */
class InjectionManager(
  private val adbSession: AdbSession,
  val serial: String,
  val packageName: String,
  private val composeInspectorOverrideJarPath: Path?,
  private val agentPathResolver: (String) -> Path = DEFAULT_AGENT_PATH_RESOLVER,
  private val serviceJarPath: Path = Paths.get(HOST_SERVICE_JAR_PATH),
  private val payloadJarPath: Path = Paths.get(HOST_PAYLOAD_JAR_PATH),
  private val viewInspectorJarPath: Path = InspectorRegistry.VIEW_INSPECTOR.localJarPath,
  internal val tempFileSuffixGenerator: () -> String = { "${UUID.randomUUID()}.tmp" },
) {
  init {
    validateSerial(serial)
    validatePackageName(packageName)
  }

  private val deviceSelector = DeviceSelector.fromSerialNumber(serial)
  private val uidResolver = UidResolver(adbSession, deviceSelector)
  private val artifactStaging = ArtifactStaging(adbSession, deviceSelector, tempFileSuffixGenerator)
  private val debugViewAttributes = DebugViewAttributes(adbSession, deviceSelector, packageName)
  private val agentSocketChecker = AgentSocketChecker(adbSession, deviceSelector)

  /** The local TCP spec of the adb forward created by [injectAndAttach]. Cleared by [removeAdbForward]. */
  private val forwardedPortSpec = AtomicReference<SocketSpec.Tcp?>(null)

  /**
   * Ensures an agent server built from the host's artifact set is running in the target app, and returns an adb forward to its socket.
   *
   * The server socket name embeds the target pid and the digest of the artifact set this host intends to ship (including the Compose
   * inspector override jar when one is configured), so a live socket with the expected name proves the process was injected by a host with
   * the same intended set. For the base artifacts that also means they were staged and loaded; the override is only staged later, when the
   * Compose inspector is created. In [InjectionMode.RECONNECT_IF_AVAILABLE], such a server is reused directly; the full injection sequence
   * (pushing artifacts, staging them via `run-as`, attaching the agent) runs only when no matching socket exists or in
   * [InjectionMode.FORCE_FULL_INJECTION]. Reconnection also skips the `run-as` accessibility check: the matching socket is the evidence of
   * a prior successful injection.
   *
   * @param needsDebugViewAttributes Whether the platform must expose attribute resolution stacks for the inspected app, i.e. whether the
   *   `resolution-stack` facet was requested. When true, the per-app debug-view-attributes setting is enabled (see
   *   [DebugViewAttributes.enable]) on both the reconnect and full paths — the setting is independent of the server lifecycle; when false,
   *   device settings are left untouched.
   * @param mode Whether a running agent server may be reused; see [InjectionMode].
   * @return An [InjectionResult] carrying the forwarded TCP port and how the server was obtained.
   */
  suspend fun injectAndAttach(needsDebugViewAttributes: Boolean, mode: InjectionMode): InjectionResult {
    // Fails fast on an unusable Compose inspector override, before any device work.
    validateComposeInspectorOverride()
    val (deviceAbi, sdkVersion) = retrieveDeviceMetadata(deviceSelector)
    if (sdkVersion < ProtocolConstants.MIN_SUPPORTED_API_LEVEL) {
      throw IllegalStateException(
        "The UI Inspector only supports API level ${ProtocolConstants.MIN_SUPPORTED_API_LEVEL} and above. The target device is running API level $sdkVersion."
      )
    }

    val packageUid =
      uidResolver.packageUid(packageName)
        ?: throw IllegalStateException(
          "Failed to access the application '$packageName'. Please make sure the app is installed, debuggable, and running under the current user."
        )

    // The pid is deliberately captured before any debug-view-attributes flip: the flip only relaunches activities within the existing
    // process, so the pid stays valid, and reading it first avoids mutating device settings when the target is not running.
    val pid = getPid(deviceSelector, packageName, packageUid)

    // Resolve local paths before touching device settings, so a missing host artifact cannot restart the app's activities for nothing.
    val agentLocalPath = getAgentLocalPath(deviceAbi)
    val serviceJarLocalPath = getServiceJarLocalPath()
    val payloadJarLocalPath = getPayloadJarLocalPath()
    val viewInspectorJarLocalPath = resolveLocalPathOrExtractFromClasspath(viewInspectorJarPath)

    // Build digests from the artifacts pushed to the device. The combined digest is part of the server socket name on the device and
    // allows the host to identify which version of the pushed artifacts it's connecting to.
    val digests =
      computeArtifactDigests(
        agentLocalPath,
        serviceJarLocalPath,
        payloadJarLocalPath,
        viewInspectorJarLocalPath,
        composeInspectorOverrideJarPath,
      )
    val serverToken = "${pid}_${digests.combined}"
    val socketName = ProtocolConstants.getSocketName(serverToken)

    return when (mode) {
      InjectionMode.RECONNECT_IF_AVAILABLE ->
        if (agentSocketChecker.isPresent(socketName)) {
          if (needsDebugViewAttributes) debugViewAttributes.enable()
          InjectionResult.Reconnected(setupAdbForward(deviceSelector, socketName))
        } else {
          performFullInjection(
            needsDebugViewAttributes,
            pid,
            serverToken,
            digests,
            agentLocalPath,
            serviceJarLocalPath,
            payloadJarLocalPath,
          )
        }
      InjectionMode.FORCE_FULL_INJECTION ->
        performFullInjection(needsDebugViewAttributes, pid, serverToken, digests, agentLocalPath, serviceJarLocalPath, payloadJarLocalPath)
    }
  }

  private fun validateComposeInspectorOverride() {
    val path = composeInspectorOverrideJarPath ?: return
    require(Files.isRegularFile(path)) { "Specified Compose Inspector JAR does not exist: $path" }
    require(Files.isReadable(path)) { "Specified Compose Inspector JAR is not readable: $path" }
  }

  /** Stages the agent artifacts on the device, installs them via `run-as`, attaches the agent to [pid], and waits for its server socket. */
  private suspend fun performFullInjection(
    needsDebugViewAttributes: Boolean,
    pid: String,
    serverToken: String,
    digests: ShippedArtifactsDigests,
    agentLocalPath: Path,
    serviceJarLocalPath: Path,
    payloadJarLocalPath: Path,
  ): InjectionResult.Injected = coroutineScope {
    // Query the app data dir synchronously before mutating device settings or pushing files, to verify that the app is accessible
    // (installed, debuggable, and running under the current user).
    val appDataDir = queryAppDataDir(deviceSelector, packageName)

    val debugViewAttributesSetup = async { if (needsDebugViewAttributes) debugViewAttributes.enable() }

    val serviceJarName = fileNameWithHash(SERVICE_JAR_FILE_NAME, digests.serviceJar)
    val payloadJarName = fileNameWithHash(PAYLOAD_JAR_FILE_NAME, digests.payloadJar)
    val (agentStagePath, serviceJarStagePath, payloadJarStagePath) =
      artifactStaging.stage(
        listOf(
          ArtifactToStage(agentLocalPath, AGENT_FILE_NAME, digests.agentBinary),
          ArtifactToStage(serviceJarLocalPath, SERVICE_JAR_FILE_NAME, digests.serviceJar),
          ArtifactToStage(payloadJarLocalPath, PAYLOAD_JAR_FILE_NAME, digests.payloadJar),
        )
      )
    debugViewAttributesSetup.await()

    installFiles(deviceSelector, packageName, agentStagePath, serviceJarStagePath, payloadJarStagePath, serviceJarName, payloadJarName)

    val attachStartTime = captureAttachStartTime()
    attachAgent(deviceSelector, pid, serverToken, appDataDir, serviceJarName, payloadJarName)

    val socketName = ProtocolConstants.getSocketName(serverToken)
    agentSocketChecker.waitUntilPresent(socketName, pid, attachStartTime)

    InjectionResult.Injected(setupAdbForward(deviceSelector, socketName))
  }

  /** Sets up adb port forwarding to the agent. */
  private suspend fun setupAdbForward(deviceSelector: DeviceSelector, socketName: String): String {
    val localSpec = SocketSpec.Tcp()
    val remoteSpec = SocketSpec.LocalAbstract(socketName)
    val port =
      adbSession.hostServices.forward(deviceSelector, localSpec, remoteSpec) ?: throw IllegalStateException("Failed to set up adb forward")
    forwardedPortSpec.set(SocketSpec.Tcp(port.toInt()))
    return port
  }

  /**
   * Removes the adb forward created by [injectAndAttach], if any. Forwards outlive the CLI process, so every run must remove its own on
   * success, failure, and cancellation alike.
   */
  suspend fun removeAdbForward() {
    val localSpec = forwardedPortSpec.getAndSet(null) ?: return
    try {
      adbSession.hostServices.killForward(deviceSelector, localSpec)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      System.err.println("Warning: failed to remove adb forward ${localSpec.toQueryString()}: ${e.message}")
    }
  }

  /**
   * Queries the device for the PID of the target application: the process owned by [packageUid], preferring the one hosting the top
   * activity when the app has several. Under legacy sharedUserId, sibling packages own the same UID, so their processes are candidates too.
   */
  private suspend fun getPid(deviceSelector: DeviceSelector, packageName: String, packageUid: Int): String {
    val candidatePids = uidResolver.pidsForUid(packageUid)
    if (candidatePids.isEmpty()) {
      throw IllegalStateException("The application '$packageName' is not running on the device. Please start the app and try again.")
    }
    if (candidatePids.size == 1) {
      // If packageName has only one pid associated to it, use that.
      // This is going to be the case for most apps.
      return candidatePids[0]
    }

    // For multi-process applications, check if any of the pids is the pid of the foreground activity
    val topActivityPid = getTopActivityPid(deviceSelector, candidatePids)
    if (topActivityPid != null) {
      return topActivityPid
    }

    // Fall back to the first candidate PID
    return candidatePids[0]
  }

  /** Queries `dumpsys activity processes` to identify the PID currently hosting the top (foreground) activity. */
  private suspend fun getTopActivityPid(deviceSelector: DeviceSelector, candidatePids: List<String>): String? {
    return try {
      val output = adbSession.deviceServices.shellAsText(deviceSelector, TOP_ACTIVITY_SHELL_COMMAND).stdout
      val candidateSet = candidatePids.toSet()
      parseTopActivityProcesses(output).firstOrNull { it.pid in candidateSet }?.pid
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      null
    }
  }

  /**
   * Reads the device's current time in logcat format, marking the start of an attach attempt: captured just before `attach-agent` runs, so
   * everything the agent logs during the attach can be found by the later diagnostics. Null when the device clock cannot be read; the
   * logcat search then simply runs unbounded.
   */
  private suspend fun captureAttachStartTime(): String? {
    return try {
      // Format matching logcat timestamp: "MM-DD HH:MM:SS.000"
      val result = adbSession.deviceServices.shellAsText(deviceSelector, "date +\"%m-%d %H:%M:%S.000\"")
      val stdout = result.stdout.trim()
      if (result.exitCode == 0 && stdout.isNotEmpty()) stdout else null
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      null
    }
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

  /** Queries the device for its CPU ABI and SDK API level in a single shell invocation. */
  private suspend fun retrieveDeviceMetadata(deviceSelector: DeviceSelector): DeviceMetadata {
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    val output = adbSession.deviceServices.shellAsTextOrThrow(deviceSelector, metadataCmd).stdout
    val lines = output.lines()
    val abi = lines.getOrNull(0)?.trim()
    if (abi.isNullOrEmpty()) {
      throw IllegalStateException("Failed to retrieve device CPU ABI.")
    }
    val sdkVersionStr = lines.getOrNull(1)?.trim()
    val sdkVersion = sdkVersionStr?.toIntOrNull() ?: throw IllegalStateException("Failed to retrieve device SDK API level.")
    return DeviceMetadata(abi, sdkVersion)
  }

  /** Resolves the local path to the agent binary for the given ABI. */
  private fun getAgentLocalPath(deviceAbi: String): Path {
    return resolveLocalPathOrExtractFromClasspath(agentPathResolver(deviceAbi))
  }

  /** Resolves the local path to the service jar. */
  private fun getServiceJarLocalPath(): Path {
    return resolveLocalPathOrExtractFromClasspath(serviceJarPath)
  }

  /** Resolves the local path to the payload jar. */
  private fun getPayloadJarLocalPath(): Path {
    return resolveLocalPathOrExtractFromClasspath(payloadJarPath)
  }

  /** Copies the staged artifacts into the app's private data directory by running [buildInstallCommand]. */
  private suspend fun installFiles(
    deviceSelector: DeviceSelector,
    packageName: String,
    agentStagePath: String,
    serviceJarStagePath: String,
    payloadJarStagePath: String,
    serviceJarName: String,
    payloadJarName: String,
  ) {
    val installCmd =
      buildInstallCommand(
        packageName = packageName,
        agentStagePath = agentStagePath,
        serviceJarStagePath = serviceJarStagePath,
        payloadJarStagePath = payloadJarStagePath,
        serviceJarName = serviceJarName,
        payloadJarName = payloadJarName,
        tempSuffix = tempFileSuffixGenerator(),
      )
    adbSession.deviceServices.shellAsTextOrThrow(deviceSelector, installCmd)
  }

  /**
   * Makes the view inspector jar available at its device staging path and returns that path. The staged file is the same
   * [viewInspectorJarPath] the artifact digest covers, so a server reached by digest match was built from a byte-identical jar.
   */
  suspend fun stageViewInspectorPayload(): String =
    stageInspectorPayload(InspectorMetadata(id = InspectorRegistry.VIEW_INSPECTOR.id, localJarPath = viewInspectorJarPath))

  /**
   * Makes an inspector payload jar available at its device staging path, pushing it only when that path is not already correctly staged,
   * and returns the path.
   */
  suspend fun stageInspectorPayload(inspector: InspectorMetadata): String {
    val actualLocalPath = resolveLocalPathOrExtractFromClasspath(inspector.localJarPath)
    val fileDigest = computeContentDigest(actualLocalPath)
    val artifactToStage = ArtifactToStage(actualLocalPath, inspector.localJarPath.fileName.toString(), fileDigest)
    return artifactStaging.stage(listOf(artifactToStage)).single()
  }

  /**
   * Attaches the agent to the target process. [appDataDir] is the absolute path to the application's private data directory as reported by
   * `run-as <package> pwd`; querying the path avoids assuming that the standard /data/data/ prefix is applicable.
   */
  private suspend fun attachAgent(
    deviceSelector: DeviceSelector,
    pid: String,
    serverToken: String,
    appDataDir: String,
    serviceJarName: String,
    payloadJarName: String,
  ) {
    val appPath = "$appDataDir/$AGENT_FILE_NAME"
    val appJarPath = "$appDataDir/$serviceJarName"
    val appPayloadJarPath = "$appDataDir/$payloadJarName"
    val attachCmd = "cmd activity attach-agent $pid \"$appPath=$appJarPath;$appPayloadJarPath;$serverToken\""
    adbSession.deviceServices.shellAsTextOrThrow(deviceSelector, attachCmd)
  }

  companion object {
    private val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z0-9._]+$")
    private val SERIAL_REGEX = Regex("^[a-zA-Z0-9.:_-]+$")

    private fun validatePackageName(packageName: String) {
      require(packageName.length <= 255 && PACKAGE_NAME_REGEX.matches(packageName)) { "Invalid package name: $packageName" }
    }

    private fun validateSerial(serial: String) {
      require(SERIAL_REGEX.matches(serial)) { "Invalid serial number: $serial" }
    }
  }
}

private data class DeviceMetadata(val abi: String, val sdkVersion: Int)

/**
 * Builds the `run-as` shell command that installs the staged artifacts into the app's data directory. Each file is written to a run-unique
 * temporary name and renamed onto its final name once the install is complete.
 */
internal fun buildInstallCommand(
  packageName: String,
  agentStagePath: String,
  serviceJarStagePath: String,
  payloadJarStagePath: String,
  serviceJarName: String,
  payloadJarName: String,
  tempSuffix: String,
): String {
  val agentTmp = "$AGENT_FILE_NAME.$tempSuffix"
  val serviceTmp = "$serviceJarName.$tempSuffix"
  val payloadTmp = "$payloadJarName.$tempSuffix"
  val staleServiceJarsPattern = fileNameWithHash(SERVICE_JAR_FILE_NAME, CONTENT_DIGEST_PATTERN)
  val stalePayloadJarsPattern = fileNameWithHash(PAYLOAD_JAR_FILE_NAME, CONTENT_DIGEST_PATTERN)
  return "run-as $packageName sh -c '" +
    // Use trap to delete temporary files at the end.
    "trap \"rm -f $agentTmp $serviceTmp $payloadTmp\" 0 && " +
    "test ! -d $AGENT_FILE_NAME && test ! -d $serviceJarName && test ! -d $payloadJarName && " +
    // Sweep other installed versions of the jars first.
    "rm -f $staleServiceJarsPattern $stalePayloadJarsPattern && " +
    "cat $agentStagePath > $agentTmp && " +
    "cat $serviceJarStagePath > $serviceTmp && " +
    "cat $payloadJarStagePath > $payloadTmp && " +
    "chmod 444 $agentTmp $serviceTmp $payloadTmp && " +
    "mv -f $serviceTmp $serviceJarName && " +
    "mv -f $payloadTmp $payloadJarName && " +
    "mv -f $agentTmp $AGENT_FILE_NAME'"
}

/** Whether [InjectionManager.injectAndAttach] may reuse an already-running agent server. */
enum class InjectionMode {
  /**
   * Reconnect to a running server whose socket matches the target pid and artifact digest; perform a full injection only when none does.
   */
  RECONNECT_IF_AVAILABLE,
  /** Perform a full injection regardless of any running server. */
  FORCE_FULL_INJECTION,
}

/** The outcome of [InjectionManager.injectAndAttach]: how the agent server was obtained, and the adb forward that reaches it. */
sealed interface InjectionResult {
  /** The forwarded TCP port number on the host. Connect to this port to communicate with the agent. */
  val forwardedPort: String

  /** An already-running server was reused; no message has been exchanged with it yet, so its liveness is not established. */
  data class Reconnected(override val forwardedPort: String) : InjectionResult

  /** The agent was freshly pushed and attached, and its server socket was observed. */
  data class Injected(override val forwardedPort: String) : InjectionResult
}
