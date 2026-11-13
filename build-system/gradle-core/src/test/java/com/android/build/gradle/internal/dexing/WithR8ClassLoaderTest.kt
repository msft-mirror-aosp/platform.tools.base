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

import com.android.Version
import com.android.build.gradle.internal.fixtures.FakeGradleProperty
import com.android.build.gradle.internal.services.R8ClassloaderBuildService
import com.android.build.gradle.internal.tasks.testR8Jar
import com.android.builder.dexing.R8Version
import com.google.common.truth.Truth.assertThat
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildServiceParameters
import org.junit.Assert.assertThrows
import org.junit.Test

/** Unit tests for [withR8ClassLoader]. */
class WithR8ClassLoaderTest {

  private class TestR8ClassloaderBuildService : R8ClassloaderBuildService() {
    override fun getParameters(): BuildServiceParameters.None {
      throw UnsupportedOperationException()
    }
  }

  @Test
  fun withBuildService_usesItsCachedClassLoader() {
    val service = TestR8ClassloaderBuildService()
    val property: Property<R8ClassloaderBuildService> = FakeGradleProperty(service)
    try {
      withR8ClassLoader(setOf(testR8Jar), property) { r8ClassLoader ->
        assertThat(r8ClassLoader).isSameInstanceAs(service.getClassLoader(setOf(testR8Jar)))
        assertThat(r8ClassLoader.loadClass("com.android.tools.r8.R8").classLoader).isSameInstanceAs(r8ClassLoader)
      }
      assertThat(service.getCachedClassLoaderCount()).isEqualTo(1)
    } finally {
      service.close()
    }
  }

  @Test
  fun withoutBuildService_usesOneOffIsolatedClassLoader() {
    withR8ClassLoader(setOf(testR8Jar)) { r8ClassLoader ->
      assertThat(r8ClassLoader).isNotNull()
      assertThat(r8ClassLoader.loadClass("com.android.tools.r8.R8").classLoader).isSameInstanceAs(r8ClassLoader)
    }
  }

  @Test
  fun emptyClasspath_throws() {
    val e = assertThrows(IllegalArgumentException::class.java) { withR8ClassLoader(emptySet()) {} }
    assertThat(e)
      .hasMessageThat()
      .isEqualTo(
        """
        |No R8 classpath was configured for this task or transform. This is an AGP bug; please file a bug at
        |https://issuetracker.google.com, using 'Android Gradle Plugin' as component name.
        |AGP version: ${Version.ANDROID_GRADLE_PLUGIN_VERSION}, default R8 version: ${R8Version.VERSION_AGP_WAS_SHIPPED_WITH}.
        """
          .trimMargin()
      )
  }
}
