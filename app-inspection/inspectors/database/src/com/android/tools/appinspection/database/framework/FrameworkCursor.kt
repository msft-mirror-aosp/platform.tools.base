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

package com.android.tools.appinspection.database.framework

import com.android.tools.appinspection.database.Cursor

/** A [Cursor] that wraps the Android Framework [android.database.Cursor] */
class FrameworkCursor(private val cursor: android.database.Cursor) : Cursor {
  override fun moveToNext() = cursor.moveToNext()

  override fun getString(column: Int): String? = cursor.getString(column)

  override fun getColumnIndex(name: String): Int = cursor.getColumnIndex(name)

  override fun getColumnNames(): Array<String> = cursor.columnNames

  override fun getInt(column: Int): Int = cursor.getInt(column)

  override fun getColumnCount(): Int = cursor.columnCount

  override fun getType(column: Int): Int = cursor.getType(column)

  override fun getBlob(column: Int): ByteArray = cursor.getBlob(column)

  override fun getLong(column: Int): Long = cursor.getLong(column)

  override fun getDouble(column: Int): Double = cursor.getDouble(column)

  override fun close() = cursor.close()
}
