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
package com.android.adblib.tools.debugging.processinventory.impl

import com.android.adblib.AdbSession
import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.InstructionSet
import com.android.adblib.adbLogger
import com.android.adblib.getOrPutSynchronized
import com.android.adblib.property
import com.android.adblib.scope
import com.android.adblib.serialNumber
import com.android.adblib.tools.debugging.JdwpProcessProperties
import com.android.adblib.tools.debugging.OptionalValue
import com.android.adblib.tools.debugging.mergeWith
import com.android.adblib.tools.debugging.processinventory.AdbLibToolsProcessInventoryServerProperties
import com.android.adblib.tools.debugging.processinventory.ProcessInventoryServerConnection
import com.android.adblib.tools.debugging.processinventory.ProcessInventoryServerConnection.ConnectionForDevice
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.ProcessUpdate
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.Response.TrackDeviceResponsePayload
import com.android.adblib.tools.debugging.processinventory.server.ProcessInventoryServer
import com.android.adblib.tools.debugging.processinventory.server.ProcessInventoryServerConfiguration
import com.android.adblib.tools.tcpserver.RetryPolicy
import com.android.adblib.tools.tcpserver.TcpServerConnection
import com.android.adblib.utils.createChildScope
import com.android.adblib.utils.logIOCompletionErrors
import com.android.adblib.utils.runAlongOtherScope
import com.android.adblib.withPrefix
import com.google.protobuf.TextFormat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal class ProcessInventoryServerConnectionImpl(session: AdbSession, private val config: ProcessInventoryServerConfiguration) :
  ProcessInventoryServerConnection {

  /**
   * The "failover" connection to the [ProcessInventoryServer] server, meaning either we start the server if not running or we connect to an
   * existing one. The "retry" policy ensures that, if the server shutdowns, we try to start a new one ourselves.
   */
  private val serverWithFailOver =
    TcpServerConnection.createWithFailoverConnection(
      session = session,
      tcpServer = ProcessInventoryServer(session, config),
      port = session.property(AdbLibToolsProcessInventoryServerProperties.LOCAL_PORT_V1),
      connectTimeout = session.property(AdbLibToolsProcessInventoryServerProperties.CONNECT_TIMEOUT),
      retryPolicy = RetryPolicy.fixedDelay(session.property(AdbLibToolsProcessInventoryServerProperties.START_RETRY_DELAY)),
    )

  override suspend fun <R> withConnectionForDevice(device: ConnectedDevice, block: suspend ConnectionForDevice.() -> R): R {
    // Use scope of device so `block` is cancelled when the device scope is cancelled
    return runAlongOtherScope(device.scope) {
      val connectionForDevice = device.processInventoryServerConnection(config, serverWithFailOver)

      // Wait for all flows to be active, in case `block` collects them
      connectionForDevice.awaitFlowsAreActive()

      connectionForDevice.block()
    }
  }

  override fun close() {
    serverWithFailOver.close()
  }
}

private val processInventoryServerConnectionForDeviceKey =
  CoroutineScopeCache.Key<ProcessInventoryServerConnectionForDevice>("processInventoryServerConnectionForDeviceKey")

private fun ConnectedDevice.processInventoryServerConnection(
  config: ProcessInventoryServerConfiguration,
  server: TcpServerConnection,
): ProcessInventoryServerConnectionForDevice {
  return this.cache.getOrPutSynchronized(processInventoryServerConnectionForDeviceKey) {
    ProcessInventoryServerConnectionForDevice(server, config, this)
  }
}

/**
 * A TCP client to the [server] connection to [ProcessInventoryServer] for a given [ConnectedDevice], synchronizing process properties
 * updates coming from internal process discovery and from the server.
 */
