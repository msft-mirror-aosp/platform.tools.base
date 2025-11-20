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
package com.android.adblib.testingutils

import com.android.fakeadbserver.DeviceState
import com.android.fakeadbserver.FAKE_ADB_SERVER_EXECUTOR_TIMEOUT_MS
import com.android.fakeadbserver.FakeAdbServer
import com.android.fakeadbserver.FakeDeviceCreator
import com.android.fakeadbserver.hostcommandhandlers.ListDevicesCommandHandler.Companion.DEFAULT_SPEED
import com.android.sdklib.AndroidApiLevel
import java.util.concurrent.TimeUnit
import org.junit.rules.ExternalResource

/**
 * This rule sets up `com.android.fakeadbserver.FakeAdbServer`.
 *
 * @param configure An optional lambda to apply additional customization to the
 * [FakeAdbServer] after the default command handlers have been installed.
 */
open class FakeAdbServerRule(
    private val configure: (FakeAdbServer.Builder.() -> Unit)? = null
) : ExternalResource(), FakeDeviceCreator {

    lateinit var adbServer: FakeAdbServer
        private set

    public override fun before() {
        adbServer = FakeAdbServer.Builder()
            .installDefaultCommandHandlers()
            .apply { configure?.invoke(this) }
            .build()
            .also { it.start() }
    }

    override fun after() {
        adbServer.stop()
        adbServer.close()
    }

    override fun connectDevice(
        deviceId: String,
        manufacturer: String,
        deviceModel: String,
        release: String,
        sdk: AndroidApiLevel,
        hostConnectionType: DeviceState.HostConnectionType,
        maxSpeedMbps: Long,
        negotiatedSpeedMbps: Long
    ): DeviceState {
        return connectDevice(
            deviceId = deviceId,
            manufacturer = manufacturer,
            deviceModel = deviceModel,
            release = release,
            sdk = sdk,
            hostConnectionType = hostConnectionType,
            cpuAbi = "x86_64",
            properties = emptyMap(),
            isRoot = false,
            maxSpeedMbps = maxSpeedMbps,
            negotiatedSpeedMbps = negotiatedSpeedMbps
        )
    }

    fun connectDevice(
        deviceId: String,
        manufacturer: String,
        deviceModel: String,
        release: String,
        sdk: AndroidApiLevel,
        hostConnectionType: DeviceState.HostConnectionType,
        cpuAbi: String = "x86_64",
        properties: Map<String, String> = emptyMap(),
        isRoot: Boolean = false,
        maxSpeedMbps: Long = DEFAULT_SPEED,
        negotiatedSpeedMbps: Long = DEFAULT_SPEED,
    ): DeviceState {
        val deviceState = adbServer.connectDevice(
            deviceId = deviceId,
            manufacturer = manufacturer,
            deviceModel = deviceModel,
            release = release,
            sdk = sdk,
            hostConnectionType = hostConnectionType,
            isRoot = isRoot,
            cpuAbi = cpuAbi,
            properties = properties,
            maxSpeedMbps = maxSpeedMbps,
            negotiatedSpeedMbps = negotiatedSpeedMbps,
        ).get(FAKE_ADB_SERVER_EXECUTOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ?: throw IllegalArgumentException()
        deviceState.deviceStatus = DeviceState.DeviceStatus.ONLINE
        return deviceState
    }

    override fun disconnectDevice(deviceId: String) {
        adbServer.disconnectDevice(deviceId)
            .get(FAKE_ADB_SERVER_EXECUTOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }
}
