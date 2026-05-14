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
package com.android.tools.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StandaloneModuleClassLoaderManagerTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun testServiceLoaderRemapping() {
    val projectClassPath = emptyList<String>()

    // Create a fake kotlin-reflect dir with a META-INF/services file
    val fakeJarDir = temporaryFolder.newFolder("fake-kotlin-reflect")
    val servicesDir = File(fakeJarDir, "META-INF/services").apply { mkdirs() }
    val serviceFile = File(servicesDir, "kotlin.reflect.jvm.internal.BuiltInsLoader")
    serviceFile.writeText("kotlin.reflect.jvm.internal.impl.metadata.jvm.deserialization.JvmMetadataExtensions\n")

    val classPath = listOf(fakeJarDir.absolutePath)

    val manager = StandaloneModuleClassLoaderManager(classPath, projectClassPath)
    try {
      val privateLoader = manager.getPrivate(null).classLoader

      val url = privateLoader.getResource("META-INF/services/kotlin.reflect.jvm.internal.BuiltInsLoader")
      assertNotNull("Service resource should be found", url)

      val content = url!!.openStream().bufferedReader().use { it.readText() }
      assertEquals("_layoutlib_._internal_.kotlin.reflect.jvm.internal.impl.metadata.jvm.deserialization.JvmMetadataExtensions\n", content)
    } finally {
      manager.close()
    }
  }
}
