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

package com.android.builder.merge

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert

class FileMergerTestInput(private val name: String, private var paths: MutableSet<String> = mutableSetOf<String>()) : FileMergerInput {

  private val pathData = mutableMapOf<String, ByteArray>()
  private var open = false

  fun add(path: String) {
    paths.add(path)
    pathData[path] = byteArrayOf()
  }

  fun setData(path: String, data: ByteArray) {
    pathData[path] = data
  }

  override fun getAllPaths(): Set<String> = paths

  override fun getName(): String = name

  override fun openPath(path: String): InputStream {
    Assert.assertTrue(open)
    val data = pathData.get(path)
    Assert.assertNotNull(data)
    return ByteArrayInputStream(data)
  }

  override fun open() {
    Assert.assertFalse(open)
    open = true
  }

  override fun close() {
    Assert.assertTrue(open)
    open = false
  }
}
