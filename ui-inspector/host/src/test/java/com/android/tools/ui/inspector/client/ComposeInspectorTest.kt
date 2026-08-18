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
import com.android.tools.ui.inspector.TestAdbDeviceServices
import com.android.tools.ui.inspector.TestAdbHostServices
import com.android.tools.ui.inspector.TestAdbSession
import com.android.tools.ui.inspector.common.FramingProtocol
import com.android.tools.ui.inspector.common.ProtocolConstants
import com.android.tools.ui.inspector.deploy.CONTENT_DIGEST_PATTERN
import com.android.tools.ui.inspector.deploy.InjectionManager
import com.android.tools.ui.inspector.deploy.InjectionMode
import com.android.tools.ui.inspector.deploy.buildInstallCommand
import com.android.tools.ui.inspector.deploy.computeContentDigest
import com.android.tools.ui.inspector.deploy.fileNameWithHash
import com.android.tools.ui.inspector.fetchUiDump
import com.android.tools.ui.inspector.model.UiNode
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol
import com.android.tools.ui.inspector.sessionFactory
import com.android.tools.ui.inspector.statProbeCommand
import com.google.common.truth.Truth.assertThat
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Content digest of an empty file: first 12 hex of its SHA-256. Every dummy base artifact these tests create is empty. */
private const val EMPTY_FILE_DIGEST = "e3b0c44298fc"

/** The device staging directory every artifact is pushed into under its digest-carrying name. */
private const val STAGING_DIR = "/data/local/tmp/ui-inspector"

private const val EMPTY_SERVICE_JAR_NAME = "lib_ui_inspector_service.$EMPTY_FILE_DIGEST.jar"
private const val EMPTY_PAYLOAD_JAR_NAME = "lib_ui_inspector_payload.$EMPTY_FILE_DIGEST.jar"

/** The exact install command the production flow issues when all three base artifacts are empty files. */
private fun emptyArtifactsInstallCommand(packageName: String): String =
  buildInstallCommand(
    packageName = packageName,
    agentStagePath = "$STAGING_DIR/lib_ui_inspector_agent.$EMPTY_FILE_DIGEST.so",
    serviceJarStagePath = "$STAGING_DIR/$EMPTY_SERVICE_JAR_NAME",
    payloadJarStagePath = "$STAGING_DIR/$EMPTY_PAYLOAD_JAR_NAME",
    serviceJarName = EMPTY_SERVICE_JAR_NAME,
    payloadJarName = EMPTY_PAYLOAD_JAR_NAME,
    tempSuffix = "test.tmp",
  )

/** The exact attach command the production flow issues when all three base artifacts are empty files. */
private fun emptyArtifactsAttachCommand(packageName: String, serverToken: String): String =
  "cmd activity attach-agent 1234 \"/data/data/$packageName/lib_ui_inspector_agent.so=" +
    "/data/data/$packageName/$EMPTY_SERVICE_JAR_NAME;/data/data/$packageName/$EMPTY_PAYLOAD_JAR_NAME;$serverToken\""

class ComposeInspectorTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private fun writeResponse(output: java.io.OutputStream, response: UiInspectorProtocol.Response) {
    val agentMessage = UiInspectorProtocol.AgentMessage.newBuilder().setResponse(response).build()
    FramingProtocol.writeMessage(output, agentMessage.toByteArray())
  }

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

    val dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    val dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()
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
        { _: String -> tempFolder.newFile("unused-agent.so").toPath() },
        tempFolder.newFile("unused-service.jar").toPath(),
        tempFolder.newFile("unused-payload.jar").toPath(),
        tempFolder.newFile("unused-view-inspector.jar").toPath(),
        tempFileSuffixGenerator = { "test.tmp" },
      )

    val connected =
      CommandSender.connect("127.0.0.1", serverPort, this).use { commandSender ->
        createComposeInspector(commandSender = commandSender, injectionManager = injectionManager, resolveJar = { fixedJar })
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

    val dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    val dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()
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

    val dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    val dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()
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

    val dummyAgent = tempFolder.newFile("lib_ui_inspector_agent.so").toPath()
    val dummyJar = tempFolder.newFile("lib_ui_inspector_service.jar").toPath()
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

    val originalFactory = sessionFactory
    sessionFactory = { testSession }

    val uiDump =
      try {
        CommandSender.connect("127.0.0.1", serverPort, this).use { commandSender ->
          val composeInspectorConnected =
            createComposeInspector(
              commandSender = commandSender,
              injectionManager = injectionManager,
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
          )
        }
      } finally {
        sessionFactory = originalFactory
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

    var current = received!!.getRoots(0).getNodes(0)
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

    var current = received!!.getParameterGroups(0).getParameter(0)
    var elementCount = 1
    while (current.elementsCount > 0) {
      current = current.getElements(0)
      elementCount++
    }
    assertThat(elementCount).isEqualTo(depth)
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
        "lib_ui_inspector_agent.so" to EMPTY_FILE_DIGEST,
        "lib_ui_inspector_service.jar" to EMPTY_FILE_DIGEST,
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
    fakeSession.deviceServices.configureShellCommand(
      deviceSelector,
      "pm list packages -U --user 0 $packageName",
      "package:$packageName uid:10123\n",
    )
    fakeSession.deviceServices.configureShellCommand(deviceSelector, "ps -A -o PID,UID,NAME", "PID UID NAME\n1234 10123 $packageName\n")
  }
}
