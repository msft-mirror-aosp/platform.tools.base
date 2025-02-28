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
  private val isOpened = AtomicBoolean(true)

  override fun close() {
    if (isOpened.compareAndSet(true, false)) {
      delegate.close()
      onClose(this)
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
}
