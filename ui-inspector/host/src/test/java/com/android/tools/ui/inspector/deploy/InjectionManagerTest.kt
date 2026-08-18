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

import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceList
import com.android.adblib.DevicePropertyNames
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.testing.FakeAdbSession
import com.android.tools.ui.inspector.TestAdbDeviceServices
import com.android.tools.ui.inspector.TestAdbHostServices
import com.android.tools.ui.inspector.TestAdbSession
import com.android.tools.ui.inspector.client.CommandSender
import com.android.tools.ui.inspector.client.InspectorCrashException
import com.android.tools.ui.inspector.common.FramingProtocol
import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.device.TOP_ACTIVITY_SHELL_COMMAND
import com.android.tools.ui.inspector.doDumpUi
import com.android.tools.ui.inspector.model.UiDump
import com.android.tools.ui.inspector.printer.UiDumpPrinter
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import com.android.tools.ui.inspector.resolveTargetPackage
import com.android.tools.ui.inspector.runWithConnectedInspectors
import com.android.tools.ui.inspector.statProbeCommand
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ByteString
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.concurrent.thread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Content digest of an empty file (the content of every dummy artifact these tests create): first 12 hex of its SHA-256. */
private const val EMPTY_FILE_DIGEST = "e3b0c44298fc"

/** The device staging directory every artifact is pushed into under its digest-carrying name. */
private const val STAGING_DIR = "/data/local/tmp/ui-inspector"

/** The digest-named staging paths of the three (empty) base artifacts, in the order the staging batch lists them. */
private val BASE_STAGING_PATHS =
  arrayOf(
    "$STAGING_DIR/lib_ui_inspector_agent.$EMPTY_FILE_DIGEST.so",
    "$STAGING_DIR/lib_ui_inspector_service.$EMPTY_FILE_DIGEST.jar",
    "$STAGING_DIR/lib_ui_inspector_payload.$EMPTY_FILE_DIGEST.jar",
  )

class InjectionManagerTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private lateinit var fakeSession: FakeAdbSession
  private lateinit var testDeviceServices: TestAdbDeviceServices
  private lateinit var testHostServices: TestAdbHostServices
  private lateinit var testSession: com.android.adblib.AdbSession
  private lateinit var dummyAgent: Path
  private lateinit var dummyJar: Path
  private lateinit var dummyPayload: Path
  private lateinit var dummyViewInspector: Path
  private lateinit var agentPathResolver: (String) -> Path

  private val deviceSerial = "123"
  private val packageName = "com.example"

  /** The digest of the four dummy artifacts, all empty files: SHA-256 over four zero 64-bit lengths, truncated to 12 hex characters. */
  private val artifactDigest = "66687aadf862"

  private fun serverToken(pid: String) = "${pid}_$artifactDigest"

  private fun socketName(pid: String) = "ui_inspector_${serverToken(pid)}"

  private val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
  private val settingsSeparator = "__UI_INSPECTOR_SETTINGS_SEPARATOR__"
  private val readSettingsCmd =
    "settings get global debug_view_attributes ; echo $settingsSeparator ; settings get global debug_view_attributes_application_package"
  private val putSettingsCmd = "settings put global debug_view_attributes_application_package $packageName"

  @Before
  fun setUp() {
    fakeSession = FakeAdbSession()
    testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    testHostServices = TestAdbHostServices(fakeSession.hostServices)
    testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)
    testDeviceServices.session = testSession

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()
    dummyPayload = tempFolder.newFile("lib_ui_inspector_payload.jar").toPath()
    dummyViewInspector = tempFolder.newFile("view-inspector.jar").toPath()

    agentPathResolver = { abi -> dummyAgent }

    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "date +\"%m-%d %H:%M:%S.000\"", "06-24 15:44:32.000\n")
    listOf(
        "my-inspector.jar" to EMPTY_FILE_DIGEST,
        "view-inspector.jar" to EMPTY_FILE_DIGEST,
        "lib_ui_inspector_agent.so" to EMPTY_FILE_DIGEST,
        "lib_ui_inspector_service.jar" to EMPTY_FILE_DIGEST,
        "lib_ui_inspector_payload.jar" to EMPTY_FILE_DIGEST,
      )
      .forEach { (baseName, digest) -> configureStagedPush(baseName, digest) }
  }

  @Test
  fun testInjectAndAttach() = runTest {
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        dummyViewInspector,
        tempFileSuffixGenerator = { "test.tmp" },
      )
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    // Mock expected shell commands for the injection flow
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configurePackageUidAndProcesses()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")
    val setupCmd = installCommand()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, setupCmd, "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, attachCommand(), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix", "${socketName("1234")}\n")

    val port = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION).forwardedPort
    assertThat(port).isEqualTo("12345")

    // Verify that syncSend was called with correct parameters
    assertThat(testDeviceServices.recordedSyncSends).hasSize(3)
    val paths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(paths.sorted()).containsExactly(*BASE_STAGING_PATHS.map { "$it.test.tmp" }.sorted().toTypedArray())
  }

  @Test
  fun testInjectAndAttach_reconnectsToRunningServer() = runTest {
    val injectionManager = createInjectionManager()
    // Only the commands of the reconnect path are configured: any injection-only command (run-as, pushes, attach) fails as unconfigured.
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configurePackageUidAndProcesses()
    // A realistic /proc/net/unix line: the abstract socket name is the last field, with a leading '@'.
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "cat /proc/net/unix",
      "0000000000000000: 00000002 00000000 00010000 0001 01 68812 @${socketName("1234")}\n",
    )

    val result = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.RECONNECT_IF_AVAILABLE)

    assertThat(result).isInstanceOf(InjectionResult.Reconnected::class.java)
    assertThat(result.forwardedPort).isEqualTo("12345")
    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
    val commands = fakeSession.deviceServices.shellV2Requests.map { it.command }
    assertThat(commands.filter { it.startsWith("run-as ") }).isEmpty()
    assertThat(commands.filter { it.startsWith("cmd activity attach-agent") }).isEmpty()
    val remoteSpec = testHostServices.recordedForwardCalls.single().third
    assertThat(remoteSpec.toQueryString()).isEqualTo("localabstract:${socketName("1234")}")
  }

  @Test
  fun testInjectAndAttach_reconnectStillEnablesDebugViewAttributes() = runTest {
    val injectionManager = createInjectionManager()
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configurePackageUidAndProcesses()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix", "${socketName("1234")}\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "null\n$settingsSeparator\nnull\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, putSettingsCmd, "")

    val result = injectionManager.injectAndAttach(needsDebugViewAttributes = true, mode = InjectionMode.RECONNECT_IF_AVAILABLE)

    assertThat(result).isInstanceOf(InjectionResult.Reconnected::class.java)
    val commands = fakeSession.deviceServices.shellV2Requests.map { it.command }
    assertThat(commands).contains(putSettingsCmd)
    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
  }

  @Test
  fun testInjectAndAttach_prefixedSocketNameIsNotAMatch_fullInjectionRuns() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    // The probe sees a socket whose name merely starts with the expected name; the post-attach wait then sees the exact socket.
    testDeviceServices.queuedShellOutputs["cat /proc/net/unix"] = ArrayDeque(listOf("@${socketName("1234")}x\n", "${socketName("1234")}\n"))

    val result = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.RECONNECT_IF_AVAILABLE)

    assertThat(result).isInstanceOf(InjectionResult.Injected::class.java)
    assertThat(testDeviceServices.recordedSyncSends).hasSize(3)
    assertThat(fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("cmd activity attach-agent") })
      .hasSize(1)
  }

  @Test
  fun testInjectAndAttach_absentSocket_fullInjectionRuns() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    // The probe finds nothing; the post-attach wait then sees the socket the agent created.
    testDeviceServices.queuedShellOutputs["cat /proc/net/unix"] = ArrayDeque(listOf("", "${socketName("1234")}\n"))

    val result = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.RECONNECT_IF_AVAILABLE)

    assertThat(result).isInstanceOf(InjectionResult.Injected::class.java)
    assertThat(testDeviceServices.recordedSyncSends).hasSize(3)
  }

  @Test
  fun testInjectAndAttach_forceModeIgnoresRunningServer() = runTest {
    val injectionManager = createInjectionManager()
    // The exact socket is present throughout; forced injection must not probe for it and must run the full sequence anyway.
    configureSuccessfulInjection()

    val result = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)

    assertThat(result).isInstanceOf(InjectionResult.Injected::class.java)
    assertThat(testDeviceServices.recordedSyncSends).hasSize(3)
    val socketChecks = fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it == "cat /proc/net/unix" }
    // The only socket check is the post-attach wait.
    assertThat(socketChecks).hasSize(1)
  }

  @Test
  fun testInjectAndAttach_staleSocketFromDifferentBuild_timesOut() = runTest {
    val injectionManager = createInjectionManager()
    // A resident server from different artifacts listens on a different name: only the exact digest-scoped socket satisfies the wait, a
    // legacy pid-only socket does not.
    configureSuccessfulInjection(socketGrepOutput = "ui_inspector_1234\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "logcat -d -t '06-24 15:44:32.000' --pid=1234 *:E", "")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected timeout waiting for the digest-scoped socket")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("Timed out waiting for agent socket ${socketName("1234")}")
    }
  }

  @Test
  fun testInjectAndAttach_serverTokenCoversShippedArtifactContents() = runTest {
    Files.write(dummyAgent, byteArrayOf(1))
    Files.write(dummyJar, byteArrayOf(2))
    Files.write(dummyPayload, byteArrayOf(3))
    Files.write(dummyViewInspector, byteArrayOf(4))
    val token = "1234_" + computeArtifactDigests(dummyAgent, dummyJar, dummyPayload, dummyViewInspector).combined
    assertThat(token).isNotEqualTo(serverToken("1234"))
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection(token = token)

    val port = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION).forwardedPort

    assertThat(port).isEqualTo("12345")
    // The socket wait only saw the content-derived token socket (see configureSuccessfulInjection), so success proves the code waited for
    // exactly that name.
    val forwardTarget = testHostServices.recordedForwardCalls.single().third
    assertThat(forwardTarget.toQueryString()).isEqualTo("localabstract:ui_inspector_$token")
  }

  @Test
  fun testInjectAndAttach_missingArtifactFailsBeforeDeviceMutation() = runTest {
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        tempFolder.root.toPath().resolve("absent-view-inspector.jar"),
        tempFileSuffixGenerator = { "test.tmp" },
      )
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "null\n$settingsSeparator\nnull\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, putSettingsCmd, "")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = true, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected IllegalStateException for a missing artifact")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("File not found on filesystem")
    }

    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
    val commands = fakeSession.deviceServices.shellV2Requests.map { it.command }
    assertThat(commands.filter { it.startsWith("settings put ") }).isEmpty()
    assertThat(commands.filter { it.startsWith("cmd activity attach-agent") }).isEmpty()
  }

  @Test
  fun testInjectAndAttach_CommandFails() = runTest {
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        dummyViewInspector,
        tempFileSuffixGenerator = { "test.tmp" },
      )
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configurePackageUidAndProcesses()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val setupCmd = installCommand()

    // Configure this command to FAIL!
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      setupCmd,
      stdout = "",
      stderr = "Package is not debuggable",
      exitCode = 1,
    )

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected IllegalStateException was not thrown")
    } catch (e: IllegalStateException) {
      assertThat(e.message).isEqualTo("Command '$setupCmd' failed with exit code 1. Stderr: Package is not debuggable")
    }

    // Verify that both files were pushed to /data/local/tmp before the setup command failed.
    // The push operations occur concurrently and complete before the copy/setup step is executed.
    assertThat(testDeviceServices.recordedSyncSends).hasSize(3)
    val paths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(paths.sorted()).containsExactly(*BASE_STAGING_PATHS.map { "$it.test.tmp" }.sorted().toTypedArray())
  }

  @Test
  fun testInjectAndAttach_FastFailLogcatDiagnostics() = runTest {
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        dummyViewInspector,
        tempFileSuffixGenerator = { "test.tmp" },
      )
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    // Mock expected shell commands for the injection flow
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configurePackageUidAndProcesses()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val setupCmd = installCommand()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, setupCmd, "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, attachCommand(), "")

    // 1. Configure the socket check to fail (socket is not created by the agent)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix", "")

    // 2. Configure logcat command to return a mock agent crash log
    val logcatCmd = "logcat -d -t '06-24 15:44:32.000' --pid=1234 *:E"
    val mockErrorLog =
      "06-24 15:44:32.764  7191  7191 E studio.ui-inspector.InspectorService: Error in InspectorService initialization\n" +
        "06-24 15:44:32.764  7191  7191 E studio.ui-inspector.InspectorService: java.lang.RuntimeException: Simulated bootstrap failure"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, logcatCmd, mockErrorLog)

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected IllegalStateException due to agent bootstrap failure")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("Failed to attach UI Inspector agent. Agent error in logcat:")
      assertThat(e.message).contains("Error in InspectorService initialization")
      assertThat(e.message).contains("java.lang.RuntimeException: Simulated bootstrap failure")
    }
  }

  @Test
  fun testStageInspectorPayload_missingProbeConfiguration_pushes() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        dummyViewInspector,
        tempFileSuffixGenerator = { "test.tmp" },
      )

    val inspectorJar = tempFolder.newFile("my-inspector.jar").toPath()
    val inspector = InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar)
    val remotePath = injectionManager.stageInspectorPayload(inspector)

    assertThat(remotePath).isEqualTo("$STAGING_DIR/my-inspector.$EMPTY_FILE_DIGEST.jar")

    // Verify the inspector jar was pushed to tmp
    val pushedPaths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(pushedPaths).contains("$STAGING_DIR/my-inspector.$EMPTY_FILE_DIGEST.jar.test.tmp")
  }

  @Test
  fun testInjectAndAttach_correctlyStagedBaseArtifacts_skipsTheirTransfer() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    // All three digest-named staging paths already hold regular 0444 files: their content matches by construction of the name.
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      statProbeCommand(*BASE_STAGING_PATHS),
      BASE_STAGING_PATHS.joinToString(separator = "") { "8124 $it\n" },
    )

    val result = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)

    assertThat(result).isInstanceOf(InjectionResult.Injected::class.java)
    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
    // The run-as install into the app directory and the attach still run.
    val commands = fakeSession.deviceServices.shellV2Requests.map { it.command }
    assertThat(commands.filter { it.startsWith("run-as $packageName sh -c") }).hasSize(1)
    assertThat(commands.filter { it.startsWith("cmd activity attach-agent") }).hasSize(1)
  }

  @Test
  fun testInjectAndAttach_partiallyStagedArtifacts_pushesOnlyWrongModeAndMissing() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    // The agent is correctly staged; the service jar's path holds an entry with the wrong mode; the payload jar is missing entirely
    // (no record, nonzero exit, as stat behaves when a file is absent).
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      statProbeCommand(*BASE_STAGING_PATHS),
      "8124 ${BASE_STAGING_PATHS[0]}\n8180 ${BASE_STAGING_PATHS[1]}\n",
      exitCode = 1,
    )

    injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)

    assertThat(testDeviceServices.recordedSyncSends.map { it.remoteFilePath }.sorted())
      .containsExactly("${BASE_STAGING_PATHS[1]}.test.tmp", "${BASE_STAGING_PATHS[2]}.test.tmp")
  }

  @Test
  fun testStageInspectorPayload_correctlyStagedCopy_skipsTransfer() = runTest {
    val injectionManager = createInjectionManager()
    val inspectorJar = tempFolder.newFile("my-inspector.jar").toPath()
    val remotePath = "$STAGING_DIR/my-inspector.$EMPTY_FILE_DIGEST.jar"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, statProbeCommand(remotePath), "8124 $remotePath\n")

    val returned = injectionManager.stageInspectorPayload(InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar))

    assertThat(returned).isEqualTo(remotePath)
    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
    assertThat(fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.contains(" && mv -f ") }).isEmpty()
  }

  @Test
  fun testStageInspectorPayload_wrongMode_pushes() = runTest {
    val injectionManager = createInjectionManager()
    val inspectorJar = tempFolder.newFile("my-inspector.jar").toPath()
    val remotePath = "$STAGING_DIR/my-inspector.$EMPTY_FILE_DIGEST.jar"
    // The staged path holds an entry whose permissions are not the staging protocol's 0444 regular file (here 0400): it may be unreadable
    // by the app, so it is replaced.
    fakeSession.deviceServices.configureShellCommand(deviceSelector, statProbeCommand(remotePath), "8100 $remotePath\n")

    injectionManager.stageInspectorPayload(InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar))

    assertThat(testDeviceServices.recordedSyncSends.map { it.remoteFilePath }).containsExactly("$remotePath.test.tmp")
    assertThat(fakeSession.deviceServices.shellV2Requests.map { it.command })
      .contains(
        "rm -f $STAGING_DIR/my-inspector.$CONTENT_DIGEST_PATTERN.jar && test ! -d '$remotePath' && mv -f '$remotePath.test.tmp' '$remotePath'"
      )
  }

  @Test
  fun testStageInspectorPayload_missingStagedCopy_pushesWithoutFailure() = runTest {
    val injectionManager = createInjectionManager()
    val inspectorJar = tempFolder.newFile("my-inspector.jar").toPath()
    val remotePath = "$STAGING_DIR/my-inspector.$EMPTY_FILE_DIGEST.jar"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, statProbeCommand(remotePath), stdout = "", exitCode = 1)

    injectionManager.stageInspectorPayload(InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar))

    assertThat(testDeviceServices.recordedSyncSends.map { it.remoteFilePath }).containsExactly("$remotePath.test.tmp")
  }

  @Test
  fun testStageInspectorPayload_statToolFailure_pushesWithoutFailure() = runTest {
    val injectionManager = createInjectionManager()
    val inspectorJar = tempFolder.newFile("my-inspector.jar").toPath()
    val remotePath = "$STAGING_DIR/my-inspector.$EMPTY_FILE_DIGEST.jar"
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      statProbeCommand(remotePath),
      stdout = "",
      stderr = "sh: stat: not found",
      exitCode = 127,
    )

    injectionManager.stageInspectorPayload(InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar))

    assertThat(testDeviceServices.recordedSyncSends.map { it.remoteFilePath }).containsExactly("$remotePath.test.tmp")
  }

  @Test
  fun testStageInspectorPayload_unparseableProbeOutput_pushes() = runTest {
    val injectionManager = createInjectionManager()
    val inspectorJar = tempFolder.newFile("my-inspector.jar").toPath()
    val remotePath = "$STAGING_DIR/my-inspector.$EMPTY_FILE_DIGEST.jar"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, statProbeCommand(remotePath), "garbage output\n")

    injectionManager.stageInspectorPayload(InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar))

    assertThat(testDeviceServices.recordedSyncSends.map { it.remoteFilePath }).containsExactly("$remotePath.test.tmp")
  }

  @Test
  fun testParseStagedFileModes_records() {
    val paths = listOf("/tmp/a", "/tmp/b")
    val stdout = "8124 /tmp/b\n81a4 /tmp/a\n"

    assertThat(parseStagedFileModes(stdout, paths)).containsExactly("/tmp/a", "81a4", "/tmp/b", "8124")
  }

  @Test
  fun testParseStagedFileModes_pathWithoutRecordIsAbsent() {
    val states = parseStagedFileModes("8124 /tmp/a\n", listOf("/tmp/a", "/tmp/b"))

    assertThat(states!!.keys).containsExactly("/tmp/a")
  }

  @Test
  fun testParseStagedFileModes_emptyOutputIsEmptyMap() {
    assertThat(parseStagedFileModes("", listOf("/tmp/a"))).isEmpty()
  }

  @Test
  fun testParseStagedFileModes_malformedLineIsUnusable() {
    assertThat(parseStagedFileModes("not a stat line\n", listOf("/tmp/a"))).isNull()
    assertThat(parseStagedFileModes("81G4 /tmp/a\n", listOf("/tmp/a"))).isNull()
    assertThat(parseStagedFileModes("8124\n", listOf("/tmp/a"))).isNull()
  }

  @Test
  fun testParseStagedFileModes_unexpectedPathIsUnusable() {
    assertThat(parseStagedFileModes("8124 /tmp/other\n", listOf("/tmp/a"))).isNull()
    // A path that merely extends a requested one is unexpected, not a match.
    assertThat(parseStagedFileModes("8124 /tmp/a.bak\n", listOf("/tmp/a"))).isNull()
  }

  @Test
  fun testParseStagedFileModes_duplicateRecordIsUnusable() {
    assertThat(parseStagedFileModes("8124 /tmp/a\n81a4 /tmp/a\n", listOf("/tmp/a"))).isNull()
  }

  @Test
  fun testStageInspectorPayload_shellUnsafeFileName_isRejected() = runTest {
    val injectionManager = createInjectionManager()
    // The file name is interpolated into shell paths and an unquoted sweep pattern; anything outside the safe charset is refused up front.
    val inspectorJar = tempFolder.newFile("my inspector.jar").toPath()

    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { injectionManager.stageInspectorPayload(InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar)) }
      }

    assertThat(exception.message).contains("Invalid staged file name")
    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
  }

  @Test
  fun testStageInspectorPayload_directoryAtStagingPath_failsInsteadOfNestingTheFile() = runTest {
    val injectionManager = createInjectionManager()
    val inspectorJar = tempFolder.newFile("my-inspector.jar").toPath()
    val remotePath = "$STAGING_DIR/my-inspector.$EMPTY_FILE_DIGEST.jar"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, statProbeCommand(remotePath), stdout = "", exitCode = 1)
    // A directory occupies the staging path: mv onto it would silently move the temp file into it, so the guarded rename fails instead.
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "rm -f $STAGING_DIR/my-inspector.$CONTENT_DIGEST_PATTERN.jar && test ! -d '$remotePath' && mv -f '$remotePath.test.tmp' '$remotePath'",
      stdout = "",
      exitCode = 1,
    )
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "rm -f '$remotePath.test.tmp'", "")

    var thrown: Exception? = null
    try {
      injectionManager.stageInspectorPayload(InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar))
      fail("Expected the guarded rename failure to propagate")
    } catch (e: IllegalStateException) {
      thrown = e
    }

    assertThat(thrown!!.message).contains("failed with exit code 1")
    // The interrupted push cleaned up its temporary file.
    assertThat(fakeSession.deviceServices.shellV2Requests.map { it.command }).contains("rm -f '$remotePath.test.tmp'")
  }

  @Test
  fun testInjectAndAttach_nonstandardAppDataDir_attachUsesQueriedPath() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    // A secondary-user installation reports its own data directory; the attach command must use it verbatim.
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/user/10/$packageName\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, attachCommand(appDataDir = "/data/user/10/$packageName"), "")

    injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)

    assertThat(fakeSession.deviceServices.shellV2Requests.map { it.command })
      .contains(attachCommand(appDataDir = "/data/user/10/$packageName"))
  }

  @Test
  fun testBuildInstallCommand_exactShape() {
    val command =
      buildInstallCommand(
        packageName = "com.example",
        agentStagePath = "$STAGING_DIR/lib_ui_inspector_agent.aaaaaaaaaaaa.so",
        serviceJarStagePath = "$STAGING_DIR/lib_ui_inspector_service.bbbbbbbbbbbb.jar",
        payloadJarStagePath = "$STAGING_DIR/lib_ui_inspector_payload.cccccccccccc.jar",
        serviceJarName = "lib_ui_inspector_service.bbbbbbbbbbbb.jar",
        payloadJarName = "lib_ui_inspector_payload.cccccccccccc.jar",
        tempSuffix = "suffix.tmp",
      )

    assertThat(command)
      .isEqualTo(
        "run-as com.example sh -c '" +
          "trap \"rm -f lib_ui_inspector_agent.so.suffix.tmp lib_ui_inspector_service.bbbbbbbbbbbb.jar.suffix.tmp " +
          "lib_ui_inspector_payload.cccccccccccc.jar.suffix.tmp\" 0 && " +
          "test ! -d lib_ui_inspector_agent.so && test ! -d lib_ui_inspector_service.bbbbbbbbbbbb.jar && " +
          "test ! -d lib_ui_inspector_payload.cccccccccccc.jar && " +
          "rm -f lib_ui_inspector_service.$CONTENT_DIGEST_PATTERN.jar lib_ui_inspector_payload.$CONTENT_DIGEST_PATTERN.jar && " +
          "cat $STAGING_DIR/lib_ui_inspector_agent.aaaaaaaaaaaa.so > lib_ui_inspector_agent.so.suffix.tmp && " +
          "cat $STAGING_DIR/lib_ui_inspector_service.bbbbbbbbbbbb.jar > lib_ui_inspector_service.bbbbbbbbbbbb.jar.suffix.tmp && " +
          "cat $STAGING_DIR/lib_ui_inspector_payload.cccccccccccc.jar > lib_ui_inspector_payload.cccccccccccc.jar.suffix.tmp && " +
          "chmod 444 lib_ui_inspector_agent.so.suffix.tmp lib_ui_inspector_service.bbbbbbbbbbbb.jar.suffix.tmp " +
          "lib_ui_inspector_payload.cccccccccccc.jar.suffix.tmp && " +
          "mv -f lib_ui_inspector_service.bbbbbbbbbbbb.jar.suffix.tmp lib_ui_inspector_service.bbbbbbbbbbbb.jar && " +
          "mv -f lib_ui_inspector_payload.cccccccccccc.jar.suffix.tmp lib_ui_inspector_payload.cccccccccccc.jar && " +
          "mv -f lib_ui_inspector_agent.so.suffix.tmp lib_ui_inspector_agent.so'"
      )
  }

  @Test
  fun testQueryAppDataDir_Fails() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
    val injectionManager =
      InjectionManager(testSession, deviceSerial, packageName, null, agentPathResolver, dummyJar, dummyPayload, dummyViewInspector)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configurePackageUidAndProcesses()

    // Configure run-as pwd to fail
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "run-as $packageName pwd",
      stdout = "",
      stderr = "run-as: package not debuggable",
      exitCode = 1,
    )

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected IllegalStateException for failing run-as pwd")
    } catch (e: IllegalStateException) {
      assertThat(e.message)
        .contains(
          "Failed to access the application '$packageName'. Please make sure the app is installed, debuggable, and running under the current user."
        )
    }
  }

  @Test
  fun testInjectAndAttach_notRunningPreservesError() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
    val injectionManager =
      InjectionManager(testSession, deviceSerial, packageName, null, agentPathResolver, dummyJar, dummyPayload, dummyViewInspector)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "pm list packages -U --user 0 $packageName",
      "package:$packageName uid:10123\n",
    )
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "ps -A -o PID,UID,NAME", "PID UID NAME\n")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected IllegalStateException for an app with no running process")
    } catch (e: IllegalStateException) {
      assertThat(e.message).isEqualTo("The application '$packageName' is not running on the device. Please start the app and try again.")
    }
  }

  @Test
  fun testInjectAndAttach_psAdbExceptionPropagates() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
    val injectionManager =
      InjectionManager(testSession, deviceSerial, packageName, null, agentPathResolver, dummyJar, dummyPayload, dummyViewInspector)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "pm list packages -U --user 0 $packageName",
      "package:$packageName uid:10123\n",
    )
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    // The ps command is deliberately unconfigured so FakeAdbDeviceServices throws an exception simulating an ADB failure.

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected exception for unconfigured ps command")
    } catch (e: Exception) {
      assertThat(e.message).doesNotContain("The application '$packageName' is not running on the device")
      assertThat(e.message).contains("Command not setup")
    }
  }

  @Test
  fun testInjectAndAttach_packageNotInstalledPreservesAccessError() = runTest {
    val injectionManager = createInjectionManager()
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pm list packages -U --user 0 $packageName", "")

    var exception: IllegalStateException? = null
    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected package-not-installed failure")
    } catch (e: IllegalStateException) {
      exception = e
    }

    assertThat(exception!!.message)
      .isEqualTo(
        "Failed to access the application '$packageName'. Please make sure the app is installed, debuggable, and running under the current user."
      )
    val commands = fakeSession.deviceServices.shellV2Requests.map { it.command }
    assertThat(commands).doesNotContain("run-as $packageName pwd")
    assertThat(commands).doesNotContain("ps -A -o PID,UID,NAME")
  }

  @Test
  fun testInjectAndAttach_pmAdbExceptionPropagates() = runTest {
    val injectionManager = createInjectionManager()
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected exception for unconfigured pm command")
    } catch (e: Exception) {
      assertThat(e.message).doesNotContain("Failed to access the application")
      assertThat(e.message).contains("Command not setup")
    }
  }

  @Test
  fun testInjectAndAttach_acceptsColonSuffixCandidate() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection(processName = "$packageName:ui")

    val port = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION).forwardedPort

    assertThat(port).isEqualTo("12345")
  }

  @Test
  fun testResolveAndInject_fullyQualifiedProcessName() = runTest {
    val targetPackage = "com.example.qaviews"
    val processName = "com.example.uiprocess"
    val pid = "4321"
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      TOP_ACTIVITY_SHELL_COMMAND,
      "    APP  UID $pid:$processName/u0a123 (top-activity)\n",
    )
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "ps -o UID= -p $pid", "10123\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pm list packages -U --user 0", "package:$targetPackage uid:10123\n")
    configureSuccessfulInjection(targetPackage = targetPackage, pid = pid, processName = processName)

    val resolvedPackage = resolveTargetPackage(testSession, deviceSerial, requested = null)
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        resolvedPackage,
        composeInspectorOverrideJarPath = null,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        dummyViewInspector,
        tempFileSuffixGenerator = { "test.tmp" },
      )

    val port = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION).forwardedPort

    assertThat(resolvedPackage).isEqualTo(targetPackage)
    assertThat(port).isEqualTo("12345")
    assertThat(fakeSession.deviceServices.shellV2Requests.map { it.command }).contains(attachCommand(targetPackage, pid))
  }

  @Test
  fun testInvalidPackageName_Throws() {
    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        InjectionManager(testSession, deviceSerial, "com.example; id", null, agentPathResolver, dummyJar, dummyPayload, dummyViewInspector)
      }
    assertThat(exception.message).contains("Invalid package name")
  }

  @Test
  fun testLongPackageName_Throws() {
    val longPackageName = "a".repeat(256)
    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        InjectionManager(testSession, deviceSerial, longPackageName, null, agentPathResolver, dummyJar, dummyPayload, dummyViewInspector)
      }
    assertThat(exception.message).contains("Invalid package name")
  }

  @Test
  fun testInvalidSerial_Throws() {
    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        InjectionManager(testSession, "serial; rm -rf /", packageName, null, agentPathResolver, dummyJar, dummyPayload, dummyViewInspector)
      }
    assertThat(exception.message).contains("Invalid serial number")
  }

  @Test
  fun testUnsupportedApi_Throws() = runTest {
    val injectionManager =
      InjectionManager(testSession, deviceSerial, packageName, null, agentPathResolver, dummyJar, dummyPayload, dummyViewInspector)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n27\n")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected IllegalStateException for unsupported API level")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("The UI Inspector only supports API level ${ProtocolConstants.MIN_SUPPORTED_API_LEVEL} and above")
      assertThat(e.message).contains("running API level 27")
    }
  }

  @Test
  fun testFailedToRetrieveSdkVersion_Throws() = runTest {
    val injectionManager =
      InjectionManager(testSession, deviceSerial, packageName, null, agentPathResolver, dummyJar, dummyPayload, dummyViewInspector)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\ninvalid_sdk\n")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected IllegalStateException for failed SDK version retrieval")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("Failed to retrieve device SDK API level")
    }
  }

  @Test
  fun testFailedToRetrieveAbi_Throws() = runTest {
    val injectionManager =
      InjectionManager(testSession, deviceSerial, packageName, null, agentPathResolver, dummyJar, dummyPayload, dummyViewInspector)
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "\n30\n")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected IllegalStateException for failed CPU ABI retrieval")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("Failed to retrieve device CPU ABI")
    }
  }

  @Test
  fun testStageInspectorPayload_Cancelled_cleansUpTempFile() = runTest {
    val dummyPayload = tempFolder.root.toPath().resolve("lib_ui_inspector_payload.jar")
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        dummyViewInspector,
        tempFileSuffixGenerator = { "test.tmp" },
      )
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    val inspectorJar = tempFolder.newFile("my-inspector.jar").toPath()
    val inspector = InspectorMetadata(id = "my.inspector", localJarPath = inspectorJar)

    // Configure rm command for the temp file and simulate cancellation during syncSend
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "rm -f '$STAGING_DIR/my-inspector.$EMPTY_FILE_DIGEST.jar.test.tmp'",
      "",
    )
    testDeviceServices.throwOnSyncSend = true

    var exceptionThrown = false
    try {
      injectionManager.stageInspectorPayload(inspector)
    } catch (e: CancellationException) {
      exceptionThrown = true
    }
    assertThat(exceptionThrown).isTrue()

    // Verify rm command was still invoked despite cancellation
    val rmRequests =
      fakeSession.deviceServices.shellV2Requests.filter {
        it.command == "rm -f '$STAGING_DIR/my-inspector.$EMPTY_FILE_DIGEST.jar.test.tmp'"
      }
    assertThat(rmRequests).isNotEmpty()
  }

  @Test
  fun testInjectAndAttach_multiProcess_topActivitySelection() = runTest {
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        agentPathResolver = agentPathResolver,
        serviceJarPath = dummyJar,
        payloadJarPath = dummyPayload,
        viewInspectorJarPath = dummyViewInspector,
        tempFileSuffixGenerator = { "test.tmp" },
      )
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    // ps returns two PIDs with the package UID: 5678 (secondary process) and 1234 (main UI process).
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "pm list packages -U --user 0 $packageName",
      "package:$packageName uid:10123\n",
    )
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "ps -A -o PID,UID,NAME",
      "PID UID NAME\n5678 10123 $packageName:worker\n1234 10123 $packageName\n",
    )
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "dumpsys activity processes | grep top-activity",
      "  Proc #0: adj=top /F/TOP TRM=0 1234:$packageName/u0a123 (top-activity)\n",
    )
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")
    val setupCmd = installCommand()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, setupCmd, "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, attachCommand(), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix", "${socketName("1234")}\n")

    val port = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION).forwardedPort
    assertThat(port).isEqualTo("12345")
  }

  @Test
  fun testInjectAndAttach_withoutResolutionStackDemand_leavesSettingsUntouched() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()

    injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)

    val settingsCommands = fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("settings ") }
    assertThat(settingsCommands).isEmpty()
  }

  @Test
  fun testInjectAndAttach_globalDebugViewAttributesAlreadyEnabled_skipsWrite() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "1\n$settingsSeparator\nnull\n")

    injectionManager.injectAndAttach(needsDebugViewAttributes = true, mode = InjectionMode.FORCE_FULL_INJECTION)

    val settingsCommands = fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("settings ") }
    assertThat(settingsCommands).containsExactly(readSettingsCmd)
  }

  @Test
  fun testInjectAndAttach_perAppSettingAlreadyNamesPackage_skipsWrite() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "null\n$settingsSeparator\n$packageName\n")

    injectionManager.injectAndAttach(needsDebugViewAttributes = true, mode = InjectionMode.FORCE_FULL_INJECTION)

    val settingsCommands = fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("settings ") }
    assertThat(settingsCommands).containsExactly(readSettingsCmd)
  }

  @Test
  fun testInjectAndAttach_enablesPerAppDebugViewAttributes_andPrintsNotice() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "null\n$settingsSeparator\nnull\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, putSettingsCmd, "")

    val stderr = captureStderr {
      injectionManager.injectAndAttach(needsDebugViewAttributes = true, mode = InjectionMode.FORCE_FULL_INJECTION)
    }

    val commands = fakeSession.deviceServices.shellV2Requests.map { it.command }
    assertThat(commands).contains(putSettingsCmd)
    assertThat(stderr).contains("settings delete global debug_view_attributes_application_package")
    // The pid is captured before the settings flip: the activity relaunch keeps the process alive, and reading the pid first avoids
    // mutating settings when the target is not running.
    val processLookupIndex = commands.indexOf("ps -A -o PID,UID,NAME")
    val settingsReadIndex = commands.indexOf(readSettingsCmd)
    assertThat(processLookupIndex).isAtLeast(0)
    assertThat(processLookupIndex).isLessThan(settingsReadIndex)
  }

  @Test
  fun testInjectAndAttach_settingsReadFails_failsWithoutWriting() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, stdout = "", stderr = "boom", exitCode = 1)

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = true, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected IllegalStateException for failing settings read")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("failed with exit code 1")
    }

    val putCommands = fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("settings put ") }
    assertThat(putCommands).isEmpty()
  }

  @Test
  fun testInjectAndAttach_malformedSettingsReadOutput_failsWithoutWriting() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "garbage without the separator\n")

    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = true, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected IllegalStateException for malformed settings output")
    } catch (e: IllegalStateException) {
      assertThat(e.message).contains("Unexpected output while reading debug-view-attributes settings")
    }

    val putCommands = fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("settings put ") }
    assertThat(putCommands).isEmpty()
  }

  @Test
  fun testInjectAndAttach_settingsPutFails_throwsWithoutNotice() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, readSettingsCmd, "null\n$settingsSeparator\nnull\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, putSettingsCmd, stdout = "", stderr = "denied", exitCode = 1)

    val stderr = captureStderr {
      try {
        injectionManager.injectAndAttach(needsDebugViewAttributes = true, mode = InjectionMode.FORCE_FULL_INJECTION)
        fail("Expected IllegalStateException for failing settings put")
      } catch (e: IllegalStateException) {
        assertThat(e.message).contains("failed with exit code 1")
      }
    }

    assertThat(stderr).doesNotContain("settings delete global")
  }

  @Test
  fun testRemoveAdbForward_killsForwardOnce() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)

    injectionManager.removeAdbForward()
    // A second call is a no-op: the forward was already removed.
    injectionManager.removeAdbForward()

    assertThat(testHostServices.recordedKillForwardCalls).hasSize(1)
    val (device, localSpec) = testHostServices.recordedKillForwardCalls.single()
    assertThat(device.toString()).contains(deviceSerial)
    assertThat(localSpec.toQueryString()).isEqualTo("tcp:12345")
  }

  @Test
  fun testRemoveAdbForward_noopWhenInjectionFailedBeforeForward() = runTest {
    val injectionManager = createInjectionManager()
    // Nothing configured: injectAndAttach fails at the first device query, before any forward is created.
    try {
      injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
      fail("Expected injection to fail")
    } catch (e: Exception) {}

    injectionManager.removeAdbForward()

    assertThat(testHostServices.recordedKillForwardCalls).isEmpty()
  }

  @Test
  fun testDoDumpUi_removesForwardWhenConnectionFails() = runTest {
    configureSuccessfulInjection()
    // Forward to a port that is guaranteed closed, so the CLI's socket connection fails on both attempts: the reconnect attempt (the
    // probed socket is present), then the forced full injection it falls back to. Each attempt must remove its own forward.
    testHostServices.forwardedPort = findClosedPort().toString()

    try {
      doDumpUiWithNoopPrinter()
      fail("Expected connection failure")
    } catch (e: Exception) {}

    assertThat(testHostServices.recordedKillForwardCalls).hasSize(2)
    testHostServices.recordedKillForwardCalls.forEach { (_, localSpec) ->
      assertThat(localSpec.toQueryString()).isEqualTo("tcp:${testHostServices.forwardedPort}")
    }
  }

  @Test
  fun testDoDumpUi_killForwardFailure_preservesPrimaryFailure() = runTest {
    configureSuccessfulInjection()
    testHostServices.forwardedPort = findClosedPort().toString()
    testHostServices.throwOnKillForward = true

    var thrown: Exception? = null
    val stderr = captureStderr {
      try {
        doDumpUiWithNoopPrinter()
        fail("Expected connection failure")
      } catch (e: Exception) {
        thrown = e
      }
    }

    // The connection failure stays the primary error; the cleanup failure is only a warning.
    assertThat(thrown!!.message).doesNotContain("Simulated killForward failure")
    assertThat(stderr).contains("failed to remove adb forward")
  }

  @Test
  fun testRunWithConnectedInspectors_deadReconnectPort_fallsBackToFullInjection() = runBlocking {
    configureSuccessfulInjection()
    // The probed socket is present, but the reconnect attempt's forward targets a closed port: the TCP connect fails, and the run must
    // fall back to one full injection whose forward targets a live scripted agent.
    val liveServer = ServerSocket(0)
    val serverThread = startScriptedLiveAgent(liveServer)
    testHostServices.queuedForwardPorts.addAll(listOf(findClosedPort().toString(), liveServer.localPort.toString()))

    var blockRuns = 0
    val stderr = captureStderr {
      runWithConnectedInspectorsForTest { _, composeInspectorConnected ->
        blockRuns++
        assertThat(composeInspectorConnected).isFalse()
      }
    }
    serverThread.join(5000)
    liveServer.close()

    assertThat(blockRuns).isEqualTo(1)
    assertThat(stderr).contains("injecting a fresh agent")
    // The forced attempt performed the full injection: three base artifact pushes and one attach. The view inspector jar was pushed only
    // by the second attempt — the first one failed before reaching it.
    val pushedPaths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(pushedPaths.filter { it.contains("/lib_ui_inspector_") }).hasSize(3)
    assertThat(pushedPaths.filter { it.contains("/view-inspector.") }).hasSize(1)
    assertThat(fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("cmd activity attach-agent") })
      .hasSize(1)
    // Each attempt created one forward and removed its own.
    assertThat(testHostServices.recordedForwardCalls).hasSize(2)
    assertThat(testHostServices.recordedKillForwardCalls).hasSize(2)
  }

  @Test
  fun testRunWithConnectedInspectors_reconnectDiesBeforeFirstResponse_fallsBack() = runBlocking {
    configureSuccessfulInjection()
    // A server that accepts and closes immediately: the TCP connect succeeds — as it does through an adb forward, whose device-side leg
    // is only created lazily — and the failure appears on the first command round trip.
    val acceptAndClose = ServerSocket(0)
    val acceptThread = thread { runCatching { acceptAndClose.accept().close() } }
    val liveServer = ServerSocket(0)
    val serverThread = startScriptedLiveAgent(liveServer)
    testHostServices.queuedForwardPorts.addAll(listOf(acceptAndClose.localPort.toString(), liveServer.localPort.toString()))

    var blockRuns = 0
    captureStderr { runWithConnectedInspectorsForTest { _, _ -> blockRuns++ } }
    acceptThread.join(5000)
    serverThread.join(5000)
    acceptAndClose.close()
    liveServer.close()

    assertThat(blockRuns).isEqualTo(1)
    // Both attempts reached the view inspector push; only the forced one pushed the base artifacts.
    val pushedPaths = testDeviceServices.recordedSyncSends.map { it.remoteFilePath }
    assertThat(pushedPaths.filter { it.contains("/lib_ui_inspector_") }).hasSize(3)
    assertThat(pushedPaths.filter { it.contains("/view-inspector.") }).hasSize(2)
    assertThat(testHostServices.recordedForwardCalls).hasSize(2)
    assertThat(testHostServices.recordedKillForwardCalls).hasSize(2)
  }

  @Test
  fun testRunWithConnectedInspectors_noFallbackAfterFirstResponse() = runBlocking {
    configureSuccessfulInjection()
    // The reused server answers the first round trip, then the connection dies during Compose version detection: past the first
    // response the server is proven alive, so the failure must propagate instead of triggering a full injection.
    val server = ServerSocket(0)
    val serverThread = thread {
      runCatching {
        server.accept().use { socket ->
          val input = socket.getInputStream()
          val create = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
          writeAgentResponse(
            socket.getOutputStream(),
            UiInspectorProtocol.Response.newBuilder()
              .setCommandId(create.commandId)
              .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
              .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
              .build(),
          )
        }
      }
    }
    testHostServices.queuedForwardPorts.add(server.localPort.toString())

    var thrown: Exception? = null
    try {
      runWithConnectedInspectorsForTest { _, _ -> fail("The block must not run when Compose version detection fails") }
      fail("Expected the connection failure to propagate")
    } catch (e: Exception) {
      thrown = e
    }
    serverThread.join(5000)
    server.close()

    assertThat(thrown).isNotNull()
    // No fallback happened: one forward, no base artifact pushes, no attach.
    assertThat(testHostServices.recordedForwardCalls).hasSize(1)
    assertThat(testDeviceServices.recordedSyncSends.map { it.remoteFilePath }.filter { it.contains("/lib_ui_inspector_") }).isEmpty()
    assertThat(fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("cmd activity attach-agent") })
      .isEmpty()
  }

  @Test
  fun testInjectAndAttach_changedComposeOverride_startsNewServer() = runTest {
    val overrideJar = tempFolder.newFile("compose-override.jar").toPath()
    Files.write(overrideJar, byteArrayOf(1, 2, 3))
    val overrideToken = "1234_" + computeArtifactDigests(dummyAgent, dummyJar, dummyPayload, dummyViewInspector, overrideJar).combined
    assertThat(overrideToken).isNotEqualTo(serverToken("1234"))
    val injectionManager = createInjectionManager(composeInspectorOverrideJarPath = overrideJar)
    configureSuccessfulInjection(token = overrideToken)
    // A server without the override is running (the four-artifact socket), but this run's digest includes the override jar: the probe
    // misses that socket and a fresh server starts under the override-scoped name. The first probe answer is the old socket, the
    // post-attach wait then sees the new one.
    testDeviceServices.queuedShellOutputs["cat /proc/net/unix"] =
      ArrayDeque(listOf("ui_inspector_${serverToken("1234")}\n", "ui_inspector_$overrideToken\n"))

    val result = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.RECONNECT_IF_AVAILABLE)

    assertThat(result).isInstanceOf(InjectionResult.Injected::class.java)
    val commands = fakeSession.deviceServices.shellV2Requests.map { it.command }
    assertThat(commands).contains(attachCommand(token = overrideToken))
    assertThat(testHostServices.recordedForwardCalls.single().third.toQueryString()).isEqualTo("localabstract:ui_inspector_$overrideToken")
    // Only the three base artifacts are staged at injection time; the override is staged later, when the Compose inspector is created.
    assertThat(testDeviceServices.recordedSyncSends.map { it.remoteFilePath }.sorted())
      .containsExactly(*BASE_STAGING_PATHS.map { "$it.test.tmp" }.sorted().toTypedArray())
  }

  @Test
  fun testInjectAndAttach_sameComposeOverride_reconnects() = runTest {
    val overrideJar = tempFolder.newFile("compose-override.jar").toPath()
    Files.write(overrideJar, byteArrayOf(1, 2, 3))
    val overrideToken = "1234_" + computeArtifactDigests(dummyAgent, dummyJar, dummyPayload, dummyViewInspector, overrideJar).combined
    val injectionManager = createInjectionManager(composeInspectorOverrideJarPath = overrideJar)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configurePackageUidAndProcesses()
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix", "ui_inspector_$overrideToken\n")

    val result = injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.RECONNECT_IF_AVAILABLE)

    assertThat(result).isInstanceOf(InjectionResult.Reconnected::class.java)
    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
    val commands = fakeSession.deviceServices.shellV2Requests.map { it.command }
    assertThat(commands.filter { it.startsWith("cmd activity attach-agent") }).isEmpty()
    assertThat(commands.filter { it.startsWith("run-as ") }).isEmpty()
    assertThat(testHostServices.recordedForwardCalls.single().third.toQueryString()).isEqualTo("localabstract:ui_inspector_$overrideToken")
  }

  @Test
  fun testInjectAndAttach_missingComposeOverride_failsBeforeAnyDeviceWork() = runTest {
    val absentJar = tempFolder.root.toPath().resolve("absent.jar")
    val injectionManager = createInjectionManager(composeInspectorOverrideJarPath = absentJar)

    val exception =
      assertThrows(IllegalArgumentException::class.java) {
        runBlocking { injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.RECONNECT_IF_AVAILABLE) }
      }

    assertThat(exception.message).isEqualTo("Specified Compose Inspector JAR does not exist: $absentJar")
    assertThat(fakeSession.deviceServices.shellV2Requests).isEmpty()
    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
    assertThat(testHostServices.recordedForwardCalls).isEmpty()
  }

  @Test
  fun testDoDumpUi_threadsComposeOverridePathToTheInjectionManager() = runTest {
    val noopPrinter =
      object : UiDumpPrinter {
        override fun printDump(uiDump: UiDump) {}
      }
    val capturedOverridePaths = mutableListOf<Path?>()
    // The factory records what it was handed and aborts the run: only the threading is under test.
    class StopAfterCapture : Exception()
    for (cliArgument in listOf("/some/override.jar", null)) {
      try {
        doDumpUi(
          adbSession = testSession,
          serial = deviceSerial,
          packageName = packageName,
          includeAttributes = false,
          includeResolutionStack = false,
          includeSystemComposables = false,
          includeSemantics = false,
          composeInspectorJarPath = cliArgument,
          printer = noopPrinter,
          injectionManagerFactory = { _, _, _, overridePath ->
            capturedOverridePaths.add(overridePath)
            throw StopAfterCapture()
          },
        )
        fail("Expected the capturing factory to abort the run")
      } catch (e: StopAfterCapture) {}
    }

    assertThat(capturedOverridePaths).containsExactly(Paths.get("/some/override.jar"), null).inOrder()
  }

  @Test
  fun testRunWithConnectedInspectors_warmReconnectWithOverride_transfersNothingAndUsesOverridePath() = runBlocking {
    val overrideJar = tempFolder.newFile("compose-override.jar").toPath()
    Files.write(overrideJar, byteArrayOf(1, 2, 3))
    val overrideToken = "1234_" + computeArtifactDigests(dummyAgent, dummyJar, dummyPayload, dummyViewInspector, overrideJar).combined
    configureSuccessfulInjection(token = overrideToken)
    // Both the view inspector jar and the override jar are already correctly staged.
    val viewJarRemotePath = "$STAGING_DIR/view-inspector.$EMPTY_FILE_DIGEST.jar"
    val overrideRemotePath = "$STAGING_DIR/${fileNameWithHash("compose-override.jar", computeContentDigest(overrideJar))}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, statProbeCommand(viewJarRemotePath), "8124 $viewJarRemotePath\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, statProbeCommand(overrideRemotePath), "8124 $overrideRemotePath\n")
    val composeCreateCmd = CompletableDeferred<UiInspectorProtocol.Command>()
    val liveServer = ServerSocket(0)
    val serverThread = thread {
      runCatching {
        liveServer.accept().use { socket ->
          val input = socket.getInputStream()
          val output = socket.getOutputStream()
          val viewCreate = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
          writeAgentResponse(
            output,
            UiInspectorProtocol.Response.newBuilder()
              .setCommandId(viewCreate.commandId)
              .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
              .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
              .build(),
          )
          val getVersion = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
          writeAgentResponse(
            output,
            UiInspectorProtocol.Response.newBuilder()
              .setCommandId(getVersion.commandId)
              .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
              .setGetVersion(
                UiInspectorProtocol.GetVersionResponse.newBuilder().putVersions(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.6.0")
              )
              .build(),
          )
          val composeCreate = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
          composeCreateCmd.complete(composeCreate)
          writeAgentResponse(
            output,
            UiInspectorProtocol.Response.newBuilder()
              .setCommandId(composeCreate.commandId)
              .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
              .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
              .build(),
          )
        }
      }
    }
    testHostServices.queuedForwardPorts.add(liveServer.localPort.toString())

    var composeConnected = false
    runWithConnectedInspectorsForTest(composeInspectorOverrideJarPath = overrideJar) { _, connected -> composeConnected = connected }
    serverThread.join(5000)
    liveServer.close()

    assertThat(composeConnected).isTrue()
    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
    assertThat(composeCreateCmd.await().createInspector.dexPath).isEqualTo(overrideRemotePath)
  }

  @Test
  fun testRunWithConnectedInspectors_reconnectWithMatchingViewJar_transfersNothing() = runBlocking {
    configureSuccessfulInjection()
    // The probe finds the running server and the staged view inspector jar already matches: the whole run transfers no file at all.
    val viewJarRemotePath = "$STAGING_DIR/view-inspector.$EMPTY_FILE_DIGEST.jar"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, statProbeCommand(viewJarRemotePath), "8124 $viewJarRemotePath\n")
    val liveServer = ServerSocket(0)
    val serverThread = startScriptedLiveAgent(liveServer)
    testHostServices.queuedForwardPorts.add(liveServer.localPort.toString())

    var blockRan = false
    runWithConnectedInspectorsForTest { _, _ -> blockRan = true }
    serverThread.join(5000)
    liveServer.close()

    assertThat(blockRan).isTrue()
    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
    assertThat(fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("cmd activity attach-agent") })
      .isEmpty()
  }

  @Test
  fun testRunWithConnectedInspectors_agentCrashIsNotRetried() = runBlocking {
    configureSuccessfulInjection()
    // The reused server reports an explicit crash instead of answering the first command: the server is alive, so the crash must
    // propagate instead of triggering a full injection.
    val server = ServerSocket(0)
    val serverThread = thread {
      runCatching {
        server.accept().use { socket ->
          FramingProtocol.readMessage(socket.getInputStream())
          val crash =
            UiInspectorProtocol.AgentMessage.newBuilder()
              .setEvent(
                UiInspectorProtocol.Event.newBuilder()
                  .setCrash(UiInspectorProtocol.CrashEvent.newBuilder().setErrorMessage("boom").setStackTrace("stack"))
              )
              .build()
          FramingProtocol.writeMessage(socket.getOutputStream(), crash.toByteArray())
        }
      }
    }
    testHostServices.queuedForwardPorts.add(server.localPort.toString())

    var thrown: Exception? = null
    try {
      runWithConnectedInspectorsForTest { _, _ -> fail("The block must not run when the agent crashes") }
      fail("Expected the crash to propagate")
    } catch (e: Exception) {
      thrown = e
    }
    serverThread.join(5000)
    server.close()

    assertThat(thrown).isInstanceOf(InspectorCrashException::class.java)
    assertThat(testHostServices.recordedForwardCalls).hasSize(1)
    assertThat(testDeviceServices.recordedSyncSends.map { it.remoteFilePath }.filter { it.contains("/lib_ui_inspector_") }).isEmpty()
  }

  @Test
  fun testRunWithConnectedInspectors_freshlyInjectedServerFailure_isNotRetried() = runBlocking {
    configureSuccessfulInjection()
    // The probe finds no socket, so the first attempt performs the full injection; its post-attach wait then sees the socket. The forward
    // targets a closed port, so the freshly injected server fails its connect — that failure must propagate, never retry.
    testDeviceServices.queuedShellOutputs["cat /proc/net/unix"] = ArrayDeque(listOf("", "${socketName("1234")}\n"))
    testHostServices.forwardedPort = findClosedPort().toString()

    var thrown: Exception? = null
    val stderr = captureStderr {
      try {
        runWithConnectedInspectorsForTest { _, _ -> fail("The block must not run when the connection fails") }
        fail("Expected the connection failure to propagate")
      } catch (e: Exception) {
        thrown = e
      }
    }

    assertThat(thrown).isNotNull()
    assertThat(stderr).doesNotContain("injecting a fresh agent")
    assertThat(testHostServices.recordedForwardCalls).hasSize(1)
    assertThat(fakeSession.deviceServices.shellV2Requests.map { it.command }.filter { it.startsWith("cmd activity attach-agent") })
      .hasSize(1)
  }

  @Test
  fun testRunWithConnectedInspectors_probeReadFailure_propagates() = runBlocking {
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configurePackageUidAndProcesses()
    // A failing /proc/net/unix read is not authoritative absence: it must fail the run, not silently fall through to a full injection.
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix", stdout = "", stderr = "boom", exitCode = 1)

    var thrown: Exception? = null
    try {
      runWithConnectedInspectorsForTest { _, _ -> fail("The block must not run when the probe fails") }
      fail("Expected the probe failure to propagate")
    } catch (e: Exception) {
      thrown = e
    }

    assertThat(thrown!!.message).contains("failed with exit code 1")
    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
    assertThat(testHostServices.recordedForwardCalls).isEmpty()
  }

  @Test
  fun testRunWithConnectedInspectors_cancellation_isNotRetried() = runBlocking {
    configureSuccessfulInjection()
    // A server that receives the first command and never answers: cancelling the caller while it awaits must propagate as cancellation,
    // not be classified as a stale server.
    val commandReceived = CompletableDeferred<Unit>()
    val server = ServerSocket(0)
    val serverThread = thread {
      runCatching {
        server.accept().use { socket ->
          FramingProtocol.readMessage(socket.getInputStream())
          commandReceived.complete(Unit)
          socket.getInputStream().read()
        }
      }
    }
    testHostServices.queuedForwardPorts.add(server.localPort.toString())

    val stderr = captureStderr {
      val job = launch { runWithConnectedInspectorsForTest { _, _ -> fail("The block must not run when the run is cancelled") } }
      commandReceived.await()
      job.cancelAndJoin()
    }
    server.close()
    serverThread.join(5000)

    assertThat(stderr).doesNotContain("injecting a fresh agent")
    assertThat(testHostServices.recordedForwardCalls).hasSize(1)
    // The cancelled attempt still removed its own forward.
    assertThat(testHostServices.recordedKillForwardCalls).hasSize(1)
  }

  @Test
  fun testDoDumpUi_emptyRootsAfterFallback_keepsStaleDiagnostic() = runBlocking {
    configureSuccessfulInjection()
    // The reconnect attempt dies on a closed port; the forced attempt reaches a live agent whose dump has no windows. The user-facing
    // empty-roots error must keep the stale-server failure as a suppressed diagnostic.
    val emptyDump =
      ViewInspectorProtocol.Response.newBuilder().setDumpViewsResponse(ViewInspectorProtocol.DumpViewsResponse.getDefaultInstance()).build()
    val server = ServerSocket(0)
    val serverThread = thread {
      runCatching {
        server.accept().use { socket ->
          val input = socket.getInputStream()
          val output = socket.getOutputStream()
          val create = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
          writeAgentResponse(
            output,
            UiInspectorProtocol.Response.newBuilder()
              .setCommandId(create.commandId)
              .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
              .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
              .build(),
          )
          val getVersion = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
          writeAgentResponse(
            output,
            UiInspectorProtocol.Response.newBuilder()
              .setCommandId(getVersion.commandId)
              .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
              .setGetVersion(UiInspectorProtocol.GetVersionResponse.getDefaultInstance())
              .build(),
          )
          val dumpCmd = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
          writeAgentResponse(
            output,
            UiInspectorProtocol.Response.newBuilder()
              .setCommandId(dumpCmd.commandId)
              .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
              .setInspectorMessage(
                UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                  .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
                  .setPayload(ByteString.copyFrom(emptyDump.toByteArray()))
              )
              .build(),
          )
        }
      }
    }
    testHostServices.queuedForwardPorts.addAll(listOf(findClosedPort().toString(), server.localPort.toString()))

    var thrown: Exception? = null
    captureStderr {
      try {
        doDumpUiWithNoopPrinter()
        fail("Expected the empty-roots failure to propagate")
      } catch (e: Exception) {
        thrown = e
      }
    }
    serverThread.join(5000)
    server.close()

    assertThat(thrown!!.message).contains("No active window roots found")
    assertThat(thrown!!.suppressed).hasLength(1)
  }

  /** Runs [runWithConnectedInspectors] with all facets off, routing [injectionManagerFactory] to this test's dummy artifact paths. */
  private suspend fun runWithConnectedInspectorsForTest(
    composeInspectorOverrideJarPath: Path? = null,
    block: suspend (CommandSender, Boolean) -> Unit,
  ) {
    runWithConnectedInspectors(
      adbSession = testSession,
      serial = deviceSerial,
      packageName = packageName,
      needsDebugViewAttributes = false,
      composeInspectorOverrideJarPath = composeInspectorOverrideJarPath,
      injectionManagerFactory = { session, serial, pkg, overridePath ->
        InjectionManager(
          session,
          serial,
          pkg,
          composeInspectorOverrideJarPath = overridePath,
          agentPathResolver,
          dummyJar,
          dummyPayload,
          dummyViewInspector,
          tempFileSuffixGenerator = { "test.tmp" },
        )
      },
      block = block,
    )
  }

  /** Starts a loopback agent that answers View inspector creation with success and Compose version detection with "no Compose". */
  private fun startScriptedLiveAgent(serverSocket: ServerSocket): Thread = thread {
    runCatching {
      serverSocket.accept().use { socket ->
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val create = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
        writeAgentResponse(
          output,
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(create.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
            .build(),
        )
        val getVersion = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
        writeAgentResponse(
          output,
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(getVersion.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setGetVersion(UiInspectorProtocol.GetVersionResponse.getDefaultInstance())
            .build(),
        )
      }
    }
  }

  private fun writeAgentResponse(output: OutputStream, response: UiInspectorProtocol.Response) {
    val agentMessage = UiInspectorProtocol.AgentMessage.newBuilder().setResponse(response).build()
    FramingProtocol.writeMessage(output, agentMessage.toByteArray())
  }

  /**
   * Runs a plain [doDumpUi] with all facets off and a printer that discards output, routing [injectionManagerFactory] to this test's dummy
   * artifact paths for the duration of the call.
   */
  private suspend fun doDumpUiWithNoopPrinter() {
    val noopPrinter =
      object : UiDumpPrinter {
        override fun printDump(uiDump: UiDump) {}
      }
    doDumpUi(
      adbSession = testSession,
      serial = deviceSerial,
      packageName = packageName,
      includeAttributes = false,
      includeResolutionStack = false,
      includeSystemComposables = false,
      includeSemantics = false,
      composeInspectorJarPath = null,
      printer = noopPrinter,
      injectionManagerFactory = { session, serial, pkg, overridePath ->
        InjectionManager(
          session,
          serial,
          pkg,
          composeInspectorOverrideJarPath = overridePath,
          agentPathResolver,
          dummyJar,
          dummyPayload,
          dummyViewInspector,
          tempFileSuffixGenerator = { "test.tmp" },
        )
      },
    )
  }

  /** Returns a local TCP port that nothing is listening on. */
  private fun findClosedPort(): Int = ServerSocket(0).use { it.localPort }

  @Test
  fun testRemoveAdbForward_killFailure_warnsInsteadOfThrowing() = runTest {
    val injectionManager = createInjectionManager()
    configureSuccessfulInjection()
    injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)
    testHostServices.throwOnKillForward = true

    val stderr = captureStderr { injectionManager.removeAdbForward() }

    assertThat(stderr).contains("failed to remove adb forward")
    assertThat(testHostServices.recordedKillForwardCalls).isEmpty()
  }

  private fun createInjectionManager(composeInspectorOverrideJarPath: Path? = null) =
    InjectionManager(
      testSession,
      deviceSerial,
      packageName,
      composeInspectorOverrideJarPath = composeInspectorOverrideJarPath,
      agentPathResolver,
      dummyJar,
      dummyPayload,
      dummyViewInspector,
      tempFileSuffixGenerator = { "test.tmp" },
    )

  private fun configurePackageUidAndProcesses(
    targetPackage: String = packageName,
    packageUid: Int = 10123,
    processOutput: String = "PID UID NAME\n1234 $packageUid $targetPackage\n",
  ) {
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "pm list packages -U --user 0 $targetPackage",
      "package:$targetPackage uid:$packageUid\n",
    )
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "ps -A -o PID,UID,NAME", processOutput)
  }

  /** Configures every shell command of the happy-path injection flow, except the debug-view-attributes settings commands. */
  private fun configureSuccessfulInjection(
    targetPackage: String = packageName,
    pid: String = "1234",
    processName: String = targetPackage,
    token: String = serverToken(pid),
    socketGrepOutput: String = "ui_inspector_$token\n",
  ) {
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configurePackageUidAndProcesses(targetPackage, processOutput = "PID UID NAME\n$pid 10123 $processName\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $targetPackage pwd", "/data/data/$targetPackage\n")
    val digests = computeArtifactDigests(dummyAgent, dummyJar, dummyPayload, dummyViewInspector)
    configureStagedPush("lib_ui_inspector_agent.so", digests.agentBinary)
    configureStagedPush("lib_ui_inspector_service.jar", digests.serviceJar)
    configureStagedPush("lib_ui_inspector_payload.jar", digests.payloadJar)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, installCommand(targetPackage), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, attachCommand(targetPackage, pid, token), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix", socketGrepOutput)
  }

  /** Configures the exact staged-push shell commands (sweep + guarded rename, and the failure-path temp cleanup) for one artifact. */
  private fun configureStagedPush(baseName: String, digest: String) {
    val target = "$STAGING_DIR/${fileNameWithHash(baseName, digest)}"
    val staleVersionsPattern = "$STAGING_DIR/${fileNameWithHash(baseName, CONTENT_DIGEST_PATTERN)}"
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "rm -f $staleVersionsPattern && test ! -d '$target' && mv -f '$target.test.tmp' '$target'",
      "",
    )
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "rm -f '$target.test.tmp'", "")
  }

  /** The digest-named staging paths of the three dummy base artifacts, computed from their current contents in batch order. */
  private fun baseStagingPaths(): List<String> {
    val digests = computeArtifactDigests(dummyAgent, dummyJar, dummyPayload, dummyViewInspector)
    return listOf(
      "$STAGING_DIR/${fileNameWithHash("lib_ui_inspector_agent.so", digests.agentBinary)}",
      "$STAGING_DIR/${fileNameWithHash("lib_ui_inspector_service.jar", digests.serviceJar)}",
      "$STAGING_DIR/${fileNameWithHash("lib_ui_inspector_payload.jar", digests.payloadJar)}",
    )
  }

  /** The exact install command the production flow issues for the dummy artifacts' current contents. */
  private fun installCommand(targetPackage: String = packageName): String {
    val digests = computeArtifactDigests(dummyAgent, dummyJar, dummyPayload, dummyViewInspector)
    val stagingPaths = baseStagingPaths()
    return buildInstallCommand(
      packageName = targetPackage,
      agentStagePath = stagingPaths[0],
      serviceJarStagePath = stagingPaths[1],
      payloadJarStagePath = stagingPaths[2],
      serviceJarName = fileNameWithHash("lib_ui_inspector_service.jar", digests.serviceJar),
      payloadJarName = fileNameWithHash("lib_ui_inspector_payload.jar", digests.payloadJar),
      tempSuffix = "test.tmp",
    )
  }

  /** The exact attach command the production flow issues for the dummy artifacts' current contents. */
  private fun attachCommand(
    targetPackage: String = packageName,
    pid: String = "1234",
    token: String = serverToken(pid),
    appDataDir: String = "/data/data/$targetPackage",
  ): String {
    val digests = computeArtifactDigests(dummyAgent, dummyJar, dummyPayload, dummyViewInspector)
    val serviceJarName = fileNameWithHash("lib_ui_inspector_service.jar", digests.serviceJar)
    val payloadJarName = fileNameWithHash("lib_ui_inspector_payload.jar", digests.payloadJar)
    return "cmd activity attach-agent $pid \"$appDataDir/lib_ui_inspector_agent.so=" +
      "$appDataDir/$serviceJarName;$appDataDir/$payloadJarName;$token\""
  }

  /** Runs [block] with [System.err] redirected and returns everything it printed. */
  private inline fun captureStderr(block: () -> Unit): String {
    val originalErr = System.err
    val buffer = ByteArrayOutputStream()
    System.setErr(PrintStream(buffer))
    try {
      block()
    } finally {
      System.setErr(originalErr)
    }
    return buffer.toString()
  }
}
