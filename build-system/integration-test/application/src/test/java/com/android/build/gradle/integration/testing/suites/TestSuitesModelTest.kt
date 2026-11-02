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

package com.android.build.gradle.integration.testing.suites

import com.android.build.gradle.integration.common.fixture.model.ReferenceModelComparator
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.v2.ide.SyncIssue
import org.junit.Test

/**
 * Snapshots the sync models of an application with test suites, compared to the same application without test suites.
 *
 * The project has a host jar test suite targeting the debug and release variants, a test APK test suite, and a mixed test suite with
 * assets, host jar and test APK sources, both targeting the debug variant.
 */
class TestSuitesModelTest :
  ReferenceModelComparator(
    referenceConfig = {
      gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
      androidApplication { android { namespace = "com.example.app" } }
    },
    deltaConfig = {
      androidApplication {
        android {
          testOptions.customSuites.create("hostJarSuite") {
            it.useJunitEngine.includeEngines.add("junit-jupiter")
            it.hostJar {}
            it.targetVariants.add("debug")
            it.targetVariants.add("release")
            it.targets.create("jvm") {}
          }
          testOptions.customSuites.create("testApkSuite") {
            it.useJunitEngine.includeEngines.add("junit-jupiter")
            it.testApk {}
            it.targetVariants.add("debug")
            it.targets.create("device") {}
          }
          testOptions.customSuites.create("mixedSuite") {
            it.useJunitEngine.includeEngines.add("junit-jupiter")
            it.assets {}
            it.hostJar {}
            it.testApk {}
            it.targetVariants.add("debug")
            it.targets.create("jvm") {}
            it.targets.create("device") {}
          }
        }
      }
    },
    syncOptions = { ignoreSyncIssues(SyncIssue.SEVERITY_WARNING) },
    variantName = "debug",
  ) {

  @Test
  fun `test BasicAndroidProject model`() {
    compareBasicAndroidProjectWith(goldenFileSuffix = "BasicAndroidProject")
  }

  @Test
  fun `test AndroidProject model`() {
    compareAndroidProjectWith(goldenFileSuffix = "AndroidProject")
  }

  @Test
  fun `test AndroidDsl model`() {
    ensureAndroidDslDeltaIsEmpty()
  }

  @Test
  fun `test VariantDependencies model`() {
    compareVariantDependenciesWith(goldenFileSuffix = "VariantDependencies")
  }
}
