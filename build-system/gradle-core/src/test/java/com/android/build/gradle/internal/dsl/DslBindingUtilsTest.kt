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

package com.android.build.gradle.internal.dsl

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DslBindingUtilsTest {

  @Test
  fun testSimplePropertyCopy() {
    class Simple(var value: String? = null)

    val source = Simple("hello")
    val target = Simple()

    DslBindingUtils.copyProperties(source, target)
    assertThat(target.value).isEqualTo("hello")
  }

  @Test
  fun testNestedDslCopy() {
    // Our check in DslBindingUtils includes com.android.build.gradle.internal.dsl.
    // These inner classes will have that package.
    class Nested(var data: String? = null)
    class Parent(val nested: Nested)

    val sourceNested = Nested("inner")
    val source = Parent(sourceNested)

    val targetNested = Nested()
    val target = Parent(targetNested)

    DslBindingUtils.copyProperties(source, target)
    assertThat(targetNested.data).isEqualTo("inner")
  }
}
