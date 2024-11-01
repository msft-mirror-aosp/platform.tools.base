/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.android.adblib.ddmlibcompatibility

import com.android.adblib.AdbServerChannelProvider
import com.android.adblib.AdbServerConfiguration
import com.android.adblib.AdbServerController
import com.android.adblib.testing.FakeAdbSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.TimeUnit

class AdbLibAndroidDebugBridgeTest {

    val config = MutableStateFlow<AdbServerConfiguration>(
        AdbServerConfiguration(
            null, null, false, false, emptyMap()
        )
    )

    @Test
    fun startReturnsFalse_whenItTimesOut() {
        val session = FakeAdbSession()
        val adbServerController = FakeAdbServerController(startDelayMs = 200)
        val bridge = AdbLibAndroidDebugBridge(session, adbServerController, config)

        // Act
        val result = bridge.startAdb(50, TimeUnit.MILLISECONDS)

        // Assert
        assertFalse(result)

    }

    // TODO: Add many more tests

    private class FakeAdbServerController(private val startDelayMs: Long = 0) : AdbServerController {

        override val channelProvider: AdbServerChannelProvider
            get() {
                throw UnsupportedOperationException("Not yet implemented")
            }

        override var isStarted: Boolean = false
            private set

        override suspend fun start() {
            delay(startDelayMs)
            isStarted = true
        }

        override suspend fun stop() {
            throw UnsupportedOperationException("Not yet implemented")
        }

        override fun close() {
            throw UnsupportedOperationException("Not yet implemented")
        }
    }
}
