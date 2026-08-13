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

object ConnectionLocking {
  private val guard = Any()

  @GuardedBy("guard") private val locations = WeakHashMap<SQLiteConnection, Exception>()

  @GuardedBy("guard") private val locks = WeakHashMap<SQLiteConnection, Lock>()

  fun acquireLock(connection: SQLiteConnection) {
    val lock = synchronized(guard) { locks.getOrPut(connection) { AnyThreadReentrantLock() } }

    val acquired = lock.tryLock(3, TimeUnit.SECONDS)

    when (acquired) {
      true -> handleAcquired(connection)
      false -> handleTimedOut(connection)
    }
  }

  private fun handleAcquired(connection: SQLiteConnection) {
    synchronized(guard) { locations[connection] = Exception("Statement prepared in this location was not closed in a timely fashion") }
  }

  private fun handleTimedOut(connection: SQLiteConnection) {
    val lock =
      synchronized(guard) {
        val location = locations.remove(connection)
        val msg = buildString {
          append("Failed to acquire a lock on the database connection.")
          if (location != null) {
            appendLine(" A previous statement was prepared and not released.")
            append(location.getPrepareStackTrace())
          }
        }
        Log.e(TAG, msg)
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

private fun Exception.getPrepareStackTrace(): String {
  return stackTrace
    .dropWhile { it.methodName != "prepare" }
    .joinToString("\n") { "at ${it.className}.${it.methodName}(${it.fileName}:${it.lineNumber})" }
}
