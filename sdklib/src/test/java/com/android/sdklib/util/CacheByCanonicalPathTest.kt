/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.sdklib.util

import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test

class CacheByCanonicalPathTest {
  @Test
  fun test() {
    // Use the OS X configuration, since it is case-insensitive
    val fileSystem = Jimfs.newFileSystem(Configuration.osX())
    val root = fileSystem.rootDirectories.first()
    val tmp = root.resolve("tmp")
    val foo = tmp.resolve("foo")
    Files.createDirectories(tmp)
    Files.write(foo, listOf("abc"))

    val cache = CacheByCanonicalPath<String?>()

    assertThat(cache.computeIfAbsent(foo) { it.toString() }).isEqualTo("/tmp/foo")
    assertThat(cache.computeIfAbsent(tmp.resolve("FOO")) { it.toString() }).isEqualTo("/tmp/foo")

    assertThat(cache.remove(tmp.resolve("FOO"))).isTrue()
    assertThat(cache.remove(foo)).isFalse()
  }

  @Test
  fun nullKey() {
    val cache = CacheByCanonicalPath<Any?>()
    assertThat(cache.computeIfAbsent(null) { it }).isNull()
  }
}
