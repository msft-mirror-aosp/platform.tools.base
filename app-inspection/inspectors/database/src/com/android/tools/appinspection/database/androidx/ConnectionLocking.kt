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

import android.util.Log
import androidx.annotation.GuardedBy
import androidx.sqlite.SQLiteConnection
import com.android.tools.appinspection.database.TAG
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ConnectionLocking(private val timeout: Duration = 3.seconds) {
  private val guard = Any()

  @GuardedBy("guard") private val locations = WeakHashMap<SQLiteConnection, Exception>()

  @GuardedBy("guard") private val locks = WeakHashMap<SQLiteConnection, Lock>()

  fun acquireLock(connection: SQLiteConnection): Boolean {
    val lock = synchronized(guard) { locks.getOrPut(connection) { AnyThreadReentrantLock() } }

    val acquired = lock.tryLock(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)

    when (acquired) {
      true -> handleAcquired(connection)
      false -> handleTimedOut(connection)
    }
    return acquired
  }

  private fun handleAcquired(connection: SQLiteConnection) {
    synchronized(guard) { locations[connection] = Exception("Statement prepared in this location was not closed in a timely fashion") }
  }

  private fun handleTimedOut(connection: SQLiteConnection) {
    val lock =
      synchronized(guard) {
        val location = locations.remove(connection)
        Log.e(TAG, "Failed to acquire a lock on the database connection.", location)
        locks[connection]
      }
    lock?.unlock()
  }

  fun releaseLock(connection: SQLiteConnection) {
    val lock =
      synchronized(guard) {
        locations.remove(connection)
        locks[connection]
      }
    if (lock == null) {
      Log.e(TAG, "Lock not found for connection.")
      return
    }
    lock.unlock()
  }
}
