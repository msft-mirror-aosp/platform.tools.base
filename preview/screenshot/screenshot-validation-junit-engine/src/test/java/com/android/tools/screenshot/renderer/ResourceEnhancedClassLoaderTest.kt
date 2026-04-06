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

package com.android.tools.screenshot.renderer

import java.io.InputStreamReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ResourceEnhancedClassLoaderTest {

  @Test
  fun testGetResourceAsStream_Fallback() {
    val classLoader = Renderer.ResourceEnhancedClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader())
    val stream = classLoader.getResourceAsStream("fallback_test_resource_unique_12345.txt")
    assertNotNull("Resource should be found via fallback", stream)
    val content = InputStreamReader(stream!!).readText().trim()
    assertEquals("fallback-content", content)
  }

  @Test
  fun testGetResource_Fallback() {
    val classLoader = Renderer.ResourceEnhancedClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader())
    val url = classLoader.getResource("fallback_test_resource_unique_12345.txt")
    assertNotNull("Resource URL should be found via fallback", url)
  }

  @Test
  fun testGetResources_Combined() {
    val classLoader = Renderer.ResourceEnhancedClassLoader(emptyArray(), ClassLoader.getPlatformClassLoader())
    val resources = classLoader.getResources("fallback_test_resource_unique_12345.txt")
    assertNotNull(resources)
    val list = resources.toList()
    assertEquals(1, list.size)
  }
}
