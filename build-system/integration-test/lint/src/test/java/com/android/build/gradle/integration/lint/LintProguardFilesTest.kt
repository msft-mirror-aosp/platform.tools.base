/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.integration.lint

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.testutils.truth.PathSubject.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test

class LintProguardFilesTest {

  @get:Rule
  val appRule =
    GradleRule.from {
      androidApplication(":appProject") {
        android {
          buildTypes { named("release") { it.isMinifyEnabled = true } }
          lint {
            abortOnError = false
            textOutput = File("lint-results.txt")
            error += "ByteOrderMark"
          }
        }
      }
    }

  @get:Rule
  val libRule =
    GradleRule.from {
      androidLibrary(":libProject") {
        android {
          lint {
            abortOnError = false
            textOutput = File("lint-results.txt")
            error += "ByteOrderMark"
          }
        }
      }
    }

  // regression for b/67156629
  @Test
  fun testIssueFromProguardFile() {
    val build =
      appRule.build {
        androidApplication(":appProject") { android { buildTypes { named("release") { it.proguardFiles("proguard-rules.pro") } } } }
          .files { add("proguard-rules.pro", "foo.\ufeffbar") }
      }
    build.executor.run("lintRelease")
    assertThat(build.directory.resolve("appProject/lint-results.txt"))
      .contains("proguard-rules.pro:1: Error: Found byte-order-mark in the middle of a file")
  }

  @Test
  fun testIssueFromProguardFileInSourceSet() {
    val build =
      appRule.build { androidApplication(":appProject") {}.files { add("src/main/keepRules/proguard-rules.keep", "foo.\ufeffbar") } }
    build.executor.run("lintRelease")
    assertThat(build.directory.resolve("appProject/lint-results.txt"))
      .contains("proguard-rules.keep:1: Error: Found byte-order-mark in the middle of a file")
  }

  // regression for b/67156629
  @Test
  fun testIssueFromConsumerProguardFile() {
    val build =
      libRule.build {
        androidLibrary(":libProject") { android { buildTypes { defaultConfig { consumerProguardFiles("consumer-rules.pro") } } } }
          .files { add("consumer-rules.pro", "foo.\ufeffbar") }
      }
    build.executor.run("lint")
    assertThat(build.directory.resolve("libProject/lint-results.txt"))
      .contains("consumer-rules.pro:1: Error: Found byte-order-mark in the middle of a file")
  }
}
