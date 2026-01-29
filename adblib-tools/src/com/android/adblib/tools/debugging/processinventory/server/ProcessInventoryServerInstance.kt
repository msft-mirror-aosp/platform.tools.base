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
package com.android.adblib.tools.debugging.processinventory.server

import com.android.adblib.AdbChannel
import com.android.adblib.AdbLogger
import com.android.adblib.AdbServerSocket
import com.android.adblib.AdbSession
import com.android.adblib.IsThreadSafe
import com.android.adblib.adbLogger
import com.android.adblib.property
import com.android.adblib.tools.debugging.processinventory.AdbLibToolsProcessInventoryServerProperties.UNUSED_DEVICE_REMOVAL_DELAY
import com.android.adblib.tools.debugging.processinventory.impl.ProcessInventoryServerSocketProtocol
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.Request
import com.android.adblib.tools.debugging.processinventory.protos.ProcessInventoryServerProto.Response
import com.android.adblib.utils.closeOnException
import com.android.adblib.withPrefix
import com.google.protobuf.TextFormat
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch

internal class ProcessInventoryServerInstance(
  private val session: AdbSession,
  config: ProcessInventoryServerConfiguration,
  private val parentScope: CoroutineScope,
  private val serverSocket: AdbServerSocket,
) {
  private val serverInstanceDescription = config.serverDescription + "-EphemeralInstanceId(#${nextInstanceId()})"

  private val logger = adbLogger(session).withPrefix("$session - $serverInstanceDescription - ")

  private val activeDevicesMap = DeviceMap(session, parentScope)

  fun runAsync(): Job =
    parentScope
      .launch {
        runCatching {
            logger.info { "Starting server at server socket '$serverSocket'" }
            while (true) {
              // Accept one connection and handle it asynchronously, ensuring socket is closed
              // in all cases (cancellation, errors and success)
              serverSocket.accept().closeOnException { socketChannel ->
                logger.debug { "Accepted new socket connection: $socketChannel" }
                launch { RequestHandler(session, logger, serverInstanceDescription, activeDevicesMap, socketChannel).handleRequest() }
                  .invokeOnCompletion {
                    logger.debug(it) { "Closing socket channel for request" }
                    socketChannel.close()
                  }
              }
            }
          }
          .onFailure { throwable ->
            currentCoroutineContext().ensureActive()
            // Log exception (nothing else we can do)
            logger.info(throwable) { "Accept operation failed due to unexpected error" }
          }
      }
      .also { it.invokeOnCompletion { logger.info { "Stopping server running at server socket '$serverSocket' (throwable='$it')" } } }

  /**
   * Handles a single server request asynchronously on a given [socketChannel].
   *
   * Note: Requests can be short (e.g. [Request.UpdateDeviceRequestPayload] ) or long-running (e.g. [Request.TrackDeviceRequestPayload]).
   */
  private class RequestHandler(
    session: AdbSession,
    private val logger: AdbLogger,
    serverInstanceDescription: String,
    private val activeDevicesMap: DeviceMap,
    private val socketChannel: AdbChannel,
  ) {

    private val protocolSocket = ProcessInventoryServerSocketProtocol(session, socketChannel).forServer(serverInstanceDescription)

    /**
     * Asynchronously handles a single server request on [socketChannel], closing the [socketChannel] when the request is done. If the
     * request fails, there is an attempt to send an error message to the peer.
     */
    suspend fun handleRequest() {
      runCatching {
          logger.verbose { "Processing one request on client socket '$socketChannel'" }
          processOneRequest()
        }
        .onFailure { throwable ->
          currentCoroutineContext().ensureActive()

          // Attempt to send error to peer, which may fail if socket has been closed
          runCatching {
            val requestName = protocolSocket.lastRequest?.payloadCase?.toString() ?: "<unknown>"
            protocolSocket.writeErrorResponse("Error processing request '$requestName' (${throwable.message})")
          }

          // Log exception (nothing else we can do)
          when (throwable) {
            is IOException,
            is TimeoutException -> {
              // IO errors can happen anytime (peer can disappear, close the socket, etc.)
              logger.debug(throwable) { "Error processing request " }
            }

            else -> {
              logger.info(throwable) { "Unexpected error processing request" }
            }
          }
        }
    }

    private suspend fun processOneRequest() {
      val request = protocolSocket.readRequest()
      logger.verbose { "Processing request ${request.payloadCase}" }
      processRequest(request).collect { response ->
        logger.verbose { "Sending one response: ${TextFormat.shortDebugString(response)}" }
        protocolSocket.writeResponse(response)
      }
      protocolSocket.shutdown()
      logger.verbose { "Done processing request ${request.payloadCase}" }
    }

    private fun processRequest(request: Request) = channelFlow {
      when (request.payloadCase) {
        Request.PayloadCase.TRACK_DEVICE_REQUEST_PAYLOAD -> {
          trackDeviceRequestFlow(request.trackDeviceRequestPayload).collect { send(it) }
        }

        Request.PayloadCase.UPDATE_DEVICE_REQUEST_PAYLOAD -> {
          notifyUpdateDevice(request.updateDeviceRequestPayload)
          emitOkResponse()
        }

        Request.PayloadCase.PROCESS_COMMAND_PAYLOAD -> {
          notifyProcessCommand(request.processCommandPayload)
          emitOkResponse()
        }

        Request.PayloadCase.PROCESS_COMMAND_REPLY_PAYLOAD -> {
          notifyProcessCommandReply(request.processCommandReplyPayload)
          emitOkResponse()
        }

        Request.PayloadCase.PAYLOAD_NOT_SET,
        null -> {
          emitErrorResponse("Request is not supported by this server")
        }
      }
    }

    /** Tracks changes to the processes of a given device for as long as the device is active. */
    private fun trackDeviceRequestFlow(trackDeviceRequest: Request.TrackDeviceRequestPayload): Flow<Response> = channelFlow {
      val deviceId = trackDeviceRequest.deviceId
      // Acquire device process catalog for device, collect it and emit response to our flow
      activeDevicesMap.withDeviceProcessCatalog(deviceId) { deviceCatalog ->
        coroutineScope {
          awaitAll(
            async {
              deviceCatalog.trackProcessUpdates().collect { processUpdates ->
                emitOkResponse { builder ->
                  builder.setTrackDeviceResponsePayload(Response.TrackDeviceResponsePayload.newBuilder().setProcessUpdates(processUpdates))
                }
              }
            },
            async {
              deviceCatalog.trackProcessCommands().collect {
                emitOkResponse { builder ->
                  builder.setTrackDeviceResponsePayload(Response.TrackDeviceResponsePayload.newBuilder().setProcessCommand(it))
                }
              }
            },
            async {
              deviceCatalog.trackProcessCommandReplies().collect {
                emitOkResponse { builder ->
                  builder.setTrackDeviceResponsePayload(Response.TrackDeviceResponsePayload.newBuilder().setProcessCommandReply(it))
                }
              }
            },
          )
        }
      }
    }

    private suspend fun notifyUpdateDevice(updateDeviceRequestPayload: Request.UpdateDeviceRequestPayload) {
      val deviceId = updateDeviceRequestPayload.deviceId
      activeDevicesMap.withDeviceProcessCatalog(deviceId) { deviceCatalog ->
        deviceCatalog.handleProcessUpdates(updateDeviceRequestPayload.processUpdates)
      }
    }

    private suspend fun notifyProcessCommand(processCommandPayload: Request.ProcessCommandPayload) {
      val deviceId = processCommandPayload.deviceId
      activeDevicesMap.withDeviceProcessCatalog(deviceId) { deviceCatalog ->
        deviceCatalog.handleProcessCommand(processCommandPayload.processCommand)
      }
    }

    private suspend fun notifyProcessCommandReply(processCommandReplyPayload: Request.ProcessCommandReplyPayload) {
      val deviceId = processCommandReplyPayload.deviceId
      activeDevicesMap.withDeviceProcessCatalog(deviceId) { deviceCatalog ->
        deviceCatalog.handleProcessCommandReply(processCommandReplyPayload.processCommandReply)
      }
    }

    private suspend fun ProducerScope<Response>.emitOkResponse(block: (Response.Builder) -> Unit = {}) {
      send(protocolSocket.buildOkResponse(block))
    }

    private suspend fun ProducerScope<Response>.emitErrorResponse(message: String) {
      send(protocolSocket.buildErrorResponse(message))
    }
  }

  /**
   * A simple wrapper for a thread-safe [Map] of [ProcessInventoryServerProto.DeviceId] to [DeviceProcessCatalog] instances.
   *
   * Note: Since there is no deterministic way of knowing when a device is disconnected, we rely on an "inactive timeout" specified in the
   * [removalDelay] parameter, i.e. when a device has not been queried or updated within that specified timeout, the device is removed from
   * the internal list of [DeviceProcessCatalog].
   */
  @IsThreadSafe
  private class DeviceMap(
    private val session: AdbSession,
    parentScope: CoroutineScope,
    private val removalDelay: Duration = session.property(UNUSED_DEVICE_REMOVAL_DELAY),
    clock: Clock = Clock.systemUTC(),
  ) {

    private val logger = adbLogger(session)

    private val map =
      UsageTrackingMap<ProcessInventoryServerProto.DeviceId, DeviceProcessCatalog>(
        logger,
        parentScope,
        removalDelay,
        clock,
        factory = { deviceId -> DeviceProcessCatalog(session, deviceId) },
      )

    inline fun <R> withDeviceProcessCatalog(deviceId: ProcessInventoryServerProto.DeviceId, block: (DeviceProcessCatalog) -> R): R {
      return map.withValue(deviceId) { deviceCatalog -> block(deviceCatalog) }
    }
  }

  companion object {

    private val instanceCount = AtomicLong(0)

    fun nextInstanceId(): Long {
      return instanceCount.incrementAndGet()
    }
  }
}
