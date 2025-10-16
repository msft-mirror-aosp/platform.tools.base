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
package com.android.adblib

/**
 * mDNS services as returned by ADB "host:track-mdns-services" query. See
 * https://source.corp.google.com/h/googleplex-android/platform/superproject/main/+/c51903756bff7744c589ca2190df9d161494f37d:packages/modules/adb/docs/dev/services.md
 * and
 * https://source.corp.google.com/h/googleplex-android/platform/superproject/main/+/124967b0fb712eb69ca625a11448e3f169e07e2c:packages/modules/adb/proto/adb_host.proto
 * for more information.
 */
data class MdnsServices(
  val tcpMdnsServices: List<MdnsTcpService>,
  val tlsMdnsServices: List<MdnsTlsService>,
  val pairingMdnsServices: List<MdnsPairingService>,
)

data class MdnsTcpService(val mdnsService: MdnsTrackServiceInfo)

data class MdnsTlsService(val service: MdnsTrackServiceInfo, val knownDevice: Boolean)

data class MdnsPairingService(val mdnsService: MdnsTrackServiceInfo)

/*
For explanation about the meaning of instance, service, and domain
refer to RFC 6763 (mdns-sd).
 */
data class ServiceInstanceName(
  // e.g.: "adb-43081FDAS000ST-GIVKML"
  val instance: String,
  // e.g.: _adb-tls-connect._tcp
  val service: String,
  // e.g.: local
  val domain: String,
)

data class MdnsTrackServiceInfo(
  val serviceInstanceName: ServiceInstanceName,
  val ipv4: String,
  // IPv6 is designed to allow and encourage interfaces to have multiple addresses simultaneously,
  // each serving a different purpose.
  val ipv6: List<String>,
  val port: Int,
  // Comes from device property "ro.product.model"
  val deviceModel: String?,
  // Comes from device property "ro.build.version.sdk_full"
  val buildVersionSdkFull: String?,
  // The name of the device, e.g. "Foo's Pixel 10".
  val givenName: String?,
  // Comes from device property "ro.serialno"
  val serial: String?,
  // Used by clients to manage changes to the MdnsService.
  val mdnsServiceVersion: String?
)
