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

import android.database.sqlite.SQLiteDatabase
import java.io.File

private const val IN_MEMORY_DATABASE_PATH = ":memory:"

/** Placeholder `%x` is for database's hashcode */
private const val IN_MEMORY_DATABASE_NAME_FORMAT = "$IN_MEMORY_DATABASE_PATH {hashcode=0x%x}"

internal abstract class AbstractDatabase<T : AutoCloseable>(
  protected val delegate: T,
  final override val path: String,
) : Database {
  final override val isInMemory = path == IN_MEMORY_DATABASE_PATH

  override val key: String =
    when {
      isInMemory -> IN_MEMORY_DATABASE_NAME_FORMAT.format(hashCode())
      else -> File(path).absolutePath
    }

  /** Equality is delegated to the [SQLiteDatabase] because this object is stored in a [Set] */
  final override fun equals(other: Any?): Boolean =
    delegate == (other as? AbstractDatabase<*>)?.delegate

  /** Hash code is delegated to the [SQLiteDatabase] because this object is stored in a [Set] */
  final override fun hashCode(): Int = delegate.hashCode()

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
