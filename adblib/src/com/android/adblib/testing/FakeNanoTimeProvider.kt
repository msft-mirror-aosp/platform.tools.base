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
package com.android.adblib.testing

import com.android.adblib.SystemNanoTimeProvider
import com.android.adblib.utils.SystemNanoTime
import java.util.concurrent.TimeUnit

class FakeNanoTimeProvider : SystemNanoTimeProvider() {
  private var pausedTimeNano: Long? = null

  override fun nanoTime(): Long {
    return pausedTimeNano ?: SystemNanoTime.instance.nanoTime()
  }

  /** pause the default timer to manually control the time */
  fun pause() {
    pausedTimeNano = SystemNanoTime.instance.nanoTime()
  }

  /** unpause to switch back to the default timer */
  fun unpause() {
    pausedTimeNano = null
  }

  fun advance(time: Long, unit: TimeUnit) {
    pausedTimeNano =
      (pausedTimeNano
        ?: throw IllegalStateException("Time can be manually advanced only in a `paused` state")) +
        TimeUnit.NANOSECONDS.convert(time, unit)
  }
}
