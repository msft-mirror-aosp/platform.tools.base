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

package com.android.tools.journeys.testengine.adapter.consumer

import com.android.tools.journeys.proto.JourneyRunEvent
import java.util.Base64

/**
 * An implementation of [JourneyRunEventConsumer] that serializes each event
 * and writes it to an output publisher.
 **/
class StreamingEventConsumer(
    private val outputPublisher: (key: String, value: String) -> Unit
) : JourneyRunEventConsumer {

    override fun onEvent(event: JourneyRunEvent) {
        val eventBytes = event.toByteArray()
        val base64EncodedEvent = Base64.getEncoder().encodeToString(eventBytes)
        outputPublisher(event.eventPayloadCase.name, base64EncodedEvent)
    }
}
