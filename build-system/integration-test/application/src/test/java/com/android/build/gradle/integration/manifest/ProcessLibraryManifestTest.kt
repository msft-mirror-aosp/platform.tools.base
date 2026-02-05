/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.build.gradle.integration.manifest

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

class ProcessLibraryManifestTest {

  private val lib =
    MinimalSubProject.lib()
      .withFile(
        "src/main/AndroidManifest.xml",
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application />
        </manifest>
        """
          .trimIndent(),
      )
      // Debug Overlay: Tries to 'remove' a node that doesn't exist in Main manifest.
      // This forces the Merger to return Result.WARNING.
      .withFile(
        "src/debug/AndroidManifest.xml",
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:tools="http://schemas.android.com/tools">
            <uses-permission android:name="com.test.FORCE_MERGER_WARNING" tools:node="remove" />
        </manifest>
        """
          .trimIndent(),
      )

  @get:Rule
  val project: GradleTestProject =
    GradleTestProject.builder().fromTestApp(MultiModuleTestProject.builder().subproject(":lib", lib).build()).create()

  /** Verifies that [BooleanOption.TREAT_MANIFEST_MERGER_WARNINGS_AS_ERRORS] promotes library manifest warnings to build failures. */
  @Test
  fun testLibraryManifestWarningBecomesErrorWithFlag() {
    val failure =
      project.executor().with(BooleanOption.TREAT_MANIFEST_MERGER_WARNINGS_AS_ERRORS, true).expectFailure().run(":lib:processDebugManifest")

    failure.assertErrorContains("treatManifestMergerWarningsAsErrors is enabled")

    failure.assertErrorContains("com.test.FORCE_MERGER_WARNING")
  }

  /**
   * Verifies that the build succeeds (logging the warning) when [BooleanOption.TREAT_MANIFEST_MERGER_WARNINGS_AS_ERRORS] is explicitly
   * disabled.
   */
  @Test
  fun testLibraryManifestWarningDoesNotFailWithoutFlag() {
    val result = project.executor().with(BooleanOption.TREAT_MANIFEST_MERGER_WARNINGS_AS_ERRORS, false).run(":lib:processDebugManifest")

    result.stdout.use { ScannerSubject.assertThat(it).contains("com.test.FORCE_MERGER_WARNING") }
  }
}
