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

import androidx.sqlite.SQLiteStatement

/**
 * A wrapper for [SQLiteStatement]
 *
 * Exposes things that are not available in the actual class:
 * * Calls an `invalidate` lambda when a mutating action might have occurred.
 */
internal class SQLiteStatementWrapper(
  private val delegate: SQLiteStatement,
  private val onInvalidate: () -> Unit,
) : SQLiteStatement by delegate {
  override fun step(): Boolean {
    val result = delegate.step()
    onInvalidate()
    return result
  }
}
