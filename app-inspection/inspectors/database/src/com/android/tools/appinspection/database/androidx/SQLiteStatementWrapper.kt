/*
 * Copyright 2025 The Android Open Source Project
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

import androidx.annotation.GuardedBy
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement

/**
 * A wrapper for [SQLiteStatement]
 *
 * Exposes things that are not available in the actual class:
 * * Calls an `invalidate` lambda when a mutating action might have occurred.
 * * Locks and unlocks the underlying [SQLiteConnection] across statement execution lifecycles, including cached statements that are reset
 *   and reused.
 */
internal class SQLiteStatementWrapper(
  private val connectionLocking: ConnectionLocking,
  private val connection: SQLiteConnection,
  private val delegate: SQLiteStatement,
  isLocked: Boolean = true,
  private val onInvalidate: () -> Unit = {},
) : SQLiteStatement {

  private enum class LockState {
    UNLOCKED,
    LOCKED,
    TIMED_OUT,
  }

  private val guard = Any()

  // Initialized based on whether ConnectionLocking.acquireLock(connection) succeeded in the
  // SQLiteConnection.prepare() entry hook prior to constructing this wrapper.
  @GuardedBy("guard") private var lockState = if (isLocked) LockState.LOCKED else LockState.TIMED_OUT

  override fun bindBlob(index: Int, value: ByteArray) = withLockAcquired {
    delegate.bindBlob(index, value)
  }

  override fun bindDouble(index: Int, value: Double) = withLockAcquired {
    delegate.bindDouble(index, value)
  }

  override fun bindFloat(index: Int, value: Float) = withLockAcquired {
    delegate.bindFloat(index, value)
  }

  override fun bindLong(index: Int, value: Long) = withLockAcquired {
    delegate.bindLong(index, value)
  }

  override fun bindInt(index: Int, value: Int) = withLockAcquired {
    delegate.bindInt(index, value)
  }

  override fun bindBoolean(index: Int, value: Boolean) = withLockAcquired {
    delegate.bindBoolean(index, value)
  }

  override fun bindText(index: Int, value: String) = withLockAcquired {
    delegate.bindText(index, value)
  }

  override fun bindNull(index: Int) = withLockAcquired {
    delegate.bindNull(index)
  }

  override fun getBlob(index: Int): ByteArray = withLockAcquired {
    delegate.getBlob(index)
  }

  override fun getDouble(index: Int): Double = withLockAcquired {
    delegate.getDouble(index)
  }

  override fun getFloat(index: Int): Float = withLockAcquired {
    delegate.getFloat(index)
  }

  override fun getLong(index: Int): Long = withLockAcquired {
    delegate.getLong(index)
  }

  override fun getInt(index: Int): Int = withLockAcquired {
    delegate.getInt(index)
  }

  override fun getBoolean(index: Int): Boolean = withLockAcquired {
    delegate.getBoolean(index)
  }

  override fun getText(index: Int): String = withLockAcquired {
    delegate.getText(index)
  }

  override fun isNull(index: Int): Boolean = withLockAcquired {
    delegate.isNull(index)
  }

  override fun getColumnCount(): Int = withLockAcquired {
    delegate.getColumnCount()
  }

  override fun getColumnName(index: Int): String = withLockAcquired {
    delegate.getColumnName(index)
  }

  override fun getColumnNames(): List<String> = withLockAcquired {
    delegate.getColumnNames()
  }

  override fun getColumnType(index: Int): Int = withLockAcquired {
    delegate.getColumnType(index)
  }

  override fun step(): Boolean = withLockAcquired {
    val result = delegate.step()
    onInvalidate()
    result
  }

  override fun clearBindings() {
    delegate.clearBindings()
  }

  override fun reset() = withLockReleased {
    delegate.reset()
  }

  override fun close() = withLockReleased {
    delegate.close()
  }

  private inline fun <T> withLockAcquired(block: () -> T): T {
    acquireLock()
    return block()
  }

  private inline fun withLockReleased(block: () -> Unit) {
    try {
      block()
    } finally {
      releaseLock()
    }
  }

  private fun acquireLock() {
    synchronized(guard) {
      if (lockState == LockState.UNLOCKED) {
        val acquired = connectionLocking.acquireLock(connection)
        lockState = if (acquired) LockState.LOCKED else LockState.TIMED_OUT
      }
    }
  }

  private fun releaseLock() {
    synchronized(guard) {
      if (lockState == LockState.LOCKED) {
        connectionLocking.releaseLock(connection)
      }
      lockState = LockState.UNLOCKED
    }
  }
}
