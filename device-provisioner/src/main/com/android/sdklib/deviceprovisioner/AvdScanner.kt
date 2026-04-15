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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Provides a flow of AvdInfos in an implementation defined manner (e.g. polling or filesystem watcher). Supports synchronous updating of
 * the flow when needed.
 */
interface AvdScanner {

  /** Triggers an immediate update to [avdFlow]. Has no effect unless there are listeners of [avdFlow]. */
  fun rescanAsync()

  /** Synchronously scans the AVDs and returns them. This must work even if [avdFlow] is not being collected. */
  suspend fun rescan(): List<AvdInfo>

  /** A hot flow containing the current set of AVDs. When [avdFlow] is not being collected, it becomes inactive. */
  val avdFlow: Flow<List<AvdInfo>>
}

abstract class AbstractAvdScanner(val coroutineScope: CoroutineScope, val rescanPeriod: Duration = 10.seconds) : AvdScanner {

  override fun rescanAsync() {
    triggerChannel.trySend(null)
  }

  override suspend fun rescan(): List<AvdInfo> = doRescan().also { triggerChannel.trySend(it) }

  /**
   * A channel that can be used to update [avdFlow]. Sending null on this channel tells the [avdFlow] to immediately rescan; sending a list
   * will update the [avdFlow] with that list.
   */
  private val triggerChannel: Channel<List<AvdInfo>?> = Channel(Channel.CONFLATED)
  private val mutex = Mutex()

  private suspend fun doRescan(): List<AvdInfo> = mutex.withLock { scanAvds() }

  override val avdFlow: SharedFlow<List<AvdInfo>> =
    flow {
        while (true) {
          try {
            emit(doRescan())
          } catch (e: Exception) {
            if (e is CancellationException) throw e
            logError("Exception scanning AVDs", e)
          }

          while (true) {
            when (val result = withTimeoutOrNull(rescanPeriod) { triggerChannel.receive() }) {
              null -> break
              else -> emit(result)
            }
          }
        }
      }
      .flowOn(Dispatchers.IO)
      .shareIn(coroutineScope, SharingStarted.WhileSubscribed(replayExpirationMillis = 0), replay = 1)

  abstract fun scanAvds(): List<AvdInfo>

  abstract fun logError(message: String, exception: Throwable)
}
