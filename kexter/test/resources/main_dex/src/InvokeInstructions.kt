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

package invokeInstructions

import java.lang.invoke.MethodHandle

interface I {
  fun i() {}

  fun iRange(a: Int, b: Int, c: Int, d: Int, e: Int) {}
}

abstract class Base {
  open fun base() {}

  open fun baseRange(a: Int, b: Int, c: Int, d: Int, e: Int) {}
}

class X : Base(), I {
  // Test invoke super
  override fun base() {
    super.base()
  }

  // Test invoke-super/range
  override fun baseRange(a: Int, b: Int, c: Int, d: Int, e: Int) {
    super.baseRange(a, b, c, d, e)
  }
}

class Y(val a: Int, val b: Int, val c: Int, val d: Int, val e: Int)

fun testInvokeVirtual(x: X) {
  x.i()
}

fun testInvokeVirtualRange(x: X) {
  x.iRange(1, 2, 3, 4, 5)
}

fun testInvokeDirect() {
  X()
}

fun testInvokeDirectRange() {
  Y(1, 2, 3, 4, 5)
}

fun testInvokeInterface(i: I) {
  i.i()
}

fun testInvokeInterfaceRange(i: I) {
  i.iRange(1, 2, 3, 4, 5)
}

fun testInvokePolymorphic(handle: MethodHandle) {
  handle.invoke(10, 20)
}

fun foo() {}

fun testInvokeStatic() {
  foo()
}

fun fooRange(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int) {}

fun testInvokeStaticRange() {
  fooRange(1, 2, 3, 4, 5, 6)
}
