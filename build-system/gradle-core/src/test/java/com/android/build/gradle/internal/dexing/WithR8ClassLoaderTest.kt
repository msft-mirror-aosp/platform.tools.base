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

package com.android.build.gradle.internal.dexing

import com.android.build.gradle.internal.fixtures.FakeGradleProperty
import com.android.build.gradle.internal.services.R8ClassloaderBuildService
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildServiceParameters
import org.junit.Test

/** Unit tests for [withR8ClassLoader]. */
class WithR8ClassLoaderTest {

  private class TestR8ClassloaderBuildService : R8ClassloaderBuildService() {
    override fun getParameters(): BuildServiceParameters.None {
      throw UnsupportedOperationException()
    }
  }

  private val r8Jar: File by lazy { File(Class.forName("com.android.tools.r8.R8").protectionDomain.codeSource.location.toURI()) }

  @Test
  fun emptyClasspath_passesNull() {
    withR8ClassLoader(emptySet<File>()) { r8ClassLoader -> assertThat(r8ClassLoader).isNull() }
  }

  @Test
  fun withBuildService_usesItsCachedClassLoader() {
    val service = TestR8ClassloaderBuildService()
    val property: Property<R8ClassloaderBuildService> = FakeGradleProperty(service)
    try {
      withR8ClassLoader(setOf(r8Jar), property) { r8ClassLoader ->
        assertThat(r8ClassLoader).isSameInstanceAs(service.getClassLoader(setOf(r8Jar)))
        assertThat(r8ClassLoader!!.loadClass("com.android.tools.r8.R8").classLoader).isSameInstanceAs(r8ClassLoader)
      }
      assertThat(service.getCachedClassLoaderCount()).isEqualTo(1)
    } finally {
      service.close()
    }
  }

  @Test
  fun withoutBuildService_usesOneOffIsolatedClassLoader() {
    withR8ClassLoader(setOf(r8Jar)) { r8ClassLoader ->
      assertThat(r8ClassLoader).isNotNull()
      assertThat(r8ClassLoader!!.loadClass("com.android.tools.r8.R8").classLoader).isSameInstanceAs(r8ClassLoader)
    }
  }
}
