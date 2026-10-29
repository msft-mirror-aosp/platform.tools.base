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

package com.android.build.gradle.internal.tasks

import com.android.build.gradle.internal.services.R8ClassloaderBuildService
import com.android.builder.dexing.R8ToolsImpl
import com.google.common.truth.Truth.assertThat
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
}
