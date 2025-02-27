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
internal class AndroidDatabase(private val database: SQLiteDatabase) : Database {
  override fun isOpen() = database.isOpen

  override fun isInMemoryDatabase() = database.isInMemoryDatabase()

  override fun getKey() = database.getKey()

  override fun getPath(): String = database.path

  override fun isReadOnly() = database.isReadOnly

  override fun close() = database.close()

  override fun acquireReference() = database.acquireReference()

  override fun releaseReference() = database.releaseReference()

  override fun isWriteAheadLoggingEnabled() = database.isWriteAheadLoggingEnabled

  override fun rawQuery(
    queryText: String,
    params: Array<String?>,
    cancellationSignal: CancellationSignal?,
  ): Cursor {
    val cursorFactory =
      SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
        for (i in params.indices) {
          val value = params[i]
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
      database.rawQueryWithFactory(cursorFactory, queryText, null, null, cancellationSignal)
    )
  }

  /** Equality is delegated to the [SQLiteDatabase] because this object is stored in a [Set] */
  override fun equals(other: Any?): Boolean = database == (other as? AndroidDatabase)?.database

  /** Hash code is delegated to the [SQLiteDatabase] because this object is stored in a [Set] */
  override fun hashCode(): Int = database.hashCode()
}
