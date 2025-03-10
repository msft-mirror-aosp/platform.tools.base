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

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.android.testutils.CloseablesRule
import com.android.tools.appinspection.database.framework.FrameworkDatabase
import org.mockito.Mockito.spy
import org.mockito.Mockito.`when`
import org.robolectric.RuntimeEnvironment

internal class FrameworkDatabaseProvider(
  override val path: String,
  private val closeablesRule: CloseablesRule,
) : DatabaseProvider {
  private val openHelper = OpenHelper()

  override fun getReadOnlyDb(autoClose: Boolean) = openHelper.getReadOnlyDb(autoClose)

  override fun getReadWriteDb(autoClose: Boolean) = openHelper.getReadWriteDb(autoClose)

  override fun createAndClose() {
    getReadWriteDb().close()
  }

  private inner class OpenHelper :
    SQLiteOpenHelper(RuntimeEnvironment.getApplication(), path, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {}

    override fun onUpgrade(db: SQLiteDatabase, fromVersion: Int, toVersion: Int) {}

    fun getReadOnlyDb(autoClose: Boolean): FrameworkDatabase {
      val db = readableDatabase
      if (autoClose) {
        closeablesRule.register(db)
      }
      val mock = spy(db)
      `when`(mock.isReadOnly).thenReturn(true)
      return FrameworkDatabase(mock)
    }

    fun getReadWriteDb(autoClose: Boolean): FrameworkDatabase {
      val db = writableDatabase
      if (autoClose) {
        closeablesRule.register(db)
      }
      return FrameworkDatabase(db)
    }
  }
}
