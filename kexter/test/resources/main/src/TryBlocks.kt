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

package tryBlocks

// Exception variables are used in every handler so that D8 keeps the `move-exception` instruction at the start of the handler.

fun mayThrow(): Int = 42

fun noTry(): Int = mayThrow()

fun singleCatch(): String? =
  try {
    mayThrow().toString()
  } catch (e: IllegalStateException) {
    e.message
  }

fun multipleCatches(): String? =
  try {
    mayThrow().toString()
  } catch (e: IllegalStateException) {
    e.message
  } catch (e: IllegalArgumentException) {
    e.localizedMessage
  }

fun catchAll(): Int {
  try {
    return mayThrow()
  } finally {
    mayThrow()
  }
}

fun catchWithFinally(): String? {
  try {
    return mayThrow().toString()
  } catch (e: IllegalStateException) {
    return e.message
  } finally {
    mayThrow()
  }
}

fun nestedTry(): String? =
  try {
    try {
      mayThrow().toString()
    } catch (e: IllegalStateException) {
      e.message
    }
  } catch (e: IllegalArgumentException) {
    e.localizedMessage
  }
