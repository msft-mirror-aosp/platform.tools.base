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

package com.android.build.gradle.integration.lint

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.Rule
import org.junit.Test

/**
 * Regression test for KMT-625: for the legacy `com.android.library` + `androidTarget()` path, lint must scan the Android compilation's
 * shared source sets (e.g. commonMain), not just androidMain.
 *
 * Uses [GradleRule] rather than a fixture subproject because this combo needs `builtInKotlin` and `newDsl` disabled under AGP 9.x, and
 * those flags must stay scoped to this test.
 */
class KotlinMultiplatformLegacyAndroidTargetLintTest {

  @get:Rule
  val rule =
    GradleRule.configure().disableBrokenBuiltInKotlinOptOutChecks().disableBrokenNewDslOptOutChecks().from {
      androidLibrary(":lib", createMinimumProject = false) {
        applyPlugin(PluginType.KOTLIN_MPP)
        pluginCallbacks += Callback::class.java
        android {
          compileSdk = DEFAULT_COMPILE_SDK_VERSION
          namespace = "com.example.lib"
          defaultConfig { minSdk = 21 }
          lint {
            abortOnError = false
            textReport = true
            enable += "ByteOrderMark"
          }
        }
        files {
          setupMinimumManifest()
          add(
            "src/commonMain/kotlin/com/example/lib/Foo.kt",
            // language=kotlin
            """
              package com.example.lib

              // ByteOrderMark issue, in commonMain only
              fun getByteOrderMark(): String {
                  return "$byteOrderMark"
              }
              """
              .trimIndent(),
          )
        }
      }
      gradleProperties {
        add(BooleanOption.BUILT_IN_KOTLIN, false)
        add(BooleanOption.USE_NEW_DSL, false)
      }
    }

  class Callback : GenericCallback {
    override fun handleProject(project: Project) {
      project.extensions.getByType(KotlinMultiplatformExtension::class.java).androidTarget()
    }
  }

  @Test
  fun `lint scans commonMain on legacy androidTarget library`() {
    rule.build.executor.withFailOnWarning(false).run(":lib:clean", ":lib:lintDebug")
    val report = rule.build.directory.resolve("lib/build/reports/lint-results-debug.txt")
    assertThat(report).exists()
    assertThat(report).contains("Found byte-order-mark in the middle of a file [ByteOrderMark]")
  }

  companion object {
    private const val byteOrderMark = "\ufeff"
  }
}
