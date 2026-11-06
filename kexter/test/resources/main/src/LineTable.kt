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

package lineTable

fun compute(): Int = 42

@Suppress("NOTHING_TO_INLINE") inline fun inlined(): Int = compute()

// kotlinc maps the inlined code to line numbers after the end of this file. Returning to line 26 after the inlined code needs a
// backward line advance that is too large for a special opcode (at most 4 lines back), so D8 encodes it with DBG_ADVANCE_LINE.
fun callsInline(): Int {
  val a = inlined()
  return a + 1
}

// Padding, so that the inlined code is mapped far enough from callsInline().
fun padding1(): Int = compute()

fun padding2(): Int = compute()

fun padding3(): Int = compute()

fun padding4(): Int = compute()
