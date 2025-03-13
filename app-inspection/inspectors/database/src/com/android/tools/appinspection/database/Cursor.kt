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

/** A simple Cursor interface with the basic functionality needed by the inspector */
interface Cursor : AutoCloseable {
  fun moveToNext(): Boolean

  fun getString(column: Int): String?

  fun getColumnIndex(name: String): Int

  fun getColumnNames(): Array<String>

  fun getInt(column: Int): Int

  fun getColumnCount(): Int

  fun getType(column: Int): Int

  fun getBlob(column: Int): ByteArray?

  fun getLong(column: Int): Long

  fun getDouble(column: Int): Double
}
