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

package com.android.tools.androidtest.testengine

import com.google.testing.platform.proto.api.core.AndroidTestDeviceInfoProto.AndroidTestDeviceInfo

/** A collector to gather device information via ADB. */
class AndroidTestDeviceInfoCollector(private val adbController: AdbController, private val deviceSerial: String) {

  /** Collects device information and returns it as an [AndroidTestDeviceInfo] proto. */
  fun collect(): AndroidTestDeviceInfo {
    val properties = getDeviceProperties()
    val memInfo = adbController.runAdbShellCommand(deviceSerial, listOf("cat", "/proc/meminfo")).output.lines()
    val cpuInfo = adbController.runAdbShellCommand(deviceSerial, listOf("cat", "/proc/cpuinfo")).output.lines()

    val avdName = properties["ro.kernel.qemu.avd_name"] ?: properties["ro.boot.qemu.avd_name"] ?: ""
    val name = if (avdName.isNotEmpty()) avdName else deviceSerial

    return AndroidTestDeviceInfo.newBuilder()
      .setName(name)
      .setApiLevel(properties["ro.build.version.sdk"] ?: "")
      .setRamInBytes(getDeviceMemory(memInfo))
      .addAllProcessors(getDeviceProcessors(cpuInfo))
      .addAllAbis(properties["ro.product.cpu.abilist"]?.split(',') ?: emptyList())
      .setManufacturer(properties["ro.product.manufacturer"] ?: "")
      .setSerial(deviceSerial)
      .setAvdName(avdName)
      .setGradleDslDeviceName(getGradleDslDeviceName())
      .setModel(properties["ro.product.model"] ?: "")
      .build()
  }

  private fun getDeviceProperties(): Map<String, String> {
    val result = adbController.runAdbShellCommand(deviceSerial, listOf("getprop"))
    val properties = mutableMapOf<String, String>()
    if (result.exitCode == 0) {
      val regex = Regex("\\[(.*)\\]: \\[(.*)\\]")
      result.output.lines().forEach { line -> regex.find(line)?.let { match -> properties[match.groupValues[1]] = match.groupValues[2] } }
    }
    return properties
  }

  private fun getGradleDslDeviceName(): String {
    val key = "com.android.junit.engine.gradleManagedDeviceDslName[$deviceSerial]"
    return System.getProperty(key) ?: AgpTestSuiteInput.get(key) ?: ""
  }

  private fun getDeviceMemory(lines: List<String>): Long {
    for (line in lines) {
      val parts = line.split(':', ignoreCase = true, limit = 2)
      if (parts.size == 2 && parts[0].trim() == "MemTotal") {
        val valueParts = parts[1].trim().split(' ', ignoreCase = true, limit = 2)
        val ramSize = valueParts[0].toDoubleOrNull() ?: continue
        val unit = if (valueParts.size == 2) valueParts[1] else "kB"
        return when (unit) {
          "kB" -> (ramSize * 1000L).toLong()
          "MB" -> (ramSize * 1000L * 1000L).toLong()
          "GB" -> (ramSize * 1000L * 1000L * 1000L).toLong()
          "TB" -> (ramSize * 1000L * 1000L * 1000L * 1000L).toLong()
          else -> 0L
        }
      }
    }
    return 0L
  }

  private fun getDeviceProcessors(lines: List<String>): Iterable<String> {
    val processors = mutableSetOf<String>()
    lines.forEach { line ->
      val parts = line.split(':', ignoreCase = true, limit = 2)
      if (parts.size == 2 && parts[0].trim() == "model name") {
        processors.add(parts[1].trim())
      }
    }
    return processors
  }
}
