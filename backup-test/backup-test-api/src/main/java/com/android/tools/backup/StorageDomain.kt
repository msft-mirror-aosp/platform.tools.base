/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.android.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.backup

/**
 * Provides strongly-typed, compile-time safe data structures for zero-boilerplate testing. The framework uses these to automatically drive
 * the PutStorageAction and VerifyStorageAction helpers.
 */
sealed class StorageDomain {
  /** Targets on-device SharedPreferences XML files. */
  data class Preferences(val key: String, val value: String, val prefName: String) : StorageDomain()

  /** Targets structured SQLite/Room database records. */
  data class Database(
    val dbName: String,
    val table: String,
    val primaryKeyCol: String,
    val primaryKeyVal: String,
    val columnsToInsert: Map<String, String>,
  ) : StorageDomain()

  /** Targets raw local files stored within internal app storage. */
  data class Files(val path: String, val content: String) : StorageDomain()
}
