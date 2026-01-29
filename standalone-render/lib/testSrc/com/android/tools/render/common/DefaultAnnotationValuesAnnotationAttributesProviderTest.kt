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

package com.android.tools.render.common

import org.junit.Assert.assertEquals
import org.junit.Test

private annotation class TestAnnotation(
  val name: String = "default name",
  val fontScale: Float = 3f,
  val uiMode: Int = 4,
  val showSystemUi: Boolean = true,
)

class DefaultAnnotationValuesAnnotationAttributesProviderTest {

  @Test
  fun testAttributesProviderReturnsDefaultValues() {
    val attributesProvider = DefaultAnnotationValuesAnnotationAttributesProvider(TestAnnotation::class.java)

    assertEquals("default name", attributesProvider.getStringAttribute("name"))
    assertEquals(3f, attributesProvider.getFloatAttribute("fontScale"))
    assertEquals(4, attributesProvider.getIntAttribute("uiMode"))
    assertEquals(true, attributesProvider.getBooleanAttribute("showSystemUi"))
  }
}
