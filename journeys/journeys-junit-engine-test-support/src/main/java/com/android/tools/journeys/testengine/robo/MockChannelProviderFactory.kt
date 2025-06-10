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

package com.android.tools.journeys.testengine.robo

import io.grpc.ManagedChannel
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder

class MockChannelProviderFactory : ChannelProviderFactory {

    override fun createChannelProvider(): (targetEndpoint: String, accessTokenPath: String) -> ManagedChannel {
        try {
            val serverName = InProcessServerBuilder.generateName()
            val fakeService = FakeCrawlerService()
            InProcessServerBuilder.forName(serverName)
                .directExecutor()
                .addService(fakeService)
                .build()
                .start()
            return { _, _ ->
                InProcessChannelBuilder.forName(serverName).directExecutor().build()
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to create channel for testing", e)
        }
    }
}
