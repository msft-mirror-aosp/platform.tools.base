/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.build.gradle.integration.library

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.HelloWorldLibraryApp
import com.android.testutils.truth.PathSubject.assertThat
import com.android.testutils.truth.ZipFileSubject.assertThat
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Tests for [BundleLibraryJavaRes]. */
@RunWith(Parameterized::class)
class BundleLibraryJavaResTest(val enableOptimizations: Boolean) {

  companion object {
    @JvmStatic @Parameterized.Parameters(name = "enableOptimizations={0}") fun data() = listOf(true)
  }

  @get:Rule
  val project =
    GradleTestProject.builder()
      .fromTestApp(HelloWorldLibraryApp.create())
      .addGradleProperties("android.experimental.enableJavaResourceOptimizations=$enableOptimizations")
      .create()

  @Test
  fun testTaskSkippedWhenNoJavaRes() {
    val taskName = if (enableOptimizations) ":lib:compressDebugJavaRes" else ":lib:processDebugJavaRes"

    // first test that the task is skipped when there are no java resources.
    project.executor().run(taskName).run { assertThat(this.skippedTasks).contains(taskName) }

    project.projectDir.resolve("lib/src/main/resources").mkdirs()
    project.executor().run(taskName).run { assertThat(this.skippedTasks).contains(taskName) }

    project.projectDir.resolve("lib/src/main/resources/foo.txt").createNewFile()
    project.executor().run(taskName).run { assertThat(this.didWorkTasks).contains(taskName) }

    // then test that the task is up-to-date if nothing changes.
    project.executor().run(taskName).run { assertThat(this.upToDateTasks).contains(taskName) }

    // then test that the task does work after the java resource is removed (since it must be
    // removed from the task's output).
    project.projectDir.resolve("lib/src/main/resources").deleteRecursively()
    project.executor().run(taskName).run { assertThat(this.didWorkTasks).contains(taskName) }

    // finally test that the task is skipped if we build again with no java resources.
    project.executor().run(taskName).run { assertThat(this.skippedTasks).contains(taskName) }
  }
}
