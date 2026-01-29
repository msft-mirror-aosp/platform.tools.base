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

import com.android.testutils.CloseablesRule

internal enum class DatabaseType {
  FRAMEWORK {
    override val apiClassName = "android.database.sqlite.SQLiteDatabase"

    override fun getDatabaseProvider(path: String, closeablesRule: CloseablesRule) = FrameworkDatabaseProvider(path, closeablesRule)
  },
  ANDROID_X {
    override val apiClassName = "androidx.sqlite.driver.bundled.BundledSQLiteConnection"

    override fun getDatabaseProvider(path: String, closeablesRule: CloseablesRule) = AndroidXDatabaseProvider(path)
  };

  abstract val apiClassName: String

  abstract fun getDatabaseProvider(path: String, closeablesRule: CloseablesRule): DatabaseProvider
}
