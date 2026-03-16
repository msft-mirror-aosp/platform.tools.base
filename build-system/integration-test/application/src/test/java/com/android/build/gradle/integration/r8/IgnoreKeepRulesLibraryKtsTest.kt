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
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.MavenRepoGenerator
import com.android.testutils.TestInputsGenerator.jarWithTextEntries
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test

class IgnoreKeepRulesLibraryKtsTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        library(MavenRepoGenerator.Library(LIB_FOO_ID, jarWithTextEntries(PROGUARD_PATH to LIB_FOO_RULE)))
        library(MavenRepoGenerator.Library(LIB_BAR_ID, jarWithTextEntries(PROGUARD_PATH to LIB_BAR_RULE)))
      }
      .from {
        androidApplication {
          android {
            defaultConfig { minSdk = 33 }
            buildTypes { named("debug") { it.optimization { enable = true } } }
          }

          dependencies {
            implementation(LIB_FOO_ID)
            implementation(LIB_BAR_ID)
          }
        }
      }

  @Test
  fun testIgnoreAllKeepRulesOff() {
    rule.build.executor.with(BooleanOption.R8_GRADUAL_API, true).run(":app:minifyDebugWithR8")
    var configuration = rule.build.androidApplication().outputsDir.resolve("mapping/debug/configuration.txt")
    assertThat(configuration).contains(LIB_FOO_RULE)
    assertThat(configuration).contains(LIB_BAR_RULE)
  }

  @Test
  fun testIgnoreAllKeepRulesOn() {
    rule.build {
      androidApplication {
        android { buildTypes { named("debug") { it.optimization { keepRules { ignoreFromAllExternalDependencies = true } } } } }
      }
    }

    rule.build.executor.with(BooleanOption.R8_GRADUAL_API, true).run(":app:minifyDebugWithR8")
    var configuration = rule.build.androidApplication().outputsDir.resolve("mapping/debug/configuration.txt")
    assertThat(configuration).doesNotContain(LIB_BAR_RULE)
    assertThat(configuration).doesNotContain(LIB_FOO_RULE)
  }

  companion object {
    private const val LIB_FOO_ID = "com.example:foo:1.0.0"
    private const val LIB_BAR_ID = "com.example:bar:1.0.0"
    private const val LIB_FOO_RULE = "-keep class foo { *; }"
    private const val LIB_BAR_RULE = "-keep class bar { *; }"
    private const val PROGUARD_PATH = "META-INF/proguard/rules.txt"
  }
}
