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

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.logging.Logger
import java.util.regex.Pattern
import kotlin.streams.asSequence

const val DEFAULT_EMULATOR_GRPC_PORT = 8554

private val LOG = Logger.getLogger("EmulatorControlHelper")

data class EmulatorGrpcInfo(val port: Int, val token: String? = null)

/**
 * Finds and returns information about an emulator gRPC service. It will find all discovery files and return the discovered emulator with
 * the given serial number.
 *
 * @param deviceSerial The serial number of the emulator device (e.g. "emulator-5554").
 * @return An [EmulatorGrpcInfo] object. If not found, returns fallback with default port and no token.
 */
fun findGrpcInfo(deviceSerial: String): EmulatorGrpcInfo {
  try {
    val fileNamePattern = Pattern.compile("pid_\\d+.ini")
    val directory = computeRegistrationDirectoryContainer()?.resolve("avd/running")
    LOG.info("Searching for emulator gRPC discovery files in directory: $directory")
    if (directory == null || !Files.exists(directory)) {
      LOG.warning("Discovery directory $directory does not exist or is null.")
      return EmulatorGrpcInfo(DEFAULT_EMULATOR_GRPC_PORT)
    }
    return Files.list(directory).use { files ->
      files
        .asSequence()
        .map { file ->
          if (fileNamePattern.matcher(file.fileName.toString()).matches()) {
            findGrpcInfo(deviceSerial, file)
          } else {
            null
          }
        }
        .filterNotNull()
        .firstOrNull() ?: EmulatorGrpcInfo(DEFAULT_EMULATOR_GRPC_PORT)
    }
  } catch (exception: Throwable) {
    LOG.fine("Failed to parse emulator gRPC port, fallback to default, exception $exception")
    return EmulatorGrpcInfo(DEFAULT_EMULATOR_GRPC_PORT)
  }
}

/**
 * Finds and returns information about an emulator gRPC service from the given discovery file.
 *
 * @param deviceSerial The serial number of the emulator device.
 * @param file The discovery file to parse.
 * @return An [EmulatorGrpcInfo] object if the discovery file contains information about the given emulator, or `null` otherwise.
 */
fun findGrpcInfo(deviceSerial: String, file: Path): EmulatorGrpcInfo? {
  val discovered = mutableMapOf<String, String>()
  Files.readAllLines(file).forEach { line ->
    val keyValuePair = line.split("=", limit = 2)
    if (keyValuePair.size == 2) {
      discovered[keyValuePair[0]] = keyValuePair[1]
    }
  }
  val serial = discovered.getOrDefault("port.serial", "")
  val matchedAvd = ("emulator-" + serial == deviceSerial)

  return if (matchedAvd) {
    EmulatorGrpcInfo(
      discovered.getOrDefault("grpc.port", DEFAULT_EMULATOR_GRPC_PORT.toString()).toInt(),
      discovered.getOrDefault("grpc.token", null),
    )
  } else {
    null
  }
}

/** Returns the Emulator registration directory. */
fun computeRegistrationDirectoryContainer(): Path? {
  val os = System.getProperty("os.name").lowercase(Locale.ROOT)
  return when {
    os.startsWith("mac") -> {
      Paths.get(System.getenv("HOME") ?: "/", "Library", "Caches", "TemporaryItems")
    }

    os.startsWith("win") -> {
      Paths.get(System.getenv("LOCALAPPDATA") ?: "/", "Temp")
    }

    else -> { // Linux and Chrome OS.
      for (dirstr in
        arrayOf(
          System.getenv("XDG_RUNTIME_DIR"),
          "/run/user/${getRealUid()}",
          System.getenv("ANDROID_EMULATOR_HOME"),
          System.getenv("ANDROID_PREFS_ROOT"),
          System.getenv("ANDROID_SDK_HOME"),
          Paths.get(System.getenv("HOME") ?: "/", ".android").toString(),
          // For integration tests, we use java property.
          System.getProperty("android.emulator.home"),
        )) {
        if (dirstr == null) {
          continue
        }
        try {
          val dir = Paths.get(dirstr)
          val exists = Files.isDirectory(dir)
          val writable = Files.isWritable(dir)
          LOG.info("[EmulatorControlHelper] Checking dir $dirstr: exists=$exists, writable=$writable")
          if (exists && writable) {
            return dir
          }
        } catch (exception: InvalidPathException) {
          LOG.finer("Failed to parse dir ${dirstr}, exception $exception")
        }
      }
      null
    }
  }
}

private fun getRealUid(): String? {
  try {
    val command = "id -u"
    val process = Runtime.getRuntime().exec(command)
    process.inputStream.use {
      val result = String(it.readBytes(), StandardCharsets.UTF_8).trim()
      LOG.info("[EmulatorControlHelper] getRealUid (id -u) returned: '$result'")
      if (result.isEmpty()) {
        return null
      }
      return result
    }
  } catch (e: IOException) {
    LOG.warning("[EmulatorControlHelper] getRealUid failed: $e")
    return null
  }
}
