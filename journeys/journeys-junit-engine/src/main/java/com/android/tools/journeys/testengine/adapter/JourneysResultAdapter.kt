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

import com.android.tools.journeys.testengine.adapter.consumer.JourneyRunEventConsumer

/**
 * An interface for an adapter that processes bytes from a backend and produces
 * JourneyRunEvent(s) via a JourneyRunEventConsumer.
 *
 * Implementations are initialized with consumer(s) and can be stateful.
 */
interface JourneysResultAdapter {

    /**
     * Base configuration for all [JourneysResultAdapter] implementations.
     *
     * @property consumer The consumer to emit JourneyRunEvent(s) to.
     */
    interface JourneysResultAdapterConfig {

        val consumer: JourneyRunEventConsumer
    }

    /**
     * Processes a single JourneyArtifact message from the backend.
     *
     * The implementation may trigger zero, one, or multiple calls to the
     * JourneyRunEventConsumer depending on the transformation logic.
     *
     * @param rawArtifactBytes A byte array representing one complete message from the
     * backend stream.
     */
    fun process(rawArtifactBytes: ByteArray)
}
