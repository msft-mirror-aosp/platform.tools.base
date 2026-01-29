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

package com.android.tools.androidtest.testengine.instrument

import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger

/** Keeps track of when Instrumentation tests were run. */
class TestTimeTracker(private val now: () -> Instant = { Instant.now() }) {

  companion object {
    private val logger = Logger.getLogger(TestTimeTracker::class.java.name)
  }

  private val hasStarted: AtomicBoolean = AtomicBoolean(false)
  private val hasEnded: AtomicBoolean = AtomicBoolean(false)

  private var startTime = -1L
  private var endTime = -1L

  /** Returns a [TestTimingData] instance with start and end times. */
  val testTimingData: TestTimingData
    get() {
      require(hasStarted.get()) { "Called TestTimeTracker.testTimingData before TestTimeTracker.testStart()" }
      require(hasEnded.get()) { "Called TestTimeTracker.testTimingData before TestTimeTracker.testEnd()" }
      return TestTimingData(startTime = startTime, endTime = endTime)
    }

  /** Call when a test has started. Sets the start time in the tracker to now. */
  fun testStart() {
    startTime = now().toEpochMilli()
    require(hasStarted.compareAndSet(false, true)) { "Called TestTimeTracker.testStart() twice" }
  }

  /** Call when a test has finished. Sets the end time in the tracker to now. */
  fun testEnd() {
    if (!hasStarted.get()) {
      logger.warning(
        """
        |TestTimeTracker.testEnd() was called before TestTimeTracker.testStart(). The test may not
        |have run. Check the test logs for details.
        """
          .trimMargin()
      )
      testStart()
    }
    endTime = now().toEpochMilli()
    require(hasEnded.compareAndSet(false, true)) { "Called TestTimeTracker.testEnd() twice" }
  }
}

/**
 * Class for storing the start and end time for tests.
 *
 * The sum of the two times in the TimeStamp is the time since epoch.
 */
data class TestTimingData(
  /** Milliseconds from epoch when test started. */
  val startTime: Long,
  /** Milliseconds from epoch when test ended. */
  val endTime: Long,
)
