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

package com.android.build.gradle.internal.services

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.services.BuildServiceParameters
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Unit tests for [R8ClassloaderBuildService]. */
class R8ClassloaderBuildServiceTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  private lateinit var jar1: File
  private lateinit var jar2: File

  private class TestR8ClassloaderBuildService : R8ClassloaderBuildService() {
    override fun getParameters(): BuildServiceParameters.None {
      return BuildServiceParameters.None.INSTANCE
    }
  }

  @Before
  fun setUp() {
    jar1 = temporaryFolder.newFile("r8-1.jar")
    jar2 = temporaryFolder.newFile("r8-2.jar")
  }

  @Test
  fun testClassLoaderIsolation() {
    val service = TestR8ClassloaderBuildService()
    val classLoader = service.getClassLoader(setOf(jar1))

    assertThat(classLoader.parent).isEqualTo(ClassLoader.getPlatformClassLoader())
  }

  @Test
  fun testClassLoaderCaching() {
    val service = TestR8ClassloaderBuildService()
    val classLoader1 = service.getClassLoader(setOf(jar1, jar2))
    val classLoader2 = service.getClassLoader(setOf(jar2, jar1))

    assertThat(classLoader1).isSameInstanceAs(classLoader2)
    assertThat(service.getCachedClassLoaderCount()).isEqualTo(1)
  }

  @Test
  fun testCanonicalPathNormalization() {
    val service = TestR8ClassloaderBuildService()
    val subFolder = temporaryFolder.newFolder("sub")
    val relativeSymlinkOrDotPath = File(subFolder, "../${jar1.name}")

    val classLoader1 = service.getClassLoader(setOf(jar1))
    val classLoader2 = service.getClassLoader(setOf(relativeSymlinkOrDotPath))

    assertThat(classLoader1).isSameInstanceAs(classLoader2)
    assertThat(service.getCachedClassLoaderCount()).isEqualTo(1)
  }

  @Test
  fun testDifferentClasspathCreatesDifferentClassLoader() {
    val service = TestR8ClassloaderBuildService()
    val classLoader1 = service.getClassLoader(setOf(jar1))
    val classLoader2 = service.getClassLoader(setOf(jar2))

    assertThat(classLoader1).isNotSameInstanceAs(classLoader2)
    assertThat(service.getCachedClassLoaderCount()).isEqualTo(2)
  }

  @Test
  fun testCreateClassLoaderCompanion() {
    val classLoader = R8ClassloaderBuildService.createClassLoader(setOf(jar1))
    assertThat(classLoader.parent).isEqualTo(ClassLoader.getPlatformClassLoader())
    classLoader.close()
  }

  @Test
  fun testCloseClearsCache() {
    val service = TestR8ClassloaderBuildService()
    service.getClassLoader(setOf(jar1))
    assertThat(service.getCachedClassLoaderCount()).isEqualTo(1)

    service.close()
    assertThat(service.getCachedClassLoaderCount()).isEqualTo(0)
  }
}
