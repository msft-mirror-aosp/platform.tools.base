/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.fakeadbserver.hostcommandhandlers

import com.android.fakeadbserver.DeviceStateSelector
import com.android.fakeadbserver.FakeAdbServer
import com.android.fakeadbserver.MdnsService
import com.android.fakeadbserver.ServiceType
import com.android.fakeadbserver.statechangehubs.MdnsStateChangeHandlerFactory
import com.android.fakeadbserver.statechangehubs.StateChangeHandlerFactory
import com.android.server.adb.protos.MdnsProto
import java.io.IOException
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope

/**
 * host:track-mdns-services is a persistent connection that tracks mDNS service registrations and de-registrations. Every time an event
 * occurs, the list of current mDNS services is sent in protobuf format.
 */
class TrackMdnsServicesCommandHandler : HostCommandHandler() {

  override fun handles(command: String): Boolean {
    return command == COMMAND
  }

  override fun invoke(
    fakeAdbServer: FakeAdbServer,
    socketScope: CoroutineScope,
    responseSocket: Socket,
    deviceSelector: DeviceStateSelector,
    command: String,
    args: String,
  ): Boolean {
    val queue =
      fakeAdbServer.mdnsChangeHub.subscribe(
        object : MdnsStateChangeHandlerFactory {
          override fun createMdnsServiceListChangedHandler(
            serviceList: Collection<MdnsService>
          ): Callable<StateChangeHandlerFactory.HandlerResult> {
            return sendMdnsServiceList(fakeAdbServer, responseSocket)
          }
        }
      ) ?: return false // Server has shutdown before we are able to start listening to the queue.
    try {
      writeOkay(responseSocket.getOutputStream()) // Send ok first.

      // Then send over the full list of mdns services before going into monitoring mode.
      sendMdnsServiceList(fakeAdbServer, responseSocket).call()
      while (true) {
        try {
          // Grab a command from the queue (take()), and execute the command (get(), as
          // defined above in the MdnsStateChangeHandlerFactory) as-is in the current
          // thread so that we can send the message in the opened connection (which only
          // exists in the current thread).
          if (!queue.take().call().mShouldContinue) {
            break
          }
        } catch (_: InterruptedException) {
          // Most likely server going into shutdown, so quit out of the loop.
          break
        }
      }
    } catch (_: Exception) {} finally {
      fakeAdbServer.mdnsChangeHub.unsubscribe(queue)
    }
    return false // The only way we can get here is if the connection/server was terminated.
  }

  private fun sendMdnsServiceList(server: FakeAdbServer, responseSocket: Socket): Callable<StateChangeHandlerFactory.HandlerResult> {
    return Callable {
      try {
        val stream = responseSocket.getOutputStream()
        val serviceList = server.mdnsServicesCopy.get()
        val protoServicesBuilder = MdnsProto.MdnsServices.newBuilder()
        serviceList.forEach { fakeService ->
          val protoMdnsServiceBuilder =
            MdnsProto.MdnsService.newBuilder()
              .setInstance(fakeService.instanceName)
              .setService(fakeService.serviceName)
              .setIpv4(fakeService.deviceAddress.hostString)
              .setPort(fakeService.deviceAddress.port)

          when (fakeService.serviceType) {
            ServiceType.TLS -> {
              val tlsService = MdnsProto.ServiceAdbTls.newBuilder().setService(protoMdnsServiceBuilder.build()).build()
              protoServicesBuilder.addTls(tlsService)
            }
            ServiceType.TCP -> {
              val tcpService = MdnsProto.ServiceAdbTcp.newBuilder().setService(protoMdnsServiceBuilder.build()).build()
              protoServicesBuilder.addTcp(tcpService)
            }

            ServiceType.PAIRING -> {
              val pairingService = MdnsProto.ServiceAdbPairing.newBuilder().setService(protoMdnsServiceBuilder.build()).build()
              protoServicesBuilder.addPair(pairingService)
            }
          }
        }
        val mdnsProto = protoServicesBuilder.build()
        val byteArray = mdnsProto.toByteArray()
        write4ByteHexIntString(stream, byteArray.size)
        stream.write(byteArray)
        stream.flush()
        return@Callable StateChangeHandlerFactory.HandlerResult(true)
      } catch (e: InterruptedException) {
        return@Callable StateChangeHandlerFactory.HandlerResult(false)
      } catch (e: ExecutionException) {
        return@Callable StateChangeHandlerFactory.HandlerResult(false)
      } catch (e: IOException) {
        return@Callable StateChangeHandlerFactory.HandlerResult(false)
      }
    }
  }

  companion object {
    const val COMMAND = "track-mdns-services"
  }
}
