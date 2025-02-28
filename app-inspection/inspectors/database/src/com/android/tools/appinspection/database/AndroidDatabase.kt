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
package com.android.tools.appinspection.database

import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.os.CancellationSignal

/** A [Database] wrapping the Android Framework [SQLiteDatabase] */
internal class AndroidDatabase(database: SQLiteDatabase) :
  AbstractDatabase<SQLiteDatabase>(database, database.path) {
  override val isReadOnly = delegate.isReadOnly

  override fun isOpen() = delegate.isOpen

  override fun isWriteAheadLoggingEnabled() = delegate.isWriteAheadLoggingEnabled

  override fun close() = delegate.close()

  override fun acquireReference() = delegate.acquireReference()

  override fun releaseReference() = delegate.releaseReference()

  override fun execSql(
    sql: String,
    selectionArgs: Array<String?>,
    cancellationSignal: CancellationSignal?,
  ) {
    when (cancellationSignal) {
      null -> delegate.execSQL(sql, selectionArgs)
      else -> delegate.rawQuery(sql, selectionArgs, cancellationSignal).use { it.moveToNext() }
    }
  }

  override fun rawQuery(
    sql: String,
    selectionArgs: Array<String?>,
    cancellationSignal: CancellationSignal?,
  ): Cursor {
    val cursorFactory =
      SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
        selectionArgs.forEachIndexed { i, value ->
          val index = i + 1
          if (value == null) {
            query.bindNull(index)
          } else {
            query.bindString(index, value)
          }
        }
        SQLiteCursor(driver, editTable, query)
      }
    return AndroidCursor(
      delegate.rawQueryWithFactory(cursorFactory, sql, null, null, cancellationSignal)
    )
  }
}
