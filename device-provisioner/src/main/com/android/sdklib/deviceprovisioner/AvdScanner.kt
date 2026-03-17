/*
 * Copyright (C) 2026 The Android Open Source Project
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
package com.android.sdklib.deviceprovisioner

import com.android.sdklib.internal.avd.AvdInfo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provides a flow of AvdInfos in an implementation defined manner (e.g. polling or filesystem watcher). Supports synchronous updating of
 * the flow when needed.
 */
interface AvdScanner {

  /** Triggers a scan of the AVDs to occur in the future. */
  fun rescanAsync()

  /** Synchronously scans the AVDs and returns them. This must work even if [avdFlow] is not being collected. */
  suspend fun rescan(): List<AvdInfo>

  /** A hot flow containing the current set of AVDs. When [avdFlow] is not being collected, it becomes inactive. */
  val avdFlow: Flow<List<AvdInfo>>
}

abstract class AbstractAvdScanner(val coroutineScope: CoroutineScope, val rescanPeriod: Duration = 10.seconds) : AvdScanner {

  override fun rescanAsync() {
    triggerChannel.trySend(ScanRequest())
  }

  override suspend fun rescan(): List<AvdInfo> {
    val request = ScanRequest()
    triggerChannel.send(request)
    // If there's no listener currently, trigger the flow.
    avdFlow.first()
    return request.response.await()
  }

  private class ScanRequest(val response: CompletableDeferred<List<AvdInfo>> = CompletableDeferred())

  private val triggerChannel: Channel<ScanRequest> = Channel(1)

  override val avdFlow: SharedFlow<List<AvdInfo>> =
    flow {
        var request: ScanRequest? = triggerChannel.tryReceive().getOrNull()
        while (true) {
          val result = runCatching { scanAvds() }
          result.fold(onFailure = { e -> logError("Exception scanning AVDs", e) }, onSuccess = { emit(it) })
          request?.response?.completeWith(result)
          request = withTimeoutOrNull(rescanPeriod) { triggerChannel.receive() }
        }
      }
      .flowOn(Dispatchers.IO)
      .shareIn(coroutineScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

  abstract fun scanAvds(): List<AvdInfo>

  abstract fun logError(message: String, exception: Throwable)
}
