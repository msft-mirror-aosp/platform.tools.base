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

import com.android.build.gradle.internal.services.R8ClassloaderBuildService
import com.android.builder.dexing.R8ToolsImpl
import com.android.testutils.TestInputsGenerator
import com.android.testutils.TestUtils
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CreateR8ToolsTest {
  @get:Rule val temporaryFolder = TemporaryFolder()

  @Test
  fun withoutIsolatedClassLoader_usesAgpsOwnImplementation() {
    val r8Tools = createR8Tools(null)

    // AGP's own copy, which runs the R8 bundled with AGP.
    assertThat(r8Tools).isInstanceOf(R8ToolsImpl::class.java)
    assertThat(r8Tools.javaClass.classLoader).isSameInstanceAs(R8ToolsImpl::class.java.classLoader)
  }

  @Test
  fun withIsolatedClassLoader_usesImplementationFromThatLoader() {
    val r8Jar = temporaryFolder.newFile("r8.jar")
    R8ClassloaderBuildService.createClassLoader(setOf(r8Jar)).use { isolatedLoader ->
      val r8Tools = createR8Tools(isolatedLoader)

      // Loaded by the isolated loader, so it runs the R8 in that loader...
      assertThat(r8Tools.javaClass.classLoader).isSameInstanceAs(isolatedLoader)
      // ...and it's a different class from AGP's own R8ToolsImpl, even though the name is the same.
      assertThat(r8Tools.javaClass).isNotSameInstanceAs(R8ToolsImpl::class.java)
    }
  }

  @Test
  fun runTraceReferences_throughIsolatedClassLoader() {
    val androidJar = TestUtils.resolvePlatformPath("android.jar", TestUtils.TestType.AGP)
    val sourceJar = temporaryFolder.root.toPath().resolve("source.jar")
    TestInputsGenerator.jarWithEmptyClasses(sourceJar, listOf("test/Source"))

    R8ClassloaderBuildService.createClassLoader(setOf(r8Jar)).use { isolatedLoader ->
      val r8Tools = createR8Tools(isolatedLoader)
      assertThat(r8Tools.javaClass.classLoader).isSameInstanceAs(isolatedLoader)

      // All references resolve against android.jar, so the check passes.
      r8Tools.runTraceReferences(listOf(sourceJar), listOf(androidJar), emptyMap())

      // Without android.jar, java.lang.Object can't be resolved, so the isolated R8's check fails.
      assertThrows(Throwable::class.java) { r8Tools.runTraceReferences(listOf(sourceJar), emptyList(), emptyMap()) }
    }
  }

  private val r8Jar: File by lazy { File(Class.forName("com.android.tools.r8.R8").protectionDomain.codeSource.location.toURI()) }
}
