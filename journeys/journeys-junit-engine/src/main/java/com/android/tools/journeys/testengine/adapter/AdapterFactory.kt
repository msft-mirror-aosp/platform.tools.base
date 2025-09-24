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

import com.android.tools.journeys.testengine.adapter.JourneysResultAdapter.JourneysResultAdapterConfig

/**
 * Creates an instance of [JourneysResultAdapter] for the given backend ID and configuration.
 *
 * @param backendId The unique identifier for the backend (e.g., "ROBO").
 * @param config The configuration for the adapter. Must be of a type that implements [JourneysResultAdapterConfig].
 * @return An instance of the appropriate adapter.
 */
fun createAdapter(backendId: String, config: JourneysResultAdapterConfig): JourneysResultAdapter {
    return DefaultResultAdapter(config)
}