private class ProcessInventoryServerConnectionForDevice(
  private val server: TcpServerConnection,
  private val config: ProcessInventoryServerConfiguration,
  override val device: ConnectedDevice,
) : ConnectionForDevice, AutoCloseable {

  private val session: AdbSession
    get() = device.session

  private val logger = adbLogger(session).withPrefix("${device.session} - $device - ${config.clientDescription} - ")

  private val scope = device.scope.createChildScope(isSupervisor = true)

  private val processPropertiesListMutableStateFlow = MutableStateFlow<List<JdwpProcessProperties>>(emptyList())

  /**
   * The [MutableSharedFlow] of [ProcessInventoryServerProto.ProcessCommand] that contains the commands received from the remote
   * [ProcessInventoryServer].
   *
   * Note: The flow is non-suspending there may not be consumers of the flow.
   */
  private val processCommandMutableSharedFlow =
    MutableSharedFlow<ProcessInventoryServerProto.ProcessCommand>(extraBufferCapacity = 10, onBufferOverflow = BufferOverflow.DROP_OLDEST)

  /**
   * The [MutableSharedFlow] of [ProcessInventoryServerProto.ProcessCommandReply] that contains the command replies received from the remote
   * [ProcessInventoryServer].
   *
   * Note: The flow is non-suspending there may not be consumers of the flow.
   */
  private val processCommandReplyMutableSharedFlow =
    MutableSharedFlow<ProcessInventoryServerProto.ProcessCommandReply>(
      extraBufferCapacity = 10,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

  private val trackerIsActiveDeferred = CompletableDeferred<Unit>()

  override val processListStateFlow = processPropertiesListMutableStateFlow.asStateFlow()

  override val processCommandSharedFlow = processCommandMutableSharedFlow.asSharedFlow()

  override val processCommandReplySharedFlow = processCommandReplyMutableSharedFlow.asSharedFlow()

  init {
    scope.launch {
      runCatching { trackDeviceRequestsFromServer() }
        .onFailure { throwable -> logger.logIOCompletionErrors(throwable, "Remote process tracking coroutine") }
    }
  }

  suspend fun awaitFlowsAreActive() {
    if (!trackerIsActiveDeferred.isCompleted) {
      logger.verbose { "Waiting for tracker to be active" }
      trackerIsActiveDeferred.await()
      logger.verbose { "Done waiting for tracker to be active" }
    }
  }

  override fun close() {
    scope.cancel("${this::class.java.simpleName} has been closed")
    processPropertiesListMutableStateFlow.update { emptyList() }
  }

  override suspend fun sendProcessProperties(properties: JdwpProcessProperties) {
    // Note: This will retry until the server is available
    server.withClientSocket { _, socketChannel ->
      logger.debug { "Sending process properties $properties on socket $socketChannel" }
      val protocolChannel = ProcessInventoryServerSocketProtocol(session, socketChannel).forClient(config.clientDescription)

      val processInfo = properties.toJdwpProcessInfoProto()
      val response = protocolChannel.sendDeviceProcessInfoList(device.serialNumber, listOf(processInfo))
      logger.debug { "Sent process properties: $response" }
    }
  }

  override suspend fun sendProcessCommand(command: ProcessInventoryServerProto.ProcessCommand) {
    // Note: This will retry until the server is available
    server.withClientSocket { _, socketChannel ->
      logger.debug { "Sending process command on socket $socketChannel: ${TextFormat.shortDebugString(command)}" }
      val protocolChannel = ProcessInventoryServerSocketProtocol(session, socketChannel).forClient(config.clientDescription)
      val response = protocolChannel.sendDeviceProcessCommand(device.serialNumber, command)
      logger.debug { "Process command response: ${TextFormat.shortDebugString(response)}" }
    }
  }

  override suspend fun sendProcessCommandReply(commandReply: ProcessInventoryServerProto.ProcessCommandReply) {
    // Note: This will retry until the server is available
    server.withClientSocket { _, socketChannel ->
      logger.debug { "Sending process command reply on socket $socketChannel: ${TextFormat.shortDebugString(commandReply)}" }
      val protocolChannel = ProcessInventoryServerSocketProtocol(session, socketChannel).forClient(config.clientDescription)
      val response = protocolChannel.sendDeviceProcessCommandReply(device.serialNumber, commandReply)
      logger.debug { "Process command response: ${TextFormat.shortDebugString(response)}" }
    }
  }

  override suspend fun notifyProcessExit(pid: Int) {
    // Note: This will retry until the server is available
    server.withClientSocket { _, socketChannel ->
      logger.debug { "Sending process termination notification on socket $socketChannel: pid=${pid}" }
      val protocolChannel = ProcessInventoryServerSocketProtocol(session, socketChannel).forClient(config.clientDescription)
      val response = protocolChannel.sendDeviceProcessRemoval(device.serialNumber, pid)
      logger.debug { "Process termination response: $response" }
    }
  }

  /** Collects and process all [TrackDeviceResponsePayload] messages from the [ProcessInventoryServer]. */
  private suspend fun trackDeviceRequestsFromServer() {
    val processMap = mutableMapOf<Int, JdwpProcessProperties>()

    // Note: This is a long-running connection that should remain active as long
    // as the device is connected. If the server becomes unavailable, a new connection
    // is opened automatically (until the device is disconnected)
    server.withClientSocket { newServerInstance, socketChannel ->
      logger.info { "Tracking requests from server socket '$socketChannel'" }

      val protocolChannel = ProcessInventoryServerSocketProtocol(session, socketChannel).forClient(config.clientDescription)

      // If a new server has just been started, send it the full list of known
      // processes
      if (newServerInstance) {
        val currentList = processMap.values
        if (currentList.isNotEmpty()) {
          logger.debug { "New server has started, sending list of" + " ${currentList.size} know processes to socket $socketChannel" }
          protocolChannel.sendDeviceProcessInfoUpdates(
            device.serialNumber,
            processInfoUpdateList = currentList.map { it.toJdwpProcessInfoProto() },
            removedProcessList = emptyList(),
          )
        }
      }

      logger.debug { "Start collecting ${ProcessInventoryServerProto.ProcessUpdates::class.java.simpleName} from socket $socketChannel" }
      protocolChannel.trackDeviceRequests(device.serialNumber).collect { response ->
        logger.verbose { "Received tracker response: ${TextFormat.shortDebugString(response)}" }

        // Signal that we are actively collecting messages
        //
        // Note: We are guaranteed to always get at least one message from the
        // `trackerDeviceRequests` flow (a "process list update" message). This means
        // setting the completable here is guaranteed to be almost "immediate"
        if (!trackerIsActiveDeferred.isCompleted) {
          logger.verbose { "Completing \"tracker active\" deferred" }
          trackerIsActiveDeferred.complete(Unit)
        }

        if (response.hasTrackDeviceResponsePayload()) {
          when (response.trackDeviceResponsePayload.responseCase) {
            TrackDeviceResponsePayload.ResponseCase.PROCESS_UPDATES -> {
              // One or more process properties have been updated
              applyProcessUpdates(response.trackDeviceResponsePayload.processUpdates, processMap)
            }

            TrackDeviceResponsePayload.ResponseCase.PROCESS_COMMAND -> {
              // Forward command to our shared flow for collectors
              val emitted = processCommandMutableSharedFlow.tryEmit(response.trackDeviceResponsePayload.processCommand)
              assert(emitted) { "SharedFlow should be non-suspending" }
            }

            TrackDeviceResponsePayload.ResponseCase.PROCESS_COMMAND_REPLY -> {
              // Forward command to our shared flow for collectors
              val emitted = processCommandReplyMutableSharedFlow.tryEmit(response.trackDeviceResponsePayload.processCommandReply)
              assert(emitted) { "SharedFlow should be non-suspending" }
            }

            TrackDeviceResponsePayload.ResponseCase.RESPONSE_NOT_SET,
            null -> {
              logger.info { "Unknown process update message: $response" }
            }
          }
        }
      }
    }
  }

  private fun applyProcessUpdates(
    processUpdates: ProcessInventoryServerProto.ProcessUpdates,
    processMap: MutableMap<Int, JdwpProcessProperties>,
  ) {
    processUpdates.processUpdateList.forEach { processUpdate -> applyProcessUpdate(processMap, processUpdate) }
  }

  private fun applyProcessUpdate(processMap: MutableMap<Int, JdwpProcessProperties>, update: ProcessUpdate) {
    when {
      update.hasProcessUpdated() -> {
        // We got a new set of process properties, merge them into our current ones
        val processInfo = update.processUpdated
        logger.debug { "Process ${processInfo.pid} has been updated: $processInfo" }

        processMap.updateProcess(processInfo.pid) { currentProperties ->
          currentProperties.mergeWith(processInfo.toJdwpProcessProperties())
        }
      }

      update.hasProcessTerminatedPid() -> {
        val pid = update.processTerminatedPid
        logger.debug { "Process $pid has exited" }

        processMap.remove(pid)
      }
    }

    // Only update if `close` has not been called, since we don't want to overwrite
    // the `closed` state (i.e. empty list)
    scope.ensureActive()
    processPropertiesListMutableStateFlow.value = processMap.values.toList()
  }

  private fun MutableMap<Int, JdwpProcessProperties>.updateProcess(pid: Int, update: (JdwpProcessProperties) -> JdwpProcessProperties) {
    this[pid] = update(this.computeIfAbsent(pid) { JdwpProcessProperties(it) })
  }

  /**
   * Converts a [ProcessInventoryServerProto.JdwpProcessInfo] from the inventory server, into a [JdwpProcessProperties] for internal use.
   */
  private fun ProcessInventoryServerProto.JdwpProcessInfo.toJdwpProcessProperties(): JdwpProcessProperties {
    val sourceProto = this
    return JdwpProcessProperties(
      pid = sourceProto.pid,
      processName = sourceProto.processName.toOptionalString(),
      packageNames = sourceProto.packageNames.toOptionalStringList(),
      userId = sourceProto.userId.toOptionalInt(),
      vmIdentifier = sourceProto.vmIdentifier.toOptionalString(),
      instructionSet = sourceProto.instructionSet.toOptionalInstructionSet(),
      jvmFlags = sourceProto.jvmFlags.toOptionalString(),
      isNativeDebuggable = sourceProto.nativeDebuggable.toOptionalBoolean(),
      isWaitingForDebugger = sourceProto.waitingForDebugger.toOptionalBoolean(),
      features = sourceProto.features.toOptionalStringList(),
    )
  }

  /**
   * Converts a [JdwpProcessProperties] from a local [JdwpProcessProperties] to a [ProcessInventoryServerProto.JdwpProcessInfo] for sending
   * to the inventory server.
   */
  private fun JdwpProcessProperties.toJdwpProcessInfoProto(): ProcessInventoryServerProto.JdwpProcessInfo {
    val sourceProperties = this
    return ProcessInventoryServerProto.JdwpProcessInfo.newBuilder()
      .also { proto ->
        sourceProperties.pid.also { proto.pid = it }
        proto.processName = sourceProperties.processName.toOptionalStringProto()
        proto.packageNames = sourceProperties.packageNames.toOptionalStringListProto()
        proto.userId = sourceProperties.userId.toOptionalInt32Proto()
        proto.vmIdentifier = sourceProperties.vmIdentifier.toOptionalStringProto()
        proto.instructionSet = sourceProperties.instructionSet.toOptionalInstructionSetProto()
        proto.vmIdentifier = sourceProperties.vmIdentifier.toOptionalStringProto()
        proto.jvmFlags = sourceProperties.jvmFlags.toOptionalStringProto()
        @Suppress("DEPRECATION")
        proto.nativeDebuggable = sourceProperties.isNativeDebuggable.toOptionalBoolProto()
        proto.waitingForDebugger = sourceProperties.isWaitingForDebugger.toOptionalBoolProto()
        proto.features = sourceProperties.features.toOptionalStringListProto()
      }
      .build()
  }

  companion object {

    private fun ProcessInventoryServerProto.OptionalString.toOptionalString(): OptionalValue<String> {
      return if (hasValue) {
        OptionalValue.of(stringValue)
      } else if (isError) {
        OptionalValue.ofError(errorMessage)
      } else {
        OptionalValue.empty()
      }
    }

    private fun ProcessInventoryServerProto.OptionalStringList.toOptionalStringList(): OptionalValue<List<String>> {
      return if (hasValue) {
        OptionalValue.of(stringsValueList)
      } else if (isError) {
        OptionalValue.ofError(errorMessage)
      } else {
        OptionalValue.empty()
      }
    }

    private fun ProcessInventoryServerProto.OptionalInt32.toOptionalInt(): OptionalValue<Int> {
      return if (hasValue) {
        OptionalValue.of(int32Value)
      } else if (isError) {
        OptionalValue.ofError(errorMessage)
      } else {
        OptionalValue.empty()
      }
    }

    private fun ProcessInventoryServerProto.OptionalBool.toOptionalBoolean(): OptionalValue<Boolean> {
      return if (hasValue) {
        OptionalValue.of(boolValue)
      } else if (isError) {
        OptionalValue.ofError(errorMessage)
      } else {
        OptionalValue.empty()
      }
    }

    private fun ProcessInventoryServerProto.OptionalString.toOptionalInstructionSet(): OptionalValue<InstructionSet> {
      return if (hasValue) {
        OptionalValue.of(InstructionSet.fromString(stringValue))
      } else if (isError) {
        OptionalValue.ofError(errorMessage)
      } else {
        OptionalValue.empty()
      }
    }

    private fun OptionalValue<String>.toOptionalStringProto(): ProcessInventoryServerProto.OptionalString {
      return ProcessInventoryServerProto.OptionalString.newBuilder()
        .also { proto ->
          if (isError) {
            proto.isError = true
            proto.errorMessage = getErrorMessageOrThrow()
          } else if (hasValue) {
            proto.hasValue = true
            proto.stringValue = getOrThrow()
          }
        }
        .build()
    }

    private fun OptionalValue<InstructionSet>.toOptionalInstructionSetProto(): ProcessInventoryServerProto.OptionalString {
      return ProcessInventoryServerProto.OptionalString.newBuilder()
        .also { proto ->
          if (isError) {
            proto.isError = true
            proto.errorMessage = getErrorMessageOrThrow()
          } else if (hasValue) {
            proto.hasValue = true
            proto.stringValue = getOrThrow().text
          }
        }
        .build()
    }

    private fun OptionalValue<Int>.toOptionalInt32Proto(): ProcessInventoryServerProto.OptionalInt32 {
      return ProcessInventoryServerProto.OptionalInt32.newBuilder()
        .also { proto ->
          if (isError) {
            proto.isError = true
            proto.errorMessage = getErrorMessageOrThrow()
          } else if (hasValue) {
            proto.hasValue = true
            proto.int32Value = getOrThrow()
          }
        }
        .build()
    }

    private fun OptionalValue<Boolean>.toOptionalBoolProto(): ProcessInventoryServerProto.OptionalBool {
      return ProcessInventoryServerProto.OptionalBool.newBuilder()
        .also { proto ->
          if (isError) {
            proto.isError = true
            proto.errorMessage = getErrorMessageOrThrow()
          } else if (hasValue) {
            proto.hasValue = true
            proto.boolValue = getOrThrow()
          }
        }
        .build()
    }

    private fun OptionalValue<List<String>>.toOptionalStringListProto(): ProcessInventoryServerProto.OptionalStringList {
      return ProcessInventoryServerProto.OptionalStringList.newBuilder()
        .also { proto ->
          if (isError) {
            proto.isError = true
            proto.errorMessage = getErrorMessageOrThrow()
          } else if (hasValue) {
            proto.hasValue = true
            proto.addAllStringsValue(getOrThrow())
          }
        }
        .build()
    }
  }
}
