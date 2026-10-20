/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.deploy.service

import com.android.tools.deployer.createAndStartAdbServerControllerBlocking
import com.android.tools.deployer.getAdbServerPort
import com.android.utils.ILogger
import com.android.utils.StdLogger
import com.google.common.annotations.VisibleForTesting

object DeployServiceMain {
  @VisibleForTesting var logger: ILogger = StdLogger(StdLogger.Level.INFO)

  /**
   * Blocking main function that starts a [DeployServer] if the required args are not specified a message is printed and the program is
   * exited.
   *
   * @param args The arguments required are port [port number to bind to] adbPath [path to adb executable]
   */
  @JvmStatic
  fun main(args: Array<String>) {
    val argsMap = mapArgs(args)
    if (!argsMap.containsKey("port") || !argsMap.containsKey("adbPath")) {
      printUsage()
      return
    }
    val port = argsMap["port"]!!.toInt()
    val adbPath = argsMap["adbPath"]!!

    val logLevel = StdLogger.Level.INFO
    createAndStartAdbServerControllerBlocking(adbPath, getAdbServerPort(logger), logLevel).use { controller ->
      AdbHelper.initAdbSession(controller, logLevel).use { session ->
        DeployServer(session).runServer(port)
      }
    }
  }

  private fun printUsage() {
    logger.error(null, "DeployServiceMain --port [port number] --adbPath [/path/to/adb]")
  }

  private fun mapArgs(args: Array<String>): MutableMap<String, String> {
    val argsMap = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size) {
      if (args[i].startsWith("--")) {
        argsMap.put(args[i].substring(2), args[++i])
      }
      i++
    }
    return argsMap
  }
}
