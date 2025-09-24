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
package com.android.tools.journeys.testengine.adapter

import com.android.tools.journeys.proto.JourneyRunEvent

/**
 * A default adapter that assumes the incoming bytes are already in the [JourneyRunEvent]
 * format and passes them directly to the consumer.
 *
 * This adapter is intended for use with backends that are designed to produce events
 * in the native format.
 *
 * @param config Configuration for the [DefaultResultAdapter].
 */
class DefaultResultAdapter(
    private val config: JourneysResultAdapter.JourneysResultAdapterConfig
) : JourneysResultAdapter {

    override fun process(rawArtifactBytes: ByteArray) {
        try {
            val event = JourneyRunEvent.parseFrom(rawArtifactBytes)
            config.consumer.onEvent(event)
        } catch (e: Exception) {
            println("Error while processing raw artifact bytes: ${e.message}")
        }
    }
}
