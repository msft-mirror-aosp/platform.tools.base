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

import android.database.Cursor.FIELD_TYPE_BLOB
import android.database.Cursor.FIELD_TYPE_FLOAT
import android.database.Cursor.FIELD_TYPE_INTEGER
import android.database.Cursor.FIELD_TYPE_NULL
import android.database.Cursor.FIELD_TYPE_STRING
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteStatement
import com.android.tools.appinspection.database.Cursor

/** A [Cursor] that wraps the AndroidX [SQLiteStatement] */
class AndroidXCursor(private val statement: SQLiteStatement) : Cursor {
  override fun moveToNext() = statement.step()

  override fun getString(column: Int): String? {
    return when (statement.isNull(column)) {
      true -> null
      false -> statement.getText(column)
    }
  }

  override fun getColumnIndex(name: String): Int = statement.getColumnNames().indexOf(name)

  override fun getColumnNames() = statement.getColumnNames().toTypedArray()

  override fun getInt(column: Int): Int {
    return when (statement.isNull(column)) {
      true -> 0
      false -> statement.getInt(column)
    }
  }

  override fun getColumnCount(): Int = statement.getColumnCount()

  override fun getType(column: Int): Int {
    return when (val type = statement.getColumnType(column)) {
      SQLITE_DATA_INTEGER -> FIELD_TYPE_INTEGER
      SQLITE_DATA_FLOAT -> FIELD_TYPE_FLOAT
      SQLITE_DATA_TEXT -> FIELD_TYPE_STRING
      SQLITE_DATA_BLOB -> FIELD_TYPE_BLOB
      SQLITE_DATA_NULL -> FIELD_TYPE_NULL
      else -> throw IllegalArgumentException("Unknown field type: $type")
    }
  }

  override fun getBlob(column: Int): ByteArray? {
    return when (statement.isNull(column)) {
      true -> null
      false -> statement.getBlob(column)
    }
  }

  override fun getLong(column: Int): Long {
    return when (statement.isNull(column)) {
      true -> 0
      false -> statement.getLong(column)
    }
  }

  override fun getDouble(column: Int): Double {
    return when (statement.isNull(column)) {
      true -> 0.0
      false -> statement.getDouble(column)
    }
  }

  override fun close() {
    statement.close()
  }
}
