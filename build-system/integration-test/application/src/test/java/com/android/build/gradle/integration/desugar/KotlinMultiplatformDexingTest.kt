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

package com.android.build.gradle.integration.desugar

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformDexingTest {
  @get:Rule
  val rule =
    GradleRule.from {
      androidKotlinMultiplatformLibrary(":library") {
        android { withDeviceTestBuilder {} }
        files {
          add(
            "src/androidDeviceTest/java/AndroidDeviceTest.kt",
            """
            package foo.bar

            class AndroidDeviceTest {}
            """
              .trimIndent(),
          )
        }
      }
    }

  // regression test for b/460470375
  @Test
  fun testDesugarGraphFoundInSecondRun() {
    val dexTask = ":library:dexBuilderAndroidDeviceTest"

    rule.build.executor.withArgument("--no-build-cache").run(dexTask)

    val library = rule.build.kotlinMultiplatformLibrary(":library")
    library.files.update("src/androidDeviceTest/java/AndroidDeviceTest.kt").moveTo("src/androidDeviceTest/kotlin/AndroidDeviceTest.kt")

    val result = rule.build.executor.withArgument("--no-build-cache").run(dexTask)

    result.assertOutputDoesNotContain("Failed to read desugaring graph")
  }
}
