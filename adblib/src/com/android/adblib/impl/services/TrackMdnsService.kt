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
package com.android.adblib.impl.services

import com.android.adblib.AdbChannel
import com.android.adblib.MdnsPairingService
import com.android.adblib.MdnsServices
import com.android.adblib.MdnsTcpService
import com.android.adblib.MdnsTlsService
import com.android.adblib.MdnsTrackServiceInfo
import com.android.adblib.ServiceInstanceName
import com.android.adblib.adbLogger
import com.android.adblib.impl.TimeoutTracker
import com.android.adblib.impl.TimeoutTracker.Companion.INFINITE
import com.android.adblib.utils.ResizableBuffer
import com.android.server.adb.protos.MdnsProto
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import kotlin.use
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

internal class TrackMdnsService(private val serviceRunner: AdbServiceRunner) {

  private val logger = adbLogger(host)

  private val host
    get() = serviceRunner.host

  fun invoke(timeout: Long, unit: TimeUnit): Flow<MdnsServices> =
    flow {
        val tracker = TimeoutTracker(host.timeProvider, timeout, unit)
        val workBuffer = ResizableBuffer()
        val service = "host:track-mdns-services"
        serviceRunner.startHostQuery(workBuffer, service, tracker).use { channel ->
          collectAdbResponses(channel, workBuffer, service, this)
        }
      }
      .flowOn(host.ioDispatcher)

  private suspend fun collectAdbResponses(
    channel: AdbChannel,
    workBuffer: ResizableBuffer,
    service: String,
    flowCollector: FlowCollector<MdnsServices>,
  ) {
    while (true) {
      // Note: We use an infinite timeout here, as the only way to end this request is to close
      //       the underlying ADB socket channel (or cancel the coroutine). This is by design.
      logger.debug { "\"${service}\" - waiting for next mdns tracking message" }
      val buffer = serviceRunner.readLengthPrefixedData(channel, workBuffer, INFINITE)
      val devices = parse(buffer)
      flowCollector.emit(devices)
    }
  }

  private fun parse(buffer: ByteBuffer): MdnsServices {
    val mdns = MdnsProto.MdnsServices.parseFrom(buffer)
    val tcp = mdns.tcpList.map { MdnsTcpService(it.service.toMdnsServiceInfo()) }
    val tls = mdns.tlsList.map { MdnsTlsService(it.service.toMdnsServiceInfo(), it.knownDevice) }
    val pairing = mdns.pairList.map { MdnsPairingService(it.service.toMdnsServiceInfo()) }
    return MdnsServices(tcp, tls, pairing)
  }

  private fun MdnsProto.MdnsService.toMdnsServiceInfo(): MdnsTrackServiceInfo {
    return MdnsTrackServiceInfo(
      ServiceInstanceName(instance, service, domain),
      ipv4,
      ipv6List,
      port,
      deviceModel,
      buildVersionSdkFull,
    )
  }
}
