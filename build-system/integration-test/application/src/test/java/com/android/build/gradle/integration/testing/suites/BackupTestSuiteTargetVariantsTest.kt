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

import com.android.build.api.dsl.BackupTestSuite
import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

/** Integration test verifying which variants a [BackupTestSuite] targets, with and without `targetVariants`. */
class BackupTestSuiteTargetVariantsTest {
  @get:Rule
  val rule = GradleRule.from {
    gradleProperties {
      add(BooleanOption.TEST_SUITE_SUPPORT, true)
      add(BooleanOption.ENABLE_BACKUP_TEST, true)
    }
    androidApplication {
      android {
        buildTypes { create("staging") {} }
        flavorDimensions += "tier"
        productFlavors {
          create("free") { it.dimension = "tier" }
          create("paid") { it.dimension = "tier" }
        }
        testOptions.backupTests.create("myBackup") {}
        testOptions.backupTests.create("releaseBackup") { it.targetVariants.add("paidRelease") }
      }
    }
  }

  @Test
  fun testTargetsTestedBuildTypeUnlessTargetVariantsAreSet() {
    val result = rule.build.executor.run(":app:tasks", "--all")

    result.assertSuiteTargets("MyBackup", "FreeDebug", "PaidDebug")
    result.assertSuiteTargets("ReleaseBackup", "PaidRelease")
  }

  @Test
  fun testDefaultFollowsTestBuildType() {
    val result = rule.build { androidApplication { android { testBuildType = "staging" } } }.executor.run(":app:tasks", "--all")

    result.assertSuiteTargets("MyBackup", "FreeStaging", "PaidStaging")
    result.assertSuiteTargets("ReleaseBackup", "PaidRelease")
  }

  /** Asserts that [suite] has a test task for each of [variants], and for no other variant. */
  private fun GradleBuildResult.assertSuiteTargets(suite: String, vararg variants: String) {
    for (variant in ALL_VARIANTS) {
      val task = "test$variant${suite}DefaultTestSuite"
      if (variant in variants) assertOutputContains(task) else assertOutputDoesNotContain(task)
    }
  }

  private companion object {
    val ALL_VARIANTS = listOf("FreeDebug", "PaidDebug", "FreeRelease", "PaidRelease", "FreeStaging", "PaidStaging")
  }
}
