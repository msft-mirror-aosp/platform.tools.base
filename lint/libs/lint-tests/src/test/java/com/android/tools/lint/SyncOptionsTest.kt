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

package com.android.tools.lint

import com.android.tools.lint.model.DefaultLintModelLintOptions
import com.android.tools.lint.model.DefaultLintModelModule
import com.android.tools.lint.model.LintModelModuleType
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncOptionsTest {

  @Test
  fun testSyncToPreservesExplicitBaseline() {
    val flags = LintCliFlags()
    val explicitBaseline = File("explicit-baseline.xml")
    flags.baselineFile = explicitBaseline

    val modelBaseline = File("model-baseline.xml")
    val lintOptions = DefaultLintModelLintOptions(baselineFile = modelBaseline)
    val module = createModule(lintOptions)

    syncTo(module, flags)
    assertEquals(explicitBaseline, flags.baselineFile)
  }

  @Test
  fun testSyncToAppliesModelBaselineWhenFlagNotSet() {
    val flags = LintCliFlags()
    assertNull(flags.baselineFile)

    val modelBaseline = File("model-baseline.xml")
    val lintOptions = DefaultLintModelLintOptions(baselineFile = modelBaseline)
    val module = createModule(lintOptions)

    syncTo(module, flags)
    assertEquals(modelBaseline, flags.baselineFile)
  }

  private fun createModule(lintOptions: DefaultLintModelLintOptions): DefaultLintModelModule {
    return DefaultLintModelModule(
      loader = null,
      dir = File("."),
      modulePath = ":",
      type = LintModelModuleType.LIBRARY,
      mavenName = null,
      agpVersion = null,
      buildFolder = File("build"),
      lintOptions = lintOptions,
      lintRuleJars = emptyList(),
      resourcePrefix = null,
      dynamicFeatures = emptyList(),
      bootClassPath = emptyList(),
      javaSourceLevel = "17",
      compileTarget = "android-34",
      variants = emptyList(),
      highlightGradualR8Api = false,
      neverShrinking = false,
    )
  }
}
