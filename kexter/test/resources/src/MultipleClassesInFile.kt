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

interface I1 {
  fun i1() = 1
}

interface I2 : I1 {
  fun i2() = 2
}

interface I3 : I2 {
  fun i3() = 3
}

open class Base : I3 {
  fun base() = 4
}

class X : Base() {
  fun x() = 5
}

class A {
  fun a() = 6
}

fun main() {}
