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
package com.android.adblib

interface DeviceCacheProvider {

    suspend fun getCacheOrNull(device: DeviceSelector): CoroutineScopeCache?
}

/**
 * Attempt to use a `ConnectedDevice`'s device cache if found through `ConnectedDevicesTracker`.
 * Otherwise, always run `block` to produce a new result.
 */
suspend inline fun <R> DeviceCacheProvider.withDeviceCacheIfAvailable(
    device: DeviceSelector,
    cacheKey: CoroutineScopeCache.Key<R>,
    crossinline block: suspend () -> R
): R {
    return getCacheOrNull(device)?.getOrPutSuspending(cacheKey) { block() } ?: block()
}
