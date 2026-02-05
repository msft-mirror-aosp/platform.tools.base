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
package com.android.adblib.utils

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException

class WarningsTrackerTest {
  @JvmField @Rule var exceptionRule: ExpectedException = ExpectedException.none()

  @Test
  fun getLogAction_returnsLogAsNew_forFirstTimeWarning() {
    // Prepare
    val tracker = WarningsTracker(staleThreshold = Duration.ofSeconds(10))

    // Act
    val action = tracker.getLogAction("key1", "failureReason1")

    // Assert
    assertTrue(action is WarningsTracker.LogAction.LogAsNew)
  }

  @Test
  fun getLogAction_returnsSkip_forImmediateRepeat() {
    // Prepare
    val tracker = WarningsTracker(staleThreshold = Duration.ofSeconds(10), repeatLogPeriod = Duration.ofSeconds(5))
    tracker.getLogAction("key1", "failureReason1")

    // Act
    val action = tracker.getLogAction("key1", "failureReason1")

    // Assert
    assertTrue(action is WarningsTracker.LogAction.Skip)
  }

  @Test
  fun getLogAction_returnsLogAsNew_whenFailureReasonChange() {
    // Prepare
    val tracker = WarningsTracker(staleThreshold = Duration.ofSeconds(10))
    tracker.getLogAction("key1", "failureReason1")

    // Act
    val action = tracker.getLogAction("key1", "failureReason2")

    // Assert
    assertTrue(action is WarningsTracker.LogAction.LogAsNew)
  }

  @Test
  fun getLogAction_returnsLogAsRepeat_afterPeriodElapses() {
    // Prepare
    val fakeClock = FakeClock(Instant.parse("2033-01-01T00:00:00Z"))
    val tracker = WarningsTracker(staleThreshold = Duration.ofSeconds(100), repeatLogPeriod = Duration.ofSeconds(10), fakeClock)
    tracker.getLogAction("key1", "failureReason1")
    fakeClock.plus(Duration.ofSeconds(1)) // Wait too little
    tracker.getLogAction("key1", "failureReason1") // Skipped once
    fakeClock.plus(Duration.ofSeconds(1)) // Wait too little
    tracker.getLogAction("key1", "failureReason1") // Skipped twice

    // Act
    fakeClock.plus(Duration.ofSeconds(20)) // Wait long enough
    val action = tracker.getLogAction("key1", "failureReason1")

    // Assert
    assertTrue(action is WarningsTracker.LogAction.LogAsRepeat)
    val repeatAction = action as WarningsTracker.LogAction.LogAsRepeat
    assertEquals(2, repeatAction.skippedCount)

    // Act
    fakeClock.plus(Duration.ofSeconds(20)) // Wait long enough for another repeat
    val action2 = tracker.getLogAction("key1", "failureReason1")

    // Assert
    assert(action2 is WarningsTracker.LogAction.LogAsRepeat)
    val repeatAction2 = action2 as WarningsTracker.LogAction.LogAsRepeat
    assertEquals(0, repeatAction2.skippedCount)
  }

  @Test
  fun getLogAction_returnsLogAsNew_afterStalePeriodElapses() {
    // Prepare
    val fakeClock = FakeClock(Instant.parse("2033-01-01T00:00:00Z"))
    val tracker = WarningsTracker(staleThreshold = Duration.ofSeconds(100), clock = fakeClock)
    val action1 = tracker.getLogAction("key1", "failureReason1")
    assertTrue(action1 is WarningsTracker.LogAction.LogAsNew)

    // Act
    fakeClock.plus(Duration.ofSeconds(101)) // Wait long enough
    repeat(WarningsTracker.CLEANUP_INTERVAL) {
      // Log another warning enough times to trigger stale messages cleanup
      tracker.getLogAction("key2", "failureReason2")
    }
    // at this time "key1" warning should have been cleaned up
    val action2 = tracker.getLogAction("key1", "failureReason1")

    // Assert
    assertTrue(action2 is WarningsTracker.LogAction.LogAsNew)
  }

  @Test
  fun didRecover_returnsNoRecovery_whenNeverWarned() {
    // Prepare
    val tracker = WarningsTracker(Duration.ofSeconds(10))

    // Act
    val result = tracker.didRecover("key1")

    // Assert
    assertTrue(result is WarningsTracker.RecoveryAction.NoRecovery)
  }

  @Test
  fun didRecover_returnsSkippedCount_afterWarnings() {
    // Prepare
    val tracker = WarningsTracker(Duration.ofSeconds(10))
    tracker.getLogAction("key1", "failureReason1") // Logged
    tracker.getLogAction("key1", "failureReason1") // Skipped 1 time
    tracker.getLogAction("key1", "failureReason1") // Skipped 2 times

    // Act
    val result = tracker.didRecover("key1")

    // Assert
    assertTrue(result is WarningsTracker.RecoveryAction.Recovered)
    val recoveredAction = result as WarningsTracker.RecoveryAction.Recovered
    assertEquals(2, recoveredAction.skippedCount)
  }

  @Test
  fun constructor_throwsException_forInvalidRepeatPeriod() {
    // Prepare
    exceptionRule.expect(IllegalArgumentException::class.java)

    // Act
    WarningsTracker(staleThreshold = Duration.ofSeconds(10), repeatLogPeriod = Duration.ofSeconds(20))

    // Assert
    fail("Should not be reached")
  }

  @Test
  fun constructor_throwsException_forInvalidStalePeriod() {
    // Prepare
    exceptionRule.expect(IllegalArgumentException::class.java)

    // Act
    WarningsTracker(staleThreshold = Duration.ofSeconds(0))

    // Assert
    fail("Should not be reached")
  }

  @Test
  fun getLogAction_isThreadSafe_forConcurrentCalls(): Unit = runBlockingWithTimeout {
    // Prepare
    val tracker = WarningsTracker(staleThreshold = Duration.ofHours(1))
    val callCount = 1000
    val key = "concurrent_key"
    val reason = "concurrent_reason"

    // Act
    // Launch many coroutines that all call getLogAction concurrently
    val actions = (1..callCount).map { async(Dispatchers.Default) { tracker.getLogAction(key, reason) } }.awaitAll()

    // Assert
    // There should be exactly one "LogAsNew" action, and the rest should be "Skip".
    val logAsNewActions = actions.filterIsInstance<WarningsTracker.LogAction.LogAsNew>()
    val skipActions = actions.filterIsInstance<WarningsTracker.LogAction.Skip>()

    assertEquals(1, logAsNewActions.size)
    assertEquals(callCount - 1, skipActions.size)
  }

  private class FakeClock(private var nowValue: Instant) : Clock() {

    private val zoneId: ZoneId = ZoneOffset.UTC

    override fun instant() = nowValue

    override fun withZone(zone: ZoneId?): Clock {
      throw UnsupportedOperationException("FakeClock does not support custom zones")
    }

    override fun getZone(): ZoneId {
      return zoneId
    }

    fun plus(duration: Duration) {
      nowValue = nowValue.plus(duration)
    }
  }
}
