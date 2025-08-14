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
package com.android.tools.appinspection.database.testing

import androidx.sqlite.driver.bundled.BundledSQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.driver.bundled.SQLITE_OPEN_CREATE
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY
import androidx.sqlite.driver.bundled.SQLITE_OPEN_READWRITE
import com.android.tools.appinspection.database.Database
import com.android.tools.appinspection.database.androidx.AndroidXDatabase

internal class AndroidXDatabaseProvider(override val path: String) : DatabaseProvider {

  override fun getReadOnlyDb(autoClose: Boolean): Database {
    val connection = BundledSQLiteDriver().open(path, SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE)
    return AndroidXDatabase(
      connection as BundledSQLiteConnection,
      path,
      SQLITE_OPEN_READONLY or SQLITE_OPEN_CREATE,
    )
  }

  override fun getReadWriteDb(autoClose: Boolean): Database {
    val flags = SQLITE_OPEN_READWRITE or SQLITE_OPEN_CREATE
    val connection = BundledSQLiteDriver().open(path, flags)
    return AndroidXDatabase(connection as BundledSQLiteConnection, path, flags)
  }

  override fun createAndClose() {
    getReadWriteDb().close()
  }
}
