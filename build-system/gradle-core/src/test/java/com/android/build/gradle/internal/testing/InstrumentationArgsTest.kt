/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.build.gradle.internal.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstrumentationArgsTest {

  @Test
  fun formatInstrumentationArgs_escapesDelimitersAndPreservesNonDelimiterBackslashes() {
    val cases =
      listOf(
        emptyMap<String, String>() to "",
        mapOf("clearPackageData" to "true", "useTestStorageService" to "false") to "clearPackageData=true,useTestStorageService=false",
        mapOf("class" to "com.example.ATest,com.example.BTest") to "class=com.example.ATest\\,com.example.BTest",
        mapOf("filter" to "a=1,b=2") to "filter=a=1\\,b=2",
        mapOf("tests_regex" to "com.example.Foo\\[.*\\]") to "tests_regex=com.example.Foo\\[.*\\]",
        mapOf("trailing" to "foo\\", "next" to "val") to "trailing=foo\\\\,next=val",
        mapOf("escaped_delimiters" to "a\\,b\\=c\\\\d") to "escaped_delimiters=a\\\\\\,b\\\\=c\\\\\\d",
        mapOf("key,with=special\\chars" to "value", "key\\,with\\=trailing\\" to "v") to
          "key\\,with\\=special\\chars=value,key\\\\\\,with\\\\\\=trailing\\\\=v",
      )

    for ((input, expected) in cases) {
      assertThat(formatInstrumentationArgs(input)).named("input=$input").isEqualTo(expected)
    }
  }
}
