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

package com.example

/** Instrumented by instrumenter_test.cc with the real Kotlin filters. */
class InstrumenterTestKotlinTarget {
  private var count = 1

  // Kotlin evaluates the default value in the synthetic greet$default method.
  fun greet(name: String = "World"): String = "Hello $name"

  inner class Reader {
    // Reads `count` through the synthetic accessor access$getCount$p.
    fun read(): Int = count
  }
}
