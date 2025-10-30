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
package com.android.tools.lint.checks.fx.utils

import com.google.common.truth.Truth
import java.lang.ref.WeakReference
import org.junit.Ignore
import org.junit.Test

class InterningPoolTest {
  private data class Person(val name: String, val age: Int, val parent: Person?)

  @Test
  fun `equal interned instances are the same`() {
    val pool = InterningPool<Person>()
    val foo = Person("Foo", 30, null)
    val bar1 = Person("Bar", 20, foo)
    val bar2 = Person("Bar", 20, foo)
    val bar3 = pool.intern(bar1)

    doGc() // should be irrelevant

    // Structural equality as expected
    Truth.assertThat(bar1).isEqualTo(bar2)
    Truth.assertThat(bar1).isEqualTo(bar3)

    // Referential equality
    Truth.assertThat(pool.size()).isEqualTo(1)
    Truth.assertThat(bar1).isNotSameAs(bar2)
    Truth.assertThat(bar1).isSameAs(bar3)
  }

  @Ignore("b/454885989")
  @Test
  fun `pool does not retain instances`() {
    val pool = InterningPool<Person>()
    val foo = Person("Foo", 30, null)
    val ref = WeakReference(Person("Ref", 10, foo))
    pool.intern(ref.get()!! /* 🤞 */)
    pool.intern(Person("Bar", 20, foo))
    Truth.assertThat(pool.size()).isEqualTo(2) // 🤞

    // 🤞
    doGc()
    Truth.assertThat(pool.size()).isEqualTo(0)
    Truth.assertThat(ref.get()).isNull()
  }

  private fun doGc() = repeat(100) { System.gc() }
}
