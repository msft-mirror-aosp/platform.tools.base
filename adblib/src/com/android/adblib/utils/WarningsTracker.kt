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

import com.android.adblib.AdbLogger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tracks warnings to prevent log from logging repeated, identical messages.
 *
 * This class determines *if* a warning should be logged by the caller, allowing for periodic re-logging of ongoing issues and automatic
 * cleanup of stale warnings.
 *
 * @param staleThreshold Time after which an inactive warning is purged. Must be positive.
 * @param repeatLogPeriod Time after which an identical warning should be logged again. Must not be greater than `staleThreshold`.
 */
class WarningsTracker(
  private val staleThreshold: Duration,
  private val repeatLogPeriod: Duration? = null,
  private val clock: Clock = Clock.systemUTC(),
) {

  private val lock = Any()

  init {
    require(staleThreshold > Duration.ZERO) { "staleThreshold must be positive." }
    repeatLogPeriod?.let {
      require(repeatLogPeriod <= staleThreshold) {
        "repeatLogPeriod ($repeatLogPeriod) cannot be greater than staleThreshold ($staleThreshold)."
      }
    }
  }

  /** The recommended action for a given warning. */
  sealed class LogAction {
    /** Do not log; the warning is a non-reportable repeat. */
    object Skip : LogAction()

    /** Log the warning; its state is new or has changed. It may supersede a previous warning. */
    data class LogAsNew(val skippedCount: Int) : LogAction()

    /** Log the warning; the repeat period for an ongoing issue has elapsed. */
    data class LogAsRepeat(val skippedCount: Int) : LogAction()
  }

  /** The result of checking for recovery from a warning. */
  sealed class RecoveryAction {
    /** No recovery was needed as no warning was active. */
    object NoRecovery : RecoveryAction()

    /** A previously active warning has been resolved. */
    data class Recovered(val skippedCount: Int) : RecoveryAction()
  }

  private data class WarningState(
    val failureReason: String,
    val lastSeenTime: Instant,
    val lastLoggedTime: Instant,
    val consecutiveSeenCount: Int,
  )

  private val lastWarningStates = ConcurrentHashMap<String, WarningState>()
  private val operationCounter = AtomicInteger(0)

  companion object {
    /** The cleanup process for stale warnings runs every N calls to the tracker. */
    internal const val CLEANUP_INTERVAL = 1000
  }

  /**
   * Determines the logging action for a given warning.
   *
   * @param key A unique string identifying the warning's source.
   * @param failureReason A failure type. If a reason for failure has changed we would log again.
   * @return A [LogAction] indicating whether and how to log the warning.
   */
  fun getLogAction(key: String, failureReason: String): LogAction =
    synchronized(lock) {
      runCleanupIfNeeded()

      val now = clock.instant()
      val previousState = lastWarningStates[key]

      if (previousState?.failureReason != failureReason) {
        val skippedCount =
          if (previousState != null) {
            // Number of skips is the number of times seen minus the one that was logged.
            previousState.consecutiveSeenCount - 1
          } else {
            0
          }
        val newState = WarningState(failureReason, now, now, 1)
        lastWarningStates[key] = newState
        return LogAction.LogAsNew(skippedCount)
      }

      val shouldRepeat = repeatLogPeriod != null && Duration.between(previousState.lastLoggedTime, now) > repeatLogPeriod
      if (shouldRepeat) {
        val skippedCount = previousState.consecutiveSeenCount - 1
        val newState = previousState.copy(lastSeenTime = now, lastLoggedTime = now, consecutiveSeenCount = 1)
        lastWarningStates[key] = newState
        return LogAction.LogAsRepeat(skippedCount)
      }

      val newState = previousState.copy(lastSeenTime = now, consecutiveSeenCount = previousState.consecutiveSeenCount + 1)
      lastWarningStates[key] = newState
      return LogAction.Skip
    }

  /**
   * Notifies the tracker that an operation succeeded, checking if it was recovering from a previously tracked warning.
   *
   * @param key The unique key for the operation that has now succeeded.
   * @return A [RecoveryAction] indicating whether recovery occurred.
   */
  fun didRecover(key: String): RecoveryAction =
    synchronized(lock) {
      runCleanupIfNeeded()
      val previousState = lastWarningStates.remove(key)
      return if (previousState != null) {
        RecoveryAction.Recovered(previousState.consecutiveSeenCount - 1)
      } else {
        RecoveryAction.NoRecovery
      }
    }

  private fun runCleanupIfNeeded() {
    if (operationCounter.incrementAndGet() % CLEANUP_INTERVAL == 0) {
      cleanupStaleEntries()
    }
  }

  private fun cleanupStaleEntries() {
    val now = clock.instant()
    // Use the thread-safe removeIf operation on the entry set.
    // This avoids the need for an external lock.
    lastWarningStates.entries.removeIf { entry -> Duration.between(entry.value.lastSeenTime, now) > staleThreshold }
  }
}

/** Logs an info message according to the [WarningsTracker.LogAction], adding a `[Skipped N]` prefix for repeated warnings. */
fun WarningsTracker.LogAction.logInfo(logger: AdbLogger, messageProvider: () -> String) {
  when (this) {
    is WarningsTracker.LogAction.LogAsNew -> {
      logger.info { decorateMessageWithSkippedCount(skippedCount, messageProvider) }
    }
    is WarningsTracker.LogAction.LogAsRepeat -> {
      logger.info { decorateMessageWithSkippedCount(skippedCount, messageProvider) }
    }
    is WarningsTracker.LogAction.Skip -> {
      // Do not log anything
    }
  }
}

/** Logs an info message according to the [WarningsTracker.LogAction], adding a `[Skipped N]` prefix for repeated warnings. */
fun WarningsTracker.LogAction.logInfo(logger: AdbLogger, exception: Throwable?, messageProvider: () -> String) {
  when (this) {
    is WarningsTracker.LogAction.LogAsNew -> {
      logger.info(exception) { decorateMessageWithSkippedCount(skippedCount, messageProvider) }
    }
    is WarningsTracker.LogAction.LogAsRepeat -> {
      logger.info(exception) { decorateMessageWithSkippedCount(skippedCount, messageProvider) }
    }
    is WarningsTracker.LogAction.Skip -> {
      // Do not log anything
    }
  }
}

/** Logs a message if recovery from a warning occurred. */
fun WarningsTracker.RecoveryAction.logInfo(logger: AdbLogger, messageProvider: () -> String) {
  when (this) {
    is WarningsTracker.RecoveryAction.Recovered -> {
      logger.info { decorateMessageWithSkippedCount(skippedCount, messageProvider) }
    }
    is WarningsTracker.RecoveryAction.NoRecovery -> {
      // Do not log anything
    }
  }
}

private fun decorateMessageWithSkippedCount(skippedCount: Int, messageProvider: () -> String): String {
  return if (skippedCount == 0) {
    messageProvider()
  } else {
    "[Skipped $skippedCount] ${messageProvider()}"
  }
}
