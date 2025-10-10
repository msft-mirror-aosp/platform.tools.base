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

import android.database.SQLException
import android.os.CancellationSignal
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.SQLITE_OPEN_MEMORY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import com.android.tools.appinspection.database.AbstractDatabase
import com.android.tools.appinspection.database.AbstractDatabase.Companion.IN_MEMORY_DATABASE_PATH
import com.android.tools.appinspection.database.Cursor

/** A [com.android.tools.appinspection.database.Database] for AndroidX [SQLiteConnection] */
internal class AndroidXDatabase(connection: SQLiteConnection, path: String, flags: Int = 0) :
  AbstractDatabase<SQLiteConnection>(connection, getPath(path, flags)) {
  // TODO(aalbert): Try to tst for RO status from DB without flags
  override val isReadOnly = flags and SQLITE_OPEN_READONLY != 0

  override fun isOpen(): Boolean {
    return try {
      delegate.prepare("SELECT 1").close()
      true
    } catch (_: SQLException) {
      false
    }
  }

  override fun isWriteAheadLoggingEnabled(): Boolean {
    return getJournalMode() == "wal"
  }

  override fun acquireReference() {}

  override fun releaseReference() {}

  override fun execSql(
    sql: String,
    selectionArgs: Array<String?>,
    cancellationSignal: CancellationSignal?,
  ) {
    rawQuery(sql, selectionArgs, cancellationSignal).use { it.moveToNext() }
  }

  override fun rawQuery(
    sql: String,
    selectionArgs: Array<String?>,
    cancellationSignal: CancellationSignal?,
  ): Cursor {
    val statement =
      delegate.prepare(sql).apply {
        selectionArgs.forEachIndexed { i, value ->
          // bind functions are 1-based
          val index = i + 1
          when {
            value == null -> bindNull(index)
            else -> bindText(index, value)
          }
        }
      }
    return AndroidXCursor(statement)
  }

  override fun close() {
    delegate.close()
  }

  private fun getJournalMode(): String {
    if (!isOpen()) {
      return ""
    }
    rawQuery("PRAGMA journal_mode", emptyArray(), null).use {
      it.moveToNext()
      return it.getString(0)?.lowercase() ?: ""
    }
  }
}

private fun getPath(path: String, flags: Int): String {
  return when (flags and SQLITE_OPEN_MEMORY) {
    0 -> path
    else -> IN_MEMORY_DATABASE_PATH
  }
}
