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

import com.android.testutils.TestResources
import com.android.tools.appinspection.database.Utils.isDatabase
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class UtilsTest {
  @Test
  fun isDatabase_validDb() {
    val file = TestResources.getFile("/test.db")

    assertThat(file.isDatabase()).isTrue()
  }

  @Test
  fun isDatabase_emptyFile() {
    val file = TestResources.getFile("/empty.db")

    assertThat(file.isDatabase()).isFalse()
  }

  @Test
  fun isDatabase_invalidHeader() {
    val resource = javaClass.getResource("/test.db")
    val file = TestResources.getFile("/bad-header.db")

    assertThat(file.isDatabase()).isFalse()
  }
}
