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

package com.android.tools.ui.inspector.client

import com.android.adblib.DeviceInfo
import com.android.adblib.DeviceList
import com.android.adblib.DevicePropertyNames
import com.android.adblib.DeviceSelector
import com.android.adblib.DeviceState
import com.android.adblib.testing.FakeAdbSession
import com.android.tools.ui.inspector.RecordingLogger
import com.android.tools.ui.inspector.TestAdbDeviceServices
import com.android.tools.ui.inspector.TestAdbHostServices
import com.android.tools.ui.inspector.TestAdbSession
import com.android.tools.ui.inspector.common.FramingProtocol
import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.deploy.ArtifactDownloader
import com.android.tools.ui.inspector.deploy.CONTENT_DIGEST_PATTERN
import com.android.tools.ui.inspector.deploy.InjectionManager
import com.android.tools.ui.inspector.deploy.InjectionMode
import com.android.tools.ui.inspector.deploy.buildInstallCommand
import com.android.tools.ui.inspector.deploy.computeContentDigest
import com.android.tools.ui.inspector.deploy.fileNameWithHash
import com.android.tools.ui.inspector.fetchParameter
import com.android.tools.ui.inspector.fetchUiDump
import com.android.tools.ui.inspector.model.ParameterReference
import com.android.tools.ui.inspector.model.UiNode
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import com.android.tools.ui.inspector.statProbeCommand
import com.google.common.base.Throwables
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import java.net.ServerSocket
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import layoutinspector.compose.inspection.LayoutInspectorComposeProtocol
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Content digest of an empty file: first 12 hex of its SHA-256. Every dummy base artifact these tests create is empty. */
private const val EMPTY_FILE_DIGEST = "e3b0c44298fc"

/** The device staging directory every artifact is pushed into under its digest-carrying name. */
private const val STAGING_DIR = "/data/local/tmp/ui-inspector"

/** The ART Tooling agent class the attach command names, loaded from the payload dex. */
private const val AGENT_CLASS_NAME = "com.android.tools.ui.inspector.payload.InspectorLauncher"

private const val EMPTY_AGENT_NAME = "libarttooling_agent.$EMPTY_FILE_DIGEST.so"
private const val EMPTY_LIBRARY_DEX_NAME = "libarttooling.$EMPTY_FILE_DIGEST.jar"
private const val EMPTY_PAYLOAD_JAR_NAME = "lib_ui_inspector_payload.$EMPTY_FILE_DIGEST.jar"

/** The exact install command the production flow issues when all three base artifacts are empty files. */
private fun emptyArtifactsInstallCommand(packageName: String): String =
  buildInstallCommand(
    packageName = packageName,
    agentStagePath = "$STAGING_DIR/$EMPTY_AGENT_NAME",
    libraryDexStagePath = "$STAGING_DIR/$EMPTY_LIBRARY_DEX_NAME",
    payloadJarStagePath = "$STAGING_DIR/$EMPTY_PAYLOAD_JAR_NAME",
    agentName = EMPTY_AGENT_NAME,
    libraryDexName = EMPTY_LIBRARY_DEX_NAME,
    payloadJarName = EMPTY_PAYLOAD_JAR_NAME,
    tempSuffix = "test.tmp",
  )

/** The exact attach command the production flow issues when all three base artifacts are empty files. */
private fun emptyArtifactsAttachCommand(packageName: String, serverToken: String): String =
  "cmd activity attach-agent 1234 \"/data/data/$packageName/$EMPTY_AGENT_NAME=" +
    "/data/data/$packageName/$EMPTY_LIBRARY_DEX_NAME;/data/data/$packageName/$EMPTY_PAYLOAD_JAR_NAME;$AGENT_CLASS_NAME;$serverToken\""

class ComposeInspectorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun writeResponse(output: java.io.OutputStream, response: UiInspectorProtocol.Response) {
    val agentMessage = UiInspectorProtocol.AgentMessage.newBuilder().setResponse(response).build()
    FramingProtocol.writeMessage(output, agentMessage.toByteArray())
  }

  private val logger = RecordingLogger()
  private val deviceSerial = "123"
  private val packageName = "com.example"

  /** The attach server token for pid 1234 with the four dummy artifacts, all empty files (see [ShippedArtifactsDigestTest]). */
  private val serverToken = "1234_66687aadf862"

  @Test
  fun testGetComposeArtifactId_legacyAndKmpVersions() {
    // Legacy (Pre-KMP) versions should return "ui"
    assertThat(getComposeArtifactId("1.4.3")).isEqualTo("ui")
    assertThat(getComposeArtifactId("1.0.0")).isEqualTo("ui")
    assertThat(getComposeArtifactId("0.9.0")).isEqualTo("ui")

    // Modern (KMP) versions should return "ui-android"
    assertThat(getComposeArtifactId("1.5.0")).isEqualTo("ui-android")
    assertThat(getComposeArtifactId("1.6.0-rc01")).isEqualTo("ui-android")
    assertThat(getComposeArtifactId("2.0.0-alpha01")).isEqualTo("ui-android")

    // Malformed/empty versions should gracefully fallback to "ui"
    assertThat(getComposeArtifactId("invalid")).isEqualTo("ui")
    assertThat(getComposeArtifactId("")).isEqualTo("ui")
    assertThat(getComposeArtifactId("1")).isEqualTo("ui")
  }

  @Test
  fun testCreateComposeInspector_endToEndOrchestration() = runBlocking {
    // 1. Setup Background Server to simulate JVM agent
    val serverSocket = ServerSocket(0)
    val serverPort = serverSocket.localPort

    val versionCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val createCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val serverJob = Job()
    val testScope = CoroutineScope(Dispatchers.Default + serverJob)

    testScope.launch {
      serverSocket.accept().use { socket ->
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // A. Handle GetVersionCommand
        val cmdBytes1 = FramingProtocol.readMessage(input)
        val cmd1 = UiInspectorProtocol.Command.parseFrom(cmdBytes1)
        versionCmdReceived.complete(cmd1)

        val versionResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd1.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setGetVersion(
              UiInspectorProtocol.GetVersionResponse.newBuilder().putVersions(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.6.0")
            )
            .build()
        writeResponse(output, versionResponse)

        // B. Handle CreateInspectorCommand
        val cmdBytes2 = FramingProtocol.readMessage(input)
        val cmd2 = UiInspectorProtocol.Command.parseFrom(cmdBytes2)
        createCmdReceived.complete(cmd2)

        val createResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd2.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
            .build()
        writeResponse(output, createResponse)
      }
    }

    // 2. Setup Mock adbSession / InjectionManager
    val fakeSession = FakeAdbSession()
    val testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    val testHostServices = TestAdbHostServices(fakeSession.hostServices)
    val testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val dummyAgent = tempFolder.newFile("libarttooling_agent.so").toPath()
    val dummyJar = tempFolder.newFile("libarttooling.jar").toPath()
    val dummyPayload = tempFolder.newFile("lib_ui_inspector_payload.jar").toPath()
    val dummyViewInspector = tempFolder.newFile("view-inspector.jar").toPath()

    val agentPathResolver = { abi: String -> dummyAgent }
    configureAtomicMoveCommands(fakeSession, deviceSelector)
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        logger,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        dummyViewInspector,
        tempFileSuffixGenerator = { "test.tmp" },
      )

    // Perform the full injection that precedes inspector creation in production
    configureUidCommands(fakeSession, deviceSelector, packageName)

    fakeSession.deviceServices.configureShellCommand(deviceSelector, emptyArtifactsInstallCommand(packageName), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, emptyArtifactsAttachCommand(packageName, serverToken), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix", "ui_inspector_$serverToken\n")
    injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)

    // 3. Execute E2E Orchestrator with dynamic lambda jar resolution mock
    CommandSender.connect("127.0.0.1", serverPort, this).use { commandSender ->
      createComposeInspector(
        commandSender = commandSender,
        injectionManager = injectionManager,
        logger = logger,
        resolveJar = {
          val fixedJar = tempFolder.newFile("compose-inspector.jar")
          fixedJar.writeText("fake pre-compiled compose dex classes")
          fixedJar
        },
      )
    }

    // 5. Assertions
    // A. Check version detection command
    val vCmd = versionCmdReceived.await()
    assertThat(vCmd.getVersion.libraryIdsList).containsExactly(ProtocolConstants.COMPOSE_UI_LIBRARY_ID)

    // B. Check inspector jar file was pushed to simulated device
    val remoteFilePushed =
      testDeviceServices.recordedSyncSends.any { it.remoteFilePath == "$STAGING_DIR/compose-inspector.665e173983c8.jar.test.tmp" }
    assertThat(remoteFilePushed).isTrue()

    // C. Check CreateInspectorCommand parameters
    val cCmd = createCmdReceived.await()
    assertThat(cCmd.createInspector.inspectorId).isEqualTo(ProtocolConstants.COMPOSE_INSPECTOR_ID)
    assertThat(cCmd.createInspector.dexPath).isEqualTo("$STAGING_DIR/compose-inspector.665e173983c8.jar")

    // Cleanup
    testScope.cancel()
    serverSocket.close()
  }

  @Test
  fun testCreateComposeInspector_matchingStagedJar_skipsTransferButStillCreates() = runBlocking {
    // Loopback agent answering version detection and inspector creation.
    val serverSocket = ServerSocket(0)
    val serverPort = serverSocket.localPort
    val createCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val serverJob = Job()
    val testScope = CoroutineScope(Dispatchers.Default + serverJob)
    testScope.launch {
      serverSocket.accept().use { socket ->
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val versionCmd = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
        writeResponse(
          output,
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(versionCmd.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setGetVersion(
              UiInspectorProtocol.GetVersionResponse.newBuilder().putVersions(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.6.0")
            )
            .build(),
        )
        val createCmd = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
        createCmdReceived.complete(createCmd)
        writeResponse(
          output,
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(createCmd.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
            .build(),
        )
      }
    }

    val fakeSession = FakeAdbSession()
    val testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    val testHostServices = TestAdbHostServices(fakeSession.hostServices)
    val testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)
    testDeviceServices.session = testSession
    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)

    // The staged copy of the resolved Compose jar already matches its content and has the exact staged mode.
    val fixedJar = tempFolder.newFile("compose-inspector.jar")
    fixedJar.writeText("fake pre-compiled compose dex classes")
    val stagedJarPath = "$STAGING_DIR/${fileNameWithHash("compose-inspector.jar", computeContentDigest(fixedJar.toPath()))}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, statProbeCommand(stagedJarPath), "8124 $stagedJarPath\n")

    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        logger,
        { _: String -> tempFolder.newFile("unused-agent.so").toPath() },
        tempFolder.newFile("unused-library.jar").toPath(),
        tempFolder.newFile("unused-payload.jar").toPath(),
        tempFolder.newFile("unused-view-inspector.jar").toPath(),
        tempFileSuffixGenerator = { "test.tmp" },
      )

    val connected =
      CommandSender.connect("127.0.0.1", serverPort, this).use { commandSender ->
        createComposeInspector(
          commandSender = commandSender,
          injectionManager = injectionManager,
          logger = logger,
          resolveJar = { fixedJar },
        )
      }

    assertThat(connected).isTrue()
    // No transfer happened, yet the agent was still asked to create the inspector from the staged path.
    assertThat(testDeviceServices.recordedSyncSends).isEmpty()
    val createCmd = createCmdReceived.await()
    assertThat(createCmd.createInspector.inspectorId).isEqualTo(ProtocolConstants.COMPOSE_INSPECTOR_ID)
    assertThat(createCmd.createInspector.dexPath).isEqualTo(stagedJarPath)

    testScope.cancel()
    serverSocket.close()
  }

  @Test
  fun testViewInspectorDump_GetComposablesAndMerge() = runBlocking {
    // 1. Setup Background Server to simulate dynamic responses
    val serverSocket = ServerSocket(0)
    val serverPort = serverSocket.localPort

    val dumpCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val composeCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val serverJob = Job()
    val testScope = CoroutineScope(Dispatchers.Default + serverJob)

    testScope.launch {
      serverSocket.accept().use { socket ->
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // A. Handle GetVersionCommand
        val cmdBytes1 = FramingProtocol.readMessage(input)
        val cmd1 = UiInspectorProtocol.Command.parseFrom(cmdBytes1)
        val versionResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd1.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setGetVersion(
              UiInspectorProtocol.GetVersionResponse.newBuilder().putVersions(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.6.0")
            )
            .build()
        writeResponse(output, versionResponse)

        // B. Handle CreateInspectorCommand
        val cmdBytes2 = FramingProtocol.readMessage(input)
        val cmd2 = UiInspectorProtocol.Command.parseFrom(cmdBytes2)
        val createResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd2.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
            .build()
        writeResponse(output, createResponse)

        // C. Handle ViewInspector Message (DumpViewsCommand)
        val cmdBytes3 = FramingProtocol.readMessage(input)
        val cmd3 = UiInspectorProtocol.Command.parseFrom(cmdBytes3)
        dumpCmdReceived.complete(cmd3)

        // Build a mock View tree mirroring a real hosted-view capture:
        // FrameLayout (1000) -> AndroidComposeView (2000) -> AndroidViewsHandler (2100) -> ViewFactoryHolder (2200) -> TextView (2300)
        val viewNode1 =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
            .setId(1000)
            .setClassName(1) // index for FrameLayout
            .setBounds(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                .setX(0)
                .setY(0)
                .setWidth(1080)
                .setHeight(1920)
            )
            .addChildren(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
                .setId(2000)
                .setClassName(2) // index for AndroidComposeView
                .setBounds(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                    .setX(0)
                    .setY(0)
                    .setWidth(1080)
                    .setHeight(1920)
                )
                .addChildren(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
                    .setId(2100)
                    .setClassName(3) // index for AndroidViewsHandler
                    .addChildren(
                      com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
                        .setId(2200)
                        .setClassName(4) // index for ViewFactoryHolder
                        .addChildren(
                          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
                            .setId(2300)
                            .setClassName(5) // index for TextView
                        )
                    )
                )
            )
            .build()

        val viewResponse =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Response.newBuilder()
            .setDumpViewsResponse(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.DumpViewsResponse.newBuilder()
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(1)
                    .setValue("android.widget.FrameLayout")
                )
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(2)
                    .setValue("androidx.compose.ui.platform.AndroidComposeView")
                )
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(3)
                    .setValue("androidx.compose.ui.platform.AndroidViewsHandler")
                )
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(4)
                    .setValue("androidx.compose.ui.viewinterop.ViewFactoryHolder")
                )
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(5)
                    .setValue("android.widget.TextView")
                )
                .addWindows(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.WindowInfo.newBuilder().setRoot(viewNode1)
                )
            )
            .build()

        val viewMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd3.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(viewResponse.toByteArray()))
            )
            .build()
        writeResponse(output, viewMsgResponse)

        // D. Handle COMPOSE_COMMAND (GetComposablesCommand)
        val cmdBytes4 = FramingProtocol.readMessage(input)
        val cmd4 = UiInspectorProtocol.Command.parseFrom(cmdBytes4)
        composeCmdReceived.complete(cmd4)

        // Build a mock Compose tree: Column (ID 3000) -> [Text (ID 4000), AndroidView (ID 5000, hosting View 2200)]
        val composeRoot =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableRoot.newBuilder()
            .setViewId(2000)
            .addNodes(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                .setId(3000)
                .setName(1) // index for Column
                .setBounds(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                    .setLayout(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                        .setX(0)
                        .setY(0)
                        .setW(1080)
                        .setH(200)
                    )
                )
                .addChildren(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                    .setId(4000)
                    .setName(2) // index for Text
                    .setBounds(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                        .setLayout(
                          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                            .setX(10)
                            .setY(10)
                            .setW(100)
                            .setH(50)
                        )
                    )
                )
                .addChildren(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                    .setId(5000)
                    .setName(3) // index for AndroidView
                    .setViewId(2200) // References the ViewFactoryHolder hosted under the AndroidViewsHandler
                )
            )
            .build()

        val composeResponse =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
            .setGetComposablesResponse(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(1).setStr("Column")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("Text")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("AndroidView")
                )
                .addRoots(composeRoot)
            )
            .build()

        val composeMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd4.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(composeResponse.toByteArray()))
            )
            .build()
        writeResponse(output, composeMsgResponse)
      }
    }

    // 2. Setup Mock adbSession / InjectionManager
    val fakeSession = FakeAdbSession()
    val testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    val testHostServices = TestAdbHostServices(fakeSession.hostServices)
    val testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configureUidCommands(fakeSession, deviceSelector, packageName)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val dummyAgent = tempFolder.newFile("libarttooling_agent.so").toPath()
    val dummyJar = tempFolder.newFile("libarttooling.jar").toPath()
    val dummyPayload = tempFolder.newFile("lib_ui_inspector_payload.jar").toPath()
    val dummyViewInspector = tempFolder.newFile("view-inspector.jar").toPath()

    val agentPathResolver = { abi: String -> dummyAgent }
    configureAtomicMoveCommands(fakeSession, deviceSelector)
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        logger,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        dummyViewInspector,
        tempFileSuffixGenerator = { "test.tmp" },
      )

    // Perform the full injection that precedes inspector creation in production
    fakeSession.deviceServices.configureShellCommand(deviceSelector, emptyArtifactsInstallCommand(packageName), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, emptyArtifactsAttachCommand(packageName, serverToken), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix", "ui_inspector_$serverToken\n")
    injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)

    val uiDump =
      CommandSender.connect("127.0.0.1", serverPort, this).use { commandSender ->
        // Inject Compose Inspector with a mock resolveJar lambda returning a dummy file
        val composeInspectorConnected =
          createComposeInspector(
            commandSender = commandSender,
            injectionManager = injectionManager,
            logger = logger,
            resolveJar = {
              val fixedJar = tempFolder.newFile("compose-inspector.jar")
              fixedJar.writeText("fake pre-compiled compose dex classes")
              fixedJar
            },
          )
        assertThat(composeInspectorConnected).isTrue()

        fetchUiDump(
          commandSender = commandSender,
          includeAttributes = false,
          includeResolutionStack = false,
          composeInspectorConnected = composeInspectorConnected,
          includeSemantics = false,
          logger = logger,
        )
      }

    // 4. Assertions
    // The device is never asked to filter: the facet is applied by stripping system composables on the host.
    val sentComposeCmd =
      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Command.parseFrom(
        composeCmdReceived.await().inspectorMessage.payload
      )
    assertThat(sentComposeCmd.getComposablesCommand.skipSystemComposables).isFalse()

    assertThat(uiDump.windows).hasSize(1)
    val viewRoot = uiDump.windows.single().root
    assertThat(viewRoot.className).isEqualTo("android.widget.FrameLayout")
    val composeView = viewRoot.children[0]
    assertThat(composeView.className).isEqualTo("androidx.compose.ui.platform.AndroidComposeView")
    // The emptied AndroidViewsHandler is dropped: the Compose root is the only remaining child.
    assertThat(composeView.children).hasSize(1)
    val column = composeView.children[0]
    assertThat(column.className).isEqualTo("Column")
    val text = column.children[0]
    assertThat(text.className).isEqualTo("Text")
    // The hosted subtree (holder + payload) is grafted under the AndroidView composable.
    val androidView = column.children[1] as UiNode.ComposeNode
    assertThat(androidView.className).isEqualTo("AndroidView")
    val holder = androidView.children.single() as UiNode.ViewNode
    assertThat(holder.className).isEqualTo("androidx.compose.ui.viewinterop.ViewFactoryHolder")
    val payload = holder.children.single() as UiNode.ViewNode
    assertThat(payload.className).isEqualTo("android.widget.TextView")

    // Cleanup
    testScope.cancel()
    serverSocket.close()
  }

  @Test
  fun testViewInspectorDump_GetComposablesAndMerge_withParameters() = runBlocking {
    // 1. Setup Background Server to simulate dynamic responses
    val serverSocket = ServerSocket(0)
    val serverPort = serverSocket.localPort

    val dumpCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val composeCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val allParamsCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val serverJob = Job()
    val testScope = CoroutineScope(Dispatchers.Default + serverJob)

    testScope.launch {
      serverSocket.accept().use { socket ->
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // A. Handle GetVersionCommand
        val cmdBytes1 = FramingProtocol.readMessage(input)
        val cmd1 = UiInspectorProtocol.Command.parseFrom(cmdBytes1)
        val versionResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd1.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setGetVersion(
              UiInspectorProtocol.GetVersionResponse.newBuilder().putVersions(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.6.0")
            )
            .build()
        writeResponse(output, versionResponse)

        // B. Handle CreateInspectorCommand (Compose)
        val cmdBytes2 = FramingProtocol.readMessage(input)
        val cmd2 = UiInspectorProtocol.Command.parseFrom(cmdBytes2)
        val createResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd2.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
            .build()
        writeResponse(output, createResponse)

        // C. Handle ViewInspector Message (DumpViewsCommand)
        val cmdBytes3 = FramingProtocol.readMessage(input)
        val cmd3 = UiInspectorProtocol.Command.parseFrom(cmdBytes3)
        dumpCmdReceived.complete(cmd3)

        // Build a mock View tree: FrameLayout (ID 1000) -> AndroidComposeView (ID 2000)
        val viewNode1 =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
            .setId(1000)
            .setClassName(1) // FrameLayout
            .setBounds(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                .setX(0)
                .setY(0)
                .setWidth(1080)
                .setHeight(1920)
            )
            .addChildren(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
                .setId(2000)
                .setClassName(2) // AndroidComposeView
                .setBounds(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                    .setX(0)
                    .setY(0)
                    .setWidth(1080)
                    .setHeight(1920)
                )
            )
            .build()

        val viewResponse =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Response.newBuilder()
            .setDumpViewsResponse(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.DumpViewsResponse.newBuilder()
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(1)
                    .setValue("android.widget.FrameLayout")
                )
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(2)
                    .setValue("androidx.compose.ui.platform.AndroidComposeView")
                )
                .addWindows(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.WindowInfo.newBuilder().setRoot(viewNode1)
                )
            )
            .build()

        val viewMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd3.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(viewResponse.toByteArray()))
            )
            .build()
        writeResponse(output, viewMsgResponse)

        // D. Handle COMPOSE_COMMAND (GetComposablesCommand)
        val cmdBytes4 = FramingProtocol.readMessage(input)
        val cmd4 = UiInspectorProtocol.Command.parseFrom(cmdBytes4)
        composeCmdReceived.complete(cmd4)

        // Build a mock Compose tree: Column (ID 3000) -> Text (ID 4000)
        val composeRoot =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableRoot.newBuilder()
            .setViewId(2000)
            .addNodes(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                .setId(3000)
                .setName(1) // Column
                .setBounds(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                    .setLayout(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                        .setX(0)
                        .setY(0)
                        .setW(1080)
                        .setH(200)
                    )
                )
                .addChildren(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                    .setId(4000)
                    .setName(2) // Text
                    .setBounds(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                        .setLayout(
                          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                            .setX(10)
                            .setY(10)
                            .setW(100)
                            .setH(50)
                        )
                    )
                )
            )
            .build()

        val composeResponse =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
            .setGetComposablesResponse(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(1).setStr("Column")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("Text")
                )
                .addRoots(composeRoot)
            )
            .build()

        val composeMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd4.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(composeResponse.toByteArray()))
            )
            .build()
        writeResponse(output, composeMsgResponse)

        // E. Handle COMPOSE_COMMAND (GetAllParametersCommand)
        val cmdBytes5 = FramingProtocol.readMessage(input)
        val cmd5 = UiInspectorProtocol.Command.parseFrom(cmdBytes5)
        allParamsCmdReceived.complete(cmd5)

        val paramGroup =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(4000)
            .addParameter(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(3) // "text"
                .setType(layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(4) // index for "Hello"
                .build()
            )
            .build()

        val allParamsResponse =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
            .setGetAllParametersResponse(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("text")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("Hello")
                )
                .addParameterGroups(paramGroup)
            )
            .build()

        val paramsMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd5.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(allParamsResponse.toByteArray()))
            )
            .build()
        writeResponse(output, paramsMsgResponse)
      }
    }

    // 2. Setup Mock adbSession / InjectionManager
    val fakeSession = FakeAdbSession()
    val testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    val testHostServices = TestAdbHostServices(fakeSession.hostServices)
    val testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configureUidCommands(fakeSession, deviceSelector, packageName)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val dummyAgent = tempFolder.newFile("libarttooling_agent.so").toPath()
    val dummyJar = tempFolder.newFile("libarttooling.jar").toPath()
    val dummyPayload = tempFolder.newFile("lib_ui_inspector_payload.jar").toPath()
    val dummyViewInspector = tempFolder.newFile("view-inspector.jar").toPath()

    val agentPathResolver = { abi: String -> dummyAgent }
    configureAtomicMoveCommands(fakeSession, deviceSelector)
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        logger,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        dummyViewInspector,
        tempFileSuffixGenerator = { "test.tmp" },
      )

    // Perform the full injection that precedes inspector creation in production
    fakeSession.deviceServices.configureShellCommand(deviceSelector, emptyArtifactsInstallCommand(packageName), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, emptyArtifactsAttachCommand(packageName, serverToken), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix", "ui_inspector_$serverToken\n")
    injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)

    val uiDump =
      CommandSender.connect("127.0.0.1", serverPort, this).use { commandSender ->
        // Inject Compose Inspector with a mock resolveJar lambda returning a dummy file
        val composeInspectorConnected =
          createComposeInspector(
            commandSender = commandSender,
            injectionManager = injectionManager,
            logger = logger,
            resolveJar = {
              val fixedJar = tempFolder.newFile("compose-inspector.jar")
              fixedJar.writeText("fake pre-compiled compose dex classes")
              fixedJar
            },
          )
        assertThat(composeInspectorConnected).isTrue()

        fetchUiDump(
          commandSender = commandSender,
          includeAttributes = true,
          includeResolutionStack = false,
          composeInspectorConnected = composeInspectorConnected,
          includeSemantics = false,
          logger = logger,
        )
      }

    // 4. Assertions
    assertThat(uiDump.windows).hasSize(1)
    val viewRoot = uiDump.windows.single().root
    assertThat(viewRoot.className).isEqualTo("android.widget.FrameLayout")
    val composeView = viewRoot.children[0]
    assertThat(composeView.className).isEqualTo("androidx.compose.ui.platform.AndroidComposeView")
    val column = composeView.children[0]
    assertThat(column.className).isEqualTo("Column")
    val textNode = column.children[0] as UiNode.ComposeNode
    assertThat(textNode.className).isEqualTo("Text")

    // Check parameters
    val p = textNode.parameters[0] as UiNode.ComposeParameter.Single
    assertThat(p.name).isEqualTo("text")
    assertThat((p.value as UiNode.ComposeParameter.Value.StringVal).value).isEqualTo("Hello")

    // The parameter limits are sent rather than left to the agent defaults.
    val allParamsCmd =
      LayoutInspectorComposeProtocol.Command.parseFrom(allParamsCmdReceived.await().inspectorMessage.payload).getAllParametersCommand
    assertThat(allParamsCmd.maxRecursions).isEqualTo(2)
    assertThat(allParamsCmd.maxInitialIterableSize).isEqualTo(5)

    // Cleanup
    testScope.cancel()
    serverSocket.close()
  }

  @Test
  fun testViewInspectorDump_GetComposablesAndMerge_withSemantics(): Unit = runBlocking {
    // 1. Setup Background Server to simulate dynamic responses
    val serverSocket = ServerSocket(0)
    val serverPort = serverSocket.localPort

    val dumpCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val composeCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val allParamsCmdReceived = CompletableDeferred<UiInspectorProtocol.Command>()
    val serverJob = Job()
    val testScope = CoroutineScope(Dispatchers.Default + serverJob)

    testScope.launch {
      serverSocket.accept().use { socket ->
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        // A. Handle GetVersionCommand
        val cmdBytes1 = FramingProtocol.readMessage(input)
        val cmd1 = UiInspectorProtocol.Command.parseFrom(cmdBytes1)
        val versionResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd1.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setGetVersion(
              UiInspectorProtocol.GetVersionResponse.newBuilder().putVersions(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, "1.6.0")
            )
            .build()
        writeResponse(output, versionResponse)

        // B. Handle CreateInspectorCommand
        val cmdBytes2 = FramingProtocol.readMessage(input)
        val cmd2 = UiInspectorProtocol.Command.parseFrom(cmdBytes2)
        val createResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd2.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
            .build()
        writeResponse(output, createResponse)

        // C. Handle ViewInspector Message (DumpViewsCommand)
        val cmdBytes3 = FramingProtocol.readMessage(input)
        val cmd3 = UiInspectorProtocol.Command.parseFrom(cmdBytes3)
        dumpCmdReceived.complete(cmd3)

        // Build a mock View tree: FrameLayout (ID 1000) -> AndroidComposeView (ID 2000)
        val viewNode1 =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
            .setId(1000)
            .setClassName(1) // index for FrameLayout
            .setBounds(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                .setX(0)
                .setY(0)
                .setWidth(1080)
                .setHeight(1920)
            )
            .addChildren(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.ViewNode.newBuilder()
                .setId(2000)
                .setClassName(2) // AndroidComposeView
                .setBounds(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Rect.newBuilder()
                    .setX(0)
                    .setY(0)
                    .setWidth(1080)
                    .setHeight(1920)
                )
            )
            .build()

        val viewResponse =
          com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Response.newBuilder()
            .setDumpViewsResponse(
              com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.DumpViewsResponse.newBuilder()
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(1)
                    .setValue("android.widget.FrameLayout")
                )
                .addStrings(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.StringEntry.newBuilder()
                    .setId(2)
                    .setValue("androidx.compose.ui.platform.AndroidComposeView")
                )
                .addWindows(
                  com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.WindowInfo.newBuilder().setRoot(viewNode1)
                )
            )
            .build()

        val viewMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd3.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.VIEW_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(viewResponse.toByteArray()))
            )
            .build()
        writeResponse(output, viewMsgResponse)

        // D. Handle COMPOSE_COMMAND (GetComposablesCommand)
        val cmdBytes4 = FramingProtocol.readMessage(input)
        val cmd4 = UiInspectorProtocol.Command.parseFrom(cmdBytes4)
        composeCmdReceived.complete(cmd4)

        // Build a mock Compose tree: Column (ID 3000) -> Text (ID 4000)
        val composeRoot =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableRoot.newBuilder()
            .setViewId(2000)
            .addNodes(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                .setId(3000)
                .setName(1) // Column
                .setBounds(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                    .setLayout(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                        .setX(0)
                        .setY(0)
                        .setW(1080)
                        .setH(200)
                    )
                )
                .addChildren(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder()
                    .setId(4000)
                    .setName(2) // Text
                    .setBounds(
                      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Bounds.newBuilder()
                        .setLayout(
                          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Rect.newBuilder()
                            .setX(10)
                            .setY(10)
                            .setW(100)
                            .setH(50)
                        )
                    )
                )
            )
            .build()

        val composeResponse =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
            .setGetComposablesResponse(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(1).setStr("Column")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(2).setStr("Text")
                )
                .addRoots(composeRoot)
            )
            .build()

        val composeMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd4.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(composeResponse.toByteArray()))
            )
            .build()
        writeResponse(output, composeMsgResponse)

        // E. Handle COMPOSE_COMMAND (GetAllParametersCommand)
        val cmdBytes5 = FramingProtocol.readMessage(input)
        val cmd5 = UiInspectorProtocol.Command.parseFrom(cmdBytes5)
        allParamsCmdReceived.complete(cmd5)

        val paramGroup =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
            .setComposableId(4000)
            .addParameter(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(3) // "text"
                .setType(layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(4) // index for "Hello"
                .build()
            )
            .addMergedSemantics(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(5) // "contentDescription"
                .setType(layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.Type.STRING)
                .setInt32Value(6) // index for "My Button"
                .build()
            )
            .build()

        val allParamsResponse =
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
            .setGetAllParametersResponse(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(3).setStr("text")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(4).setStr("Hello")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder()
                    .setId(5)
                    .setStr("contentDescription")
                )
                .addStrings(
                  layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(6).setStr("My Button")
                )
                .addParameterGroups(paramGroup)
            )
            .build()

        val allParamsMsgResponse =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd5.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(allParamsResponse.toByteArray()))
            )
            .build()
        writeResponse(output, allParamsMsgResponse)
      }
    }

    // 2. Setup Mock adbSession / InjectionManager
    val fakeSession = FakeAdbSession()
    val testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    val testHostServices = TestAdbHostServices(fakeSession.hostServices)
    val testSession = TestAdbSession(fakeSession, testDeviceServices, testHostServices)

    fakeSession.hostServices.devices = DeviceList(listOf(DeviceInfo(deviceSerial, DeviceState.ONLINE)), emptyList())

    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    val metadataCmd = "getprop ${DevicePropertyNames.RO_PRODUCT_CPU_ABI} && getprop ${DevicePropertyNames.RO_BUILD_VERSION_SDK}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, metadataCmd, "arm64-v8a\n30\n")
    configureUidCommands(fakeSession, deviceSelector, packageName)
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "run-as $packageName pwd", "/data/data/$packageName\n")

    val dummyAgent = tempFolder.newFile("libarttooling_agent.so").toPath()
    val dummyJar = tempFolder.newFile("libarttooling.jar").toPath()
    val dummyPayload = tempFolder.newFile("lib_ui_inspector_payload.jar").toPath()
    val dummyViewInspector = tempFolder.newFile("view-inspector.jar").toPath()

    val agentPathResolver = { _: String -> dummyAgent }
    configureAtomicMoveCommands(fakeSession, deviceSelector)
    val injectionManager =
      InjectionManager(
        testSession,
        deviceSerial,
        packageName,
        composeInspectorOverrideJarPath = null,
        logger,
        agentPathResolver,
        dummyJar,
        dummyPayload,
        dummyViewInspector,
        tempFileSuffixGenerator = { "test.tmp" },
      )

    // Perform the full injection that precedes inspector creation in production
    configureUidCommands(fakeSession, deviceSelector, packageName)

    fakeSession.deviceServices.configureShellCommand(deviceSelector, emptyArtifactsInstallCommand(packageName), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, emptyArtifactsAttachCommand(packageName, serverToken), "")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "cat /proc/net/unix", "ui_inspector_$serverToken\n")
    injectionManager.injectAndAttach(needsDebugViewAttributes = false, mode = InjectionMode.FORCE_FULL_INJECTION)

    val uiDump =
      CommandSender.connect("127.0.0.1", serverPort, this).use { commandSender ->
        val composeInspectorConnected =
          createComposeInspector(
            commandSender = commandSender,
            injectionManager = injectionManager,
            logger = logger,
            resolveJar = {
              val fixedJar = tempFolder.newFile("compose-inspector.jar")
              fixedJar.writeText("fake pre-compiled compose dex classes")
              fixedJar
            },
          )
        assertThat(composeInspectorConnected).isTrue()

        fetchUiDump(
          commandSender = commandSender,
          includeAttributes = true,
          includeResolutionStack = false,
          composeInspectorConnected = composeInspectorConnected,
          includeSemantics = true,
          logger = logger,
        )
      }

    // 4. Assertions
    assertThat(uiDump.windows).hasSize(1)
    val viewRoot = uiDump.windows.single().root
    assertThat(viewRoot.className).isEqualTo("android.widget.FrameLayout")
    val composeView = viewRoot.children[0]
    assertThat(composeView.className).isEqualTo("androidx.compose.ui.platform.AndroidComposeView")
    val column = composeView.children[0]
    assertThat(column.className).isEqualTo("Column")
    val textNode = column.children[0] as UiNode.ComposeNode
    assertThat(textNode.className).isEqualTo("Text")

    // Check parameters
    val p = textNode.parameters[0] as UiNode.ComposeParameter.Single
    assertThat(p.name).isEqualTo("text")
    assertThat((p.value as UiNode.ComposeParameter.Value.StringVal).value).isEqualTo("Hello")

    // Check semantics
    val s = textNode.mergedSemantics[0] as UiNode.ComposeParameter.Single
    assertThat(s.name).isEqualTo("contentDescription")
    assertThat((s.value as UiNode.ComposeParameter.Value.StringVal).value).isEqualTo("My Button")

    // Cleanup
    testScope.cancel()
    serverSocket.close()
  }

  @Test
  fun testQueryComposeTree_parsesResponseDeeperThanDefaultRecursionLimit(): Unit = runBlocking {
    // A chain of 150 nested ComposableNodes: one proto nesting level per composable, well past protobuf's default recursion limit of 100.
    val depth = 150
    var chain = layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(depth.toLong())
    for (id in depth - 1 downTo 1) {
      chain =
        layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableNode.newBuilder().setId(id.toLong()).addChildren(chain)
    }
    val composeResponse =
      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
        .setGetComposablesResponse(
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetComposablesResponse.newBuilder()
            .addRoots(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ComposableRoot.newBuilder().setViewId(2000).addNodes(chain)
            )
        )
        .build()

    val received =
      respondingWithComposePayload(composeResponse.toByteArray()) { commandSender ->
        queryComposeTree(commandSender, rootViewId = 2000, extractAllParameters = false)
      }

    var current = received.getRoots(0).getNodes(0)
    var nodeCount = 1
    while (current.childrenCount > 0) {
      current = current.getChildren(0)
      nodeCount++
    }
    assertThat(nodeCount).isEqualTo(depth)
    assertThat(current.id).isEqualTo(depth.toLong())
  }

  @Test
  fun testQueryComposeParameters_parsesResponseDeeperThanDefaultRecursionLimit(): Unit = runBlocking {
    // A chain of 150 nested Parameter elements: one proto nesting level per element, well past protobuf's default recursion limit of 100.
    val depth = 150
    var chain = layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.newBuilder().setName(1)
    for (unused in 1 until depth) {
      chain = layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Parameter.newBuilder().setName(1).addElements(chain)
    }
    val paramsResponse =
      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
        .setGetAllParametersResponse(
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetAllParametersResponse.newBuilder()
            .addParameterGroups(
              layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.ParameterGroup.newBuilder()
                .setComposableId(4000)
                .addParameter(chain)
            )
        )
        .build()

    val received =
      respondingWithComposePayload(paramsResponse.toByteArray()) { commandSender ->
        queryComposeParameters(commandSender, rootViewId = 2000)
      }

    var current = received.getParameterGroups(0).getParameter(0)
    var elementCount = 1
    while (current.elementsCount > 0) {
      current = current.getElements(0)
      elementCount++
    }
    assertThat(elementCount).isEqualTo(depth)
  }

  @Test
  fun testCreateComposeInspector_failedVersionQuery_fails() {
    val injectionManager = unusedInjectionManager()
    val exception =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          respondingWith({ command ->
            UiInspectorProtocol.Response.newBuilder()
              .setCommandId(command.commandId)
              .setStatus(UiInspectorProtocol.Response.Status.ERROR)
              .setErrorMessage("version lookup crashed")
              .build()
          }) { commandSender ->
            createComposeInspector(
              commandSender,
              injectionManager,
              logger,
              resolveJar = { throw AssertionError("no jar resolution expected") },
            )
          }
        }
      }
    // A failed query says nothing about whether the app uses Compose, so it is not "Compose not detected".
    assertThat(exception).hasMessageThat().contains("version lookup crashed")
  }

  @Test
  fun testCreateComposeInspector_wrongVersionResponseType_fails() {
    val injectionManager = unusedInjectionManager()
    val exception =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          respondingWith({ command ->
            // A success of the wrong type: reading its (default) version map would pass for "Compose not detected".
            UiInspectorProtocol.Response.newBuilder()
              .setCommandId(command.commandId)
              .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
              .setCreateInspector(UiInspectorProtocol.CreateInspectorResponse.getDefaultInstance())
              .build()
          }) { commandSender ->
            createComposeInspector(
              commandSender,
              injectionManager,
              logger,
              resolveJar = { throw AssertionError("no jar resolution expected") },
            )
          }
        }
      }

    assertThat(exception).hasMessageThat().contains("expected GET_VERSION, got CREATE_INSPECTOR")
  }

  @Test
  fun testCreateComposeInspector_composeNotDetected_returnsFalse(): Unit = runBlocking {
    val injectionManager = unusedInjectionManager()
    val connected =
      respondingWith({ command ->
        UiInspectorProtocol.Response.newBuilder()
          .setCommandId(command.commandId)
          .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
          .setGetVersion(UiInspectorProtocol.GetVersionResponse.getDefaultInstance())
          .build()
      }) { commandSender ->
        createComposeInspector(commandSender, injectionManager, logger, resolveJar = { throw AssertionError("no jar resolution expected") })
      }

    assertThat(connected).isFalse()
  }

  @Test
  fun testCreateComposeInspector_unresolvableJar_fails() {
    val injectionManager = unusedInjectionManager()
    val downloadFailure = IOException("maven.google.com unreachable")
    val exception =
      assertThrows(IOException::class.java) {
        runBlocking {
          respondingWith({ command -> composeVersionResponse(command, "1.6.0") }) { commandSender ->
            createComposeInspector(commandSender, injectionManager, logger, resolveJar = { throw downloadFailure })
          }
        }
      }

    assertThat(exception).hasMessageThat().contains("Compose 1.6.0")
    assertThat(exception).hasMessageThat().contains("maven.google.com unreachable")
    assertThat(Throwables.getRootCause(exception)).isSameAs(downloadFailure)
  }

  @Test
  fun testCreateComposeInspector_nonAarDownload_failsWithComposeVersion() {
    val injectionManager = unusedInjectionManager()
    // A proxy that answers the AAR download with an HTML page.
    val htmlDownloader =
      object : ArtifactDownloader {
        override fun download(url: String, outputFile: File) {
          outputFile.writeText("<html>Blocked by your network administrator</html>")
        }
      }
    val resolveJar = ComposeInspectorResolution(overrideJar = null, tempFolder.newFolder().toPath(), htmlDownloader).jarResolver(logger)
    val exception =
      assertThrows(IOException::class.java) {
        runBlocking {
          respondingWith({ command -> composeVersionResponse(command, "1.6.0") }) { commandSender ->
            createComposeInspector(commandSender, injectionManager, logger, resolveJar)
          }
        }
      }

    assertThat(exception)
      .hasMessageThat()
      .startsWith(
        "Could not resolve the Compose inspector for Compose 1.6.0: " +
          "https://maven.google.com/androidx/compose/ui/ui-android/1.6.0/ui-android-1.6.0.aar is not an AAR"
      )
  }

  @Test
  fun testCreateComposeInspector_resolverDefect_propagatesAsIs() {
    val injectionManager = unusedInjectionManager()
    val defect = NullPointerException("resolver bug")
    val thrown =
      assertThrows(NullPointerException::class.java) {
        runBlocking {
          respondingWith({ command -> composeVersionResponse(command, "1.6.0") }) { commandSender ->
            createComposeInspector(commandSender, injectionManager, logger, resolveJar = { throw defect })
          }
        }
      }

    // Nothing between the resolver and the caller swallows or rewraps what is not a resolution failure.
    assertThat(thrown).isSameAs(defect)
  }

  @Test
  fun testCreateComposeInspector_agentRejectsInspector_fails() {
    val fakeSession = FakeAdbSession()
    val testDeviceServices = TestAdbDeviceServices(fakeSession.deviceServices)
    val testSession = TestAdbSession(fakeSession, testDeviceServices, TestAdbHostServices(fakeSession.hostServices))
    testDeviceServices.session = testSession
    val deviceSelector = DeviceSelector.fromSerialNumber(deviceSerial)
    // The jar is already staged, so the only device work left is the agent's answer to the create command.
    val fixedJar = tempFolder.newFile("compose-inspector.jar")
    fixedJar.writeText("fake pre-compiled compose dex classes")
    val stagedJarPath = "$STAGING_DIR/${fileNameWithHash("compose-inspector.jar", computeContentDigest(fixedJar.toPath()))}"
    fakeSession.deviceServices.configureShellCommand(deviceSelector, statProbeCommand(stagedJarPath), "8124 $stagedJarPath\n")
    val injectionManager = unusedInjectionManager(testSession)

    val exception =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          respondingWith({ command ->
            if (command.hasGetVersion()) {
              composeVersionResponse(command, "1.6.0")
            } else {
              UiInspectorProtocol.Response.newBuilder()
                .setCommandId(command.commandId)
                .setStatus(UiInspectorProtocol.Response.Status.ERROR)
                .setErrorMessage("dex load failed")
                .build()
            }
          }) { commandSender ->
            createComposeInspector(commandSender, injectionManager, logger, resolveJar = { fixedJar })
          }
        }
      }

    assertThat(exception).hasMessageThat().contains("dex load failed")
  }

  @Test
  fun testQueryComposeTree_unexpectedResponse_fails() {
    // A parameters response where a tree response was asked for.
    val wrongResponse =
      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
        .setGetAllParametersResponse(
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetAllParametersResponse.getDefaultInstance()
        )
        .build()

    val exception =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          respondingWithComposePayload(wrongResponse.toByteArray()) { commandSender ->
            queryComposeTree(commandSender, rootViewId = 2000, extractAllParameters = false)
          }
        }
      }

    assertThat(exception).hasMessageThat().contains("expected GET_COMPOSABLES_RESPONSE, got GET_ALL_PARAMETERS_RESPONSE")
  }

  @Test
  fun testQueryComposeParameters_unexpectedResponse_fails() {
    // A tree response where a parameters response was asked for.
    val wrongResponse =
      layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.Response.newBuilder()
        .setGetComposablesResponse(
          layoutinspector.compose.inspection.LayoutInspectorComposeProtocol.GetComposablesResponse.getDefaultInstance()
        )
        .build()

    val exception =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          respondingWithComposePayload(wrongResponse.toByteArray()) { commandSender ->
            queryComposeParameters(commandSender, rootViewId = 2000)
          }
        }
      }

    assertThat(exception).hasMessageThat().contains("expected GET_ALL_PARAMETERS_RESPONSE, got GET_COMPOSABLES_RESPONSE")
  }

  @Test
  fun testFetchParameter_recapturesWindowThenRequestsValue(): Unit = runBlocking {
    val reference =
      ParameterReference(
        2000,
        -100,
        ParameterReference.Kind.MERGED_SEMANTICS,
        2,
        listOf(1, 3),
        0,
        ParameterReference.hashOf("style", UiNode.ComposeParameter.Value.StringVal("TextStyle")),
      )
    // An object whose property at the depth limit is left out in turn.
    val details =
      detailsResponse(
        LayoutInspectorComposeProtocol.Parameter.newBuilder()
          .setName(1)
          .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
          .setInt32Value(2)
          .addElements(
            LayoutInspectorComposeProtocol.Parameter.newBuilder()
              .setName(3)
              .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
              .setInt32Value(4)
              .setReference(
                LayoutInspectorComposeProtocol.ParameterReference.newBuilder()
                  .setComposableId(-100)
                  .setKind(LayoutInspectorComposeProtocol.ParameterReference.Kind.MERGED_SEMANTICS)
                  .setParameterIndex(2)
                  .addAllCompositeIndex(listOf(1, 3, 0))
              )
          ),
        mapOf(1 to "style", 2 to "TextStyle", 3 to "shadow", 4 to "Shadow"),
      )

    val (parameter, commands) = fetchParameterFromFakeAgent(reference) { details }

    assertThat(commands.map { it.specializedCase })
      .containsExactly(
        LayoutInspectorComposeProtocol.Command.SpecializedCase.GET_COMPOSABLES_COMMAND,
        LayoutInspectorComposeProtocol.Command.SpecializedCase.GET_PARAMETER_DETAILS_COMMAND,
      )
      .inOrder()
    val capture = commands[0].getComposablesCommand
    assertThat(capture.rootViewId).isEqualTo(2000)
    assertThat(capture.generation).isEqualTo(0)
    assertThat(capture.extractAllParameters).isTrue()
    val request = commands[1].getParameterDetailsCommand
    assertThat(request.rootViewId).isEqualTo(2000)
    assertThat(request.reference.composableId).isEqualTo(-100)
    assertThat(request.reference.kind).isEqualTo(LayoutInspectorComposeProtocol.ParameterReference.Kind.MERGED_SEMANTICS)
    assertThat(request.reference.parameterIndex).isEqualTo(2)
    assertThat(request.reference.compositeIndexList).containsExactly(1, 3).inOrder()
    assertThat(request.startIndex).isEqualTo(0)
    assertThat(request.maxElements).isEqualTo(5)
    assertThat(request.maxRecursions).isEqualTo(2)
    assertThat(request.maxInitialIterableSize).isEqualTo(5)
    assertThat(request.skipSystemComposables).isFalse()
    assertThat(request.generation).isEqualTo(0)
    assertThat(parameter)
      .isEqualTo(
        UiNode.ComposeParameter.Group(
          "style",
          listOf(
            UiNode.ComposeParameter.Single(
              "shadow",
              UiNode.ComposeParameter.Value.StringVal("Shadow"),
              ParameterReference(
                2000,
                -100,
                ParameterReference.Kind.MERGED_SEMANTICS,
                2,
                listOf(1, 3, 0),
                0,
                ParameterReference.hashOf("shadow", UiNode.ComposeParameter.Value.StringVal("Shadow")),
              ),
            )
          ),
          isCollection = false,
          value = UiNode.ComposeParameter.Value.StringVal("TextStyle"),
        )
      )
  }

  @Test
  fun testFetchParameter_returnsWholeCollection(): Unit = runBlocking {
    // The dump sent the first 5 of 131 elements. Each page asks for as many elements as received so far in this call, at least 5.
    val reference = ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, listOf(4), 5, listHash(size = 131))

    val (parameter, commands) = fetchParameterFromFakeAgent(reference, details = listPages(size = 131))

    val requests = commands.drop(1).map { it.getParameterDetailsCommand }
    assertThat(requests.map { it.startIndex to it.maxElements })
      .containsExactly(5 to 5, 10 to 5, 15 to 10, 25 to 20, 45 to 40, 85 to 80)
      .inOrder()
    val collection = parameter as UiNode.ComposeParameter.Group
    assertThat(collection.value).isEqualTo(UiNode.ComposeParameter.Value.StringVal("List[131]"))
    assertThat(collection.elements.map { (it.value as UiNode.ComposeParameter.Value.NumberVal).value }).isEqualTo((5 until 131).toList())
    assertThat(collection.reference).isNull()
  }

  @Test
  fun testFetchParameter_collectionCutAtDepthLimitStartsFromFirstElement(): Unit = runBlocking {
    // The dump sent none of the elements: the inspector sends the first ones as in a dump, whatever the requested number.
    val reference = ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, listOf(4), 0, listHash(size = 7))

    val (parameter, commands) = fetchParameterFromFakeAgent(reference, details = listPages(size = 7))

    assertThat(commands.drop(1).map { it.getParameterDetailsCommand.startIndex }).containsExactly(0, 5).inOrder()
    assertThat((parameter as UiNode.ComposeParameter.Group).elements).hasSize(7)
    assertThat(parameter.reference).isNull()
  }

  @Test
  fun testFetchParameter_endlessCollectionStopsAtByteBudgetWithToken(): Unit = runBlocking {
    val reference = ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, listOf(4), 5, listHash(size = Int.MAX_VALUE))

    // A budget the first page already reaches.
    val (parameter, commands) = fetchParameterFromFakeAgent(reference, byteBudget = 1, details = listPages(size = Int.MAX_VALUE))

    assertThat(commands.drop(1)).hasSize(1)
    val collection = parameter as UiNode.ComposeParameter.Group
    assertThat(collection.elements).hasSize(5)
    assertThat(collection.reference)
      .isEqualTo(ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, listOf(4), 10, listHash(size = Int.MAX_VALUE)))
  }

  @Test
  fun testFetchParameter_continuingAfterByteBudgetStartsWithSmallPages(): Unit = runBlocking {
    // The pages of a call double from the elements received in that call, so that continuing after a stop does not request a bigger page.
    val reference =
      ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, listOf(4), 100_000, listHash(size = Int.MAX_VALUE))

    val (_, commands) = fetchParameterFromFakeAgent(reference, byteBudget = 1, details = listPages(size = Int.MAX_VALUE))

    assertThat(commands.drop(1).single().getParameterDetailsCommand.maxElements).isEqualTo(5)
  }

  @Test
  fun testFetchParameter_laterPageWithOtherName_fails() {
    val reference = ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, listOf(4), 5, listHash(size = 131))
    val feedPages = listPages(size = 131)
    val otherPages = listPages(size = 131, name = "items")

    val exception =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          fetchParameterFromFakeAgent(reference) { command -> if (command.startIndex == 5) feedPages(command) else otherPages(command) }
        }
      }

    // The message names no command: the UI inspector is used by several CLIs, whose commands have other names.
    assertThat(exception)
      .hasMessageThat()
      .isEqualTo("The parameter is no longer available: the UI changed since the dump, or its window closed. Dump the UI again.")
  }

  @Test
  fun testFetchParameter_collectionWithOtherSize_fails() {
    // The same list, which now has another size: its content changed since the dump.
    val reference = ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, listOf(4), 5, listHash(size = 131))

    val exception =
      assertThrows(IllegalStateException::class.java) {
        runBlocking { fetchParameterFromFakeAgent(reference, details = listPages(size = 140)) }
      }

    assertThat(exception).hasMessageThat().contains("no longer available")
  }

  @Test
  fun testFetchParameter_pageNotMovingReferenceStopsWithToken(): Unit = runBlocking {
    // A page without elements that still has a reference would otherwise be requested again forever. It is dropped, and the token returned
    // leads to it, after the elements received.
    val reference = ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, listOf(4), 5, listHash(size = 131))
    val feedPages = listPages(size = 131)
    val stuck =
      detailsResponse(
        LayoutInspectorComposeProtocol.Parameter.newBuilder()
          .setName(1)
          .setType(LayoutInspectorComposeProtocol.Parameter.Type.ITERABLE)
          .setInt32Value(2)
          .setReference(LayoutInspectorComposeProtocol.ParameterReference.newBuilder().setComposableId(-100).addCompositeIndex(4)),
        mapOf(1 to "feed", 2 to "List[131]"),
      )

    val (parameter, commands) =
      fetchParameterFromFakeAgent(reference) { command -> if (command.startIndex == 5) feedPages(command) else stuck }

    assertThat(commands.drop(1)).hasSize(2)
    assertThat((parameter as UiNode.ComposeParameter.Group).elements).hasSize(5)
    assertThat(parameter.reference)
      .isEqualTo(ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, listOf(4), 10, listHash(size = 131)))
  }

  @Test
  fun testFetchParameter_valueNotFound_fails() {
    // The inspector answers with an empty response when the window or the value is gone.
    val reference = ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, emptyList(), 0, "style".hashCode())

    val exception =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          fetchParameterFromFakeAgent(reference) { LayoutInspectorComposeProtocol.GetParameterDetailsResponse.getDefaultInstance() }
        }
      }

    assertThat(exception).hasMessageThat().contains("no longer available")
  }

  @Test
  fun testFetchParameter_valueWithOtherName_fails() {
    // e.g. a modifier was added before the one the reference points to, which now leads to another modifier.
    val reference =
      ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, listOf(1), 0, ParameterReference.hashOf("padding", null))
    val details =
      detailsResponse(
        LayoutInspectorComposeProtocol.Parameter.newBuilder().setName(1).setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING),
        mapOf(1 to "background"),
      )

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { fetchParameterFromFakeAgent(reference) { details } } }

    assertThat(exception).hasMessageThat().contains("no longer available")
  }

  @Test
  fun testFetchParameter_continuedValueNoLongerCollection_fails() {
    // The inspector answers a request past the first elements of a value that is no longer a collection with the value as it is now, e.g.
    // an object, or an empty string when it cannot decompose it. Here it even has the same name and value as the collection had.
    val reference =
      ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, emptyList(), 5, listHash(size = 7, name = "items"))
    val details =
      detailsResponse(
        LayoutInspectorComposeProtocol.Parameter.newBuilder()
          .setName(1)
          .setType(LayoutInspectorComposeProtocol.Parameter.Type.STRING)
          .setInt32Value(2),
        mapOf(1 to "items", 2 to "List[7]"),
      )

    val exception = assertThrows(IllegalStateException::class.java) { runBlocking { fetchParameterFromFakeAgent(reference) { details } } }

    assertThat(exception).hasMessageThat().contains("no longer available")
  }

  @Test
  fun testFetchParameter_withoutCompose_failsWithoutQuerying() {
    val reference = ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, emptyList(), 0, "style".hashCode())

    val exception =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          respondingWith({ error("Unexpected command: $it") }) { commandSender ->
            fetchParameter(commandSender, composeInspectorConnected = false, reference)
          }
        }
      }

    assertThat(exception).hasMessageThat().isEqualTo("The app does not use Compose.")
  }

  @Test
  fun testQueryParameterDetails_unexpectedResponse_fails() {
    // A parameters response where a details response was asked for.
    val wrongResponse =
      LayoutInspectorComposeProtocol.Response.newBuilder()
        .setGetAllParametersResponse(LayoutInspectorComposeProtocol.GetAllParametersResponse.getDefaultInstance())
        .build()
    val reference = ParameterReference(2000, -100, ParameterReference.Kind.PARAMETERS, 0, emptyList(), 0, "style".hashCode())

    val exception =
      assertThrows(IllegalStateException::class.java) {
        runBlocking {
          respondingWithComposePayload(wrongResponse.toByteArray()) { queryParameterDetails(it, reference, elementsReceived = 0) }
        }
      }

    assertThat(exception).hasMessageThat().contains("expected GET_PARAMETER_DETAILS_RESPONSE, got GET_ALL_PARAMETERS_RESPONSE")
  }

  /** A details response holding [parameter], whose names and string values [strings] resolves, for the window 2000. */
  private fun detailsResponse(
    parameter: LayoutInspectorComposeProtocol.Parameter.Builder,
    strings: Map<Int, String>,
  ): LayoutInspectorComposeProtocol.GetParameterDetailsResponse =
    LayoutInspectorComposeProtocol.GetParameterDetailsResponse.newBuilder()
      .setRootViewId(2000)
      .setParameter(parameter)
      .addAllStrings(strings.map { (id, str) -> LayoutInspectorComposeProtocol.StringEntry.newBuilder().setId(id).setStr(str).build() })
      .build()

  /** The value hash of the list [listPages] answers with. */
  private fun listHash(size: Int, name: String = "feed"): Int =
    ParameterReference.hashOf(name, UiNode.ComposeParameter.Value.StringVal("List[$size]"))

  /**
   * Answers the details commands for a list of [size] integers named [name], each equal to its index, as the inspector does: the first 5
   * elements from the start, otherwise as many as requested, and a reference while elements remain.
   */
  private fun listPages(
    size: Int,
    name: String = "feed",
  ): (LayoutInspectorComposeProtocol.GetParameterDetailsCommand) -> LayoutInspectorComposeProtocol.GetParameterDetailsResponse =
    { command ->
      val count = if (command.startIndex == 0) 5 else command.maxElements
      val end = minOf(size.toLong(), command.startIndex.toLong() + count).toInt()
      val list =
        LayoutInspectorComposeProtocol.Parameter.newBuilder()
          .setName(1)
          .setType(LayoutInspectorComposeProtocol.Parameter.Type.ITERABLE)
          .setInt32Value(2)
          .addAllElements(
            (command.startIndex until end).map { index ->
              LayoutInspectorComposeProtocol.Parameter.newBuilder()
                .setName(3)
                .setType(LayoutInspectorComposeProtocol.Parameter.Type.INT32)
                .setInt32Value(index)
                .setIndex(index)
                .build()
            }
          )
      if (end < size) {
        list.setReference(
          LayoutInspectorComposeProtocol.ParameterReference.newBuilder()
            .setComposableId(-100)
            .addAllCompositeIndex(command.reference.compositeIndexList)
        )
      }
      detailsResponse(list, mapOf(1 to name, 2 to "List[$size]", 3 to "[i]"))
    }

  /**
   * Runs [fetchParameter] for [reference], with [byteBudget] if set, against a fake agent whose Compose inspector answers with an empty
   * tree and with [details], and returns its result with the Compose commands the agent received.
   */
  private suspend fun fetchParameterFromFakeAgent(
    reference: ParameterReference,
    byteBudget: Int? = null,
    details: (LayoutInspectorComposeProtocol.GetParameterDetailsCommand) -> LayoutInspectorComposeProtocol.GetParameterDetailsResponse,
  ): Pair<UiNode.ComposeParameter, List<LayoutInspectorComposeProtocol.Command>> {
    val commands = Collections.synchronizedList(mutableListOf<LayoutInspectorComposeProtocol.Command>())
    val parameter =
      respondingWith({ command ->
        val composeCommand = LayoutInspectorComposeProtocol.Command.parseFrom(command.inspectorMessage.payload)
        commands.add(composeCommand)
        val composeResponse =
          when (composeCommand.specializedCase) {
            LayoutInspectorComposeProtocol.Command.SpecializedCase.GET_COMPOSABLES_COMMAND ->
              LayoutInspectorComposeProtocol.Response.newBuilder()
                .setGetComposablesResponse(LayoutInspectorComposeProtocol.GetComposablesResponse.getDefaultInstance())
            else ->
              LayoutInspectorComposeProtocol.Response.newBuilder()
                .setGetParameterDetailsResponse(details(composeCommand.getParameterDetailsCommand))
          }
        UiInspectorProtocol.Response.newBuilder()
          .setCommandId(command.commandId)
          .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
          .setInspectorMessage(
            UiInspectorProtocol.InspectorMessageResponse.newBuilder()
              .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
              .setPayload(composeResponse.build().toByteString())
          )
          .build()
      }) { commandSender ->
        if (byteBudget == null) fetchParameter(commandSender, composeInspectorConnected = true, reference)
        else fetchParameter(commandSender, composeInspectorConnected = true, reference, byteBudget)
      }
    return parameter to commands.toList()
  }

  /** A successful version answer to [command] reporting Compose [version]. */
  private fun composeVersionResponse(command: UiInspectorProtocol.Command, version: String): UiInspectorProtocol.Response =
    UiInspectorProtocol.Response.newBuilder()
      .setCommandId(command.commandId)
      .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
      .setGetVersion(UiInspectorProtocol.GetVersionResponse.newBuilder().putVersions(ProtocolConstants.COMPOSE_UI_LIBRARY_ID, version))
      .build()

  /** An [InjectionManager] for flows that never stage anything, or stage against [session] alone. */
  private fun unusedInjectionManager(session: com.android.adblib.AdbSession = FakeAdbSession()): InjectionManager =
    InjectionManager(
      session,
      deviceSerial,
      packageName,
      composeInspectorOverrideJarPath = null,
      logger,
      { _: String -> tempFolder.newFile("unused-agent-${System.nanoTime()}.so").toPath() },
      tempFolder.newFile("unused-library-${System.nanoTime()}.jar").toPath(),
      tempFolder.newFile("unused-payload-${System.nanoTime()}.jar").toPath(),
      tempFolder.newFile("unused-view-inspector-${System.nanoTime()}.jar").toPath(),
      tempFileSuffixGenerator = { "test.tmp" },
    )

  /** Runs [block] against a loopback fake agent that answers every command with [respond], until the connection closes. */
  private suspend fun <T> respondingWith(
    respond: (UiInspectorProtocol.Command) -> UiInspectorProtocol.Response,
    block: suspend (CommandSender) -> T,
  ): T {
    val serverSocket = ServerSocket(0)
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    testScope.launch {
      serverSocket.accept().use { socket ->
        val input = socket.getInputStream()
        try {
          while (true) {
            val command = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(input))
            writeResponse(socket.getOutputStream(), respond(command))
          }
        } catch (_: IOException) {
          // The client closing the connection ends the conversation.
        }
      }
    }
    try {
      return coroutineScope {
        CommandSender.connect("127.0.0.1", serverSocket.localPort, this).use { commandSender -> block(commandSender) }
      }
    } finally {
      testScope.cancel()
      serverSocket.close()
    }
  }

  /** Runs [block] against a loopback fake agent that answers the single expected inspector command with [payload]. */
  private suspend fun <T> respondingWithComposePayload(payload: ByteArray, block: suspend (CommandSender) -> T): T {
    val serverSocket = ServerSocket(0)
    val testScope = CoroutineScope(Dispatchers.Default + Job())
    testScope.launch {
      serverSocket.accept().use { socket ->
        val cmd = UiInspectorProtocol.Command.parseFrom(FramingProtocol.readMessage(socket.getInputStream()))
        val response =
          UiInspectorProtocol.Response.newBuilder()
            .setCommandId(cmd.commandId)
            .setStatus(UiInspectorProtocol.Response.Status.SUCCESS)
            .setInspectorMessage(
              UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                .setInspectorId(ProtocolConstants.COMPOSE_INSPECTOR_ID)
                .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
            )
            .build()
        writeResponse(socket.getOutputStream(), response)
      }
    }
    try {
      return coroutineScope {
        CommandSender.connect("127.0.0.1", serverSocket.localPort, this).use { commandSender -> block(commandSender) }
      }
    } finally {
      testScope.cancel()
      serverSocket.close()
    }
  }

  private fun configureAtomicMoveCommands(fakeSession: FakeAdbSession, deviceSelector: DeviceSelector) {
    listOf(
        "libarttooling_agent.so" to EMPTY_FILE_DIGEST,
        "libarttooling.jar" to EMPTY_FILE_DIGEST,
        "lib_ui_inspector_payload.jar" to EMPTY_FILE_DIGEST,
        // The compose fixture jar's content is "fake pre-compiled compose dex classes".
        "compose-inspector.jar" to "665e173983c8",
      )
      .forEach { (baseName, digest) ->
        val target = "$STAGING_DIR/${fileNameWithHash(baseName, digest)}"
        val staleVersionsPattern = "$STAGING_DIR/${fileNameWithHash(baseName, CONTENT_DIGEST_PATTERN)}"
        fakeSession.deviceServices.configureShellCommand(
          deviceSelector,
          "rm -f $staleVersionsPattern && test ! -d '$target' && mv -f '$target.test.tmp' '$target'",
          "",
        )
        fakeSession.deviceServices.configureShellCommand(deviceSelector, "rm -f '$target.test.tmp'", "")
      }
  }

  private fun configureUidCommands(fakeSession: FakeAdbSession, deviceSelector: DeviceSelector, packageName: String) {
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "pm list packages -U --user 0", "package:$packageName uid:10123\n")
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "ps -A -o PID,UID,NAME", "PID UID NAME\n1234 10123 $packageName\n")
  }
}
