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
package com.android.deploy.service

import com.android.adblib.AdbSession.Companion.create
import com.android.adblib.AdbSessionHost
import com.android.adblib.ddmlibcompatibility.AdbLibIDeviceManagerFactory
import com.android.adblib.ddmlibcompatibility.debugging.AdbLibClientManagerFactory.createClientManager
import com.android.adblib.tools.debugging.processinventory.ProcessInventoryServerConnection
import com.android.adblib.tools.debugging.processinventory.installProcessInventoryJdwpProcessPropertiesCollectorFactory
import com.android.adblib.tools.debugging.processinventory.server.ProcessInventoryServerConfiguration
import com.android.ddmlib.AdbHelper
import com.android.ddmlib.AdbInitOptions
import com.android.ddmlib.AndroidDebugBridge
import java.util.logging.Logger

private val logger: Logger = Logger.getLogger(AdbHelper::class.java.getName())

object AdbHelper {

    /**
     * Initializes the AndroidDebugBridge, using adblib or ddmlib.
     */
    fun initAndroidDebugBridge() {
        if (System.getProperty("deploy.service.use.adblib").toBoolean()) {
            logger.info("adblib is enabled")
            val host = AdbSessionHost()
            val session = create(host)

            val inventoryServerEnabled = {
                val enabled = System.getProperty("deploy.service.use.adblib.inventory.server").toBoolean()
                logger.info("adblib inventory server is $enabled")
                enabled
            }

            val inventoryServerConfig = GameToolsProcessInventoryServerConfiguration()
            val inventoryServerConnection =
                ProcessInventoryServerConnection.create(session, inventoryServerConfig)
            session.installProcessInventoryJdwpProcessPropertiesCollectorFactory(
                inventoryServerConnection,
                inventoryServerEnabled,
            )
            val options =
                AdbInitOptions.Builder()
                    .setIDeviceManagerFactory(AdbLibIDeviceManagerFactory(session))
                    .build()
            AndroidDebugBridge.init(options)
        } else {
            logger.info("adblib is disabled")
            AndroidDebugBridge.init(true)
        }
    }
}

private class GameToolsProcessInventoryServerConfiguration : ProcessInventoryServerConfiguration {

    override val clientDescription: String = "GameTools-ProcessInventoryServer-Client"
    override val serverDescription: String = "GameTools-ProcessInventoryServer-Server"
}
