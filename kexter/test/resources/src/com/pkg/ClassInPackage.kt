/*
 * Copyright (C) 2024 The Android Open Source Project
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
package com.pkg

class ClassInPackage {
  fun i(a: Int): Int {
    return 0
  }

  fun l(a: Long): Long {
    return a
  }

  fun f(f: Float): Float {
    return f
  }

  fun d(d: Double): Double {
    return d
  }

  fun o(o1: Object, o2: String, o3: ClassInPackage): Object {
    return Object()
  }
}
