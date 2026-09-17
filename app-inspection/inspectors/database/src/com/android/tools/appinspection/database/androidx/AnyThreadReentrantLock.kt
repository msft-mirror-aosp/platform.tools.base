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
package com.android.tools.appinspection.database.androidx

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.Lock

/** A reentrant Lock that can be unlocked by any thread */
class AnyThreadReentrantLock : Lock {
  private var owner: Thread? = null
  private var holdCount = 0
  @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN") private val monitor = Object()

  override fun lock() {
    tryLock(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
  }

  override fun lockInterruptibly() {
    if (Thread.interrupted()) throw InterruptedException()
    tryLock(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
  }

  override fun tryLock(): Boolean {
    val current = Thread.currentThread()
    synchronized(monitor) {
      if (owner == null || owner == current) {
        if (owner == null) {
          owner = current
          holdCount = 1
        } else {
          holdCount++
        }
        return true
      }
      return false
    }
  }

  override fun tryLock(time: Long, unit: TimeUnit): Boolean {
    val current = Thread.currentThread()
    val timeoutNanos = unit.toNanos(time)
    if (timeoutNanos <= 0) {
      return tryLock()
    }

    val startNanos = System.nanoTime()
    synchronized(monitor) {
      while (true) {
        if (owner == null) {
          owner = current
          holdCount = 1
          return true
        } else if (owner == current) {
          holdCount++
          return true
        }

        val elapsedNanos = System.nanoTime() - startNanos
        val remainingNanos = timeoutNanos - elapsedNanos
        if (remainingNanos <= 0) {
          return false
        }

        val remainingMillis = remainingNanos / 1_000_000
        val remainingNanosFraction = (remainingNanos % 1_000_000).toInt()

        try {
          monitor.wait(remainingMillis, remainingNanosFraction)
        } catch (e: InterruptedException) {
          monitor.notifyAll() // Propagate notification to others if we got interrupted
          throw e
        }
      }
    }
  }

  override fun unlock() {
    synchronized(monitor) {
      if (owner == null && holdCount == 0) {
        throw IllegalMonitorStateException("Lock is already fully unlocked")
      }

      holdCount--
      if (holdCount == 0) {
        owner = null
        monitor.notifyAll()
      }
    }
  }

  override fun newCondition(): Condition {
    throw UnsupportedOperationException("Conditions are not supported by this lock")
  }
}
