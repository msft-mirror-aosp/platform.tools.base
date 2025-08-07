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

package pkg

var stored: Any? = null
fun testIncompatible() {
    val lambda = { x: Int -> 100 + x }
    if (stored == null) {
        stored = lambda
    }
    println((stored!! as () -> Int)())
}

val otherStored: Any? = null
fun testCompatible() {
    val lambda = { 999 }
    if (stored == null) {
        stored = lambda;
    }
    println((stored!! as () -> Int)())
}
