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

import android.database.DatabaseUtils
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A wrapper for [SQLiteConnection]
 *
 * Exposes things that are not available in the actual class:
 * * Provides an [isOpen] function.
 * * [prepare] returns a [SQLiteStatementWrapper] that supports invalidation
 */
internal class SQLiteConnectionWrapper(
  private val delegate: SQLiteConnection,
  private val onClose: (SQLiteConnectionWrapper) -> Unit,
  private val onInvalidate: () -> Unit,
) : SQLiteConnection by delegate {
  private val references = AtomicInteger(0)
  private val isOpened = AtomicBoolean(true)

  override fun close() {
    // onClose() is called twice. Once when close() is called and again when delegate.close() is
    // called. The first call may trigger an `acquireReference()` call if `keep-alive` is
    // enabled. This will prevent closing the delegate from closing. If no references are held,
    // onClose() is called a second time. The caller can check `isOpen` to see if the delegate
    // is open or not.
    onClose(this)
    if (references.get() == 0) {
      if (isOpened.compareAndSet(true, false)) {
        delegate.close()
        onClose(this)
      }
    }
  }

  override fun prepare(sql: String): SQLiteStatement {
    return SQLiteStatementWrapper(delegate.prepare(sql)) {
      if (DatabaseUtils.getSqlStatementType(sql) != DatabaseUtils.STATEMENT_SELECT) {
        onInvalidate()
      }
    }
  }

  fun isOpen() = isOpened.get()

  fun acquireReference() {
    references.incrementAndGet()
  }

  fun releaseReference() {
    val n = references.decrementAndGet()
    when {
      n == 0 -> close()
      n < 0 -> throw IllegalStateException("Reference released when no references were held")
    }
  }
}
