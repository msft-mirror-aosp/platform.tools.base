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

package com.android.build.gradle.integration.r8

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.testutils.truth.PathSubject.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Integration test for the [com.android.build.gradle.internal.tasks.R8AnalysisTask] task. */
class R8AnalysisTaskTest {
  @get:Rule
  val rule =
    GradleRule.from {
      androidJavaApplication {
        android {
          enableKotlin = false
          buildTypes {
            named("release") {
              it.isMinifyEnabled = true
              it.proguardFiles += listOf(File("proguard-rules.pro"), getDefaultProguardFile("proguard-android-optimize.txt"))
            }
          }
        }
        files.add("proguard-rules.pro", "-keep class pkg.name.app.HelloWorld { *; }")
      }
    }

  @Test
  fun testR8AnalysisTaskRunsSuccessfully() {
    val build = rule.build
    val app = build.androidApplication()

    build.executor.run(":app:analyzeReleaseR8Config").apply { assertTask(":app:analyzeReleaseR8Config").didWork() }

    val pbReport = app.resolve("build/reports/r8/r8-config-analyzer-release.pb")
    val htmlReport = app.resolve("build/reports/r8/r8-config-analyzer-release.html")

    assertThat(pbReport).exists()
    assertThat(htmlReport).exists()
  }

  @Test
  fun testMissingKeepRules() {
    val build =
      rule.build {
        androidApplication {
          dependencies { implementation(localJar("lib.jar") { addClassWithEmptyMethods("test/A", "foo()Ltest/B;", "bar()Ltest/C;") }) }
          files.update("proguard-rules.pro").replaceWith("-keep class test.A { *; }")
        }
      }

    val result = build.executor.expectFailure().run(":app:analyzeReleaseR8Config")
    result.assertErrorContains(
      "Missing classes detected while running R8. Please run build with optimization to get missing rules file with list of missing classes."
    )
  }

  @Test
  fun testMinifyTaskNotTriggered() {
    val build = rule.build
    val result = build.executor.run(":app:analyzeReleaseR8Config")
    result.assertTask(":app:analyzeReleaseR8Config").didWork()
    com.google.common.truth.Truth.assertThat(result.tasks).doesNotContain(":app:minifyReleaseWithR8")
  }

  @Test
  fun testR8AnalysisTaskNotTriggeredOnAssemble() {
    val build = rule.build
    val result = build.executor.run(":app:assembleRelease")
    com.google.common.truth.Truth.assertThat(result.tasks).doesNotContain(":app:analyzeReleaseR8Config")
  }
}
