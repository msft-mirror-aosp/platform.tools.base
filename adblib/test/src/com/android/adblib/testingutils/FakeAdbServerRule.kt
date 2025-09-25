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
import com.android.fakeadbserver.FakeAdbServer
import com.android.fakeadbserver.hostcommandhandlers.ListDevicesCommandHandler.Companion.DEFAULT_SPEED
import com.android.sdklib.AndroidApiLevel
import java.util.concurrent.TimeUnit
import org.junit.rules.ExternalResource

/**
 * This rule sets up `com.android.fakeadbserver.FakeAdbServer`.
 *
 * @param configure An optional lambda to customize the [FakeAdbServer]
 * before it is built and started. If not provided, it defaults to installing
 * the default command handlers.
 */
open class FakeAdbServerRule(
    configure: (FakeAdbServer.Builder.() -> FakeAdbServer.Builder)? = null
) : ExternalResource() {

    lateinit var adbServer: FakeAdbServer
        private set

    private val configure: FakeAdbServer.Builder.() -> FakeAdbServer.Builder = configure ?: {
        installDefaultCommandHandlers()
    }

    public override fun before() {
        adbServer = FakeAdbServer.Builder().configure().build().also { it.start() }
    }

    override fun after() {
        adbServer.stop()
        adbServer.close()
    }

    fun connectDevice(
        deviceId: String,
        manufacturer: String,
        deviceModel: String,
        release: String,
        sdk: AndroidApiLevel,
        hostConnectionType: DeviceState.HostConnectionType,
        maxSpeedMbps: Long = DEFAULT_SPEED,
        negotiatedSpeedMbps: Long = DEFAULT_SPEED,
    ): DeviceState {
        // TODO: wait for device to also show up in `AndroidDebugBridge.bridge.devices`
        return adbServer.connectDevice(
            deviceId,
            manufacturer,
            deviceModel,
            release,
            sdk,
            hostConnectionType,
            maxSpeedMbps = maxSpeedMbps,
            negotiatedSpeedMbps = negotiatedSpeedMbps,
        ).get(FAKE_ADB_SERVER_EXECUTOR_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            ?: throw IllegalArgumentException()
    }
}
