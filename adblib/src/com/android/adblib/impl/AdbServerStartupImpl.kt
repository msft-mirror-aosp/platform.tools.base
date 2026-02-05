/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.adblib.impl

import com.android.adblib.AdbServerConfiguration
import com.android.adblib.AdbServerController
import com.android.adblib.AdbServerStartup
import com.android.adblib.AdbSessionHost
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal class AdbServerStartupImpl(private val host: AdbSessionHost) : AdbServerStartup {

  private val adbServerConfiguration =
    MutableStateFlow(
      AdbServerConfiguration(adbPath = null, serverPort = null, isUserManaged = false, isUnitTest = false, envVars = emptyMap())
    )

  private val serverController = AdbServerController.createServerController(host, adbServerConfiguration)

  override suspend fun start(port: Int, timeout: Long, unit: TimeUnit): Int {
    host.logger.debug { "Starting ADB server on port $port." }
    host.timeProvider.withErrorTimeout(timeout, unit) {
      val adbPath = withContext(host.blockingIoDispatcher) { getAdbFile() }
      adbServerConfiguration.update { configuration -> configuration.copy(adbPath = adbPath, serverPort = port) }
      serverController.start()
    }
    // We only attempt to spin up the Adb Server on the requested port
    return port
  }

  private fun getAdbFile(): Path {
    val os = System.getProperty("os.name")
    val adbExecutableName = if (os.startsWith("Windows")) "adb.exe" else "adb"
    return findOnPath(adbExecutableName) ?: throw IOException("Couldn't locate '$adbExecutableName' on PATH")
  }

  private fun findOnPath(executableName: String): Path? {
    val pathEnvVariable = System.getenv("PATH") ?: throw IOException("No PATH environmental variable is defined")
    for (binDir in pathEnvVariable.split(File.pathSeparator)) {
      val file = Paths.get(binDir).resolve(executableName)
      if (Files.isRegularFile(file)) {
        return file
      }
    }
    host.logger.debug { "$executableName could not be located in any of the $pathEnvVariable folders" }
    return null
  }
}
