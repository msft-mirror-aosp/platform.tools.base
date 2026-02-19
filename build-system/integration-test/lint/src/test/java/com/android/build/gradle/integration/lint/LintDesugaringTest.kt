/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.DESUGAR_DEPENDENCY_VERSION
import com.android.build.gradle.integration.common.fixture.SUPPORT_LIB_VERSION
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject
import java.io.File
import org.gradle.api.JavaVersion
import org.junit.Rule
import org.junit.Test

/** Integration test for lint analyzing library desugaring from Gradle. */
class LintDesugaringTest {

  @get:Rule
  val rule =
    GradleRule.fromProject("lintDesugaring") {
      androidApplication(":app") {
        android {
          namespace = "com.example.android.lint.kotlin"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }

          defaultConfig {
            minSdk { version = release(24) }
            targetSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          }

          lint {
            disable += "GradleDependency" // such that we don't flag newly available Kotlin versions etc
            xmlReport = true
            xmlOutput = File("lint-report.xml")
            textReport = true
            checkOnly += "NewApi"
          }

          compileOptions {
            isCoreLibraryDesugaringEnabled = true
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
          }
        }

        dependencies {
          implementation(project(":library"))
          coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:$DESUGAR_DEPENDENCY_VERSION")
        }
      }

      androidLibrary(":library") {
        android {
          namespace = "com.example.android.lint.desugaring.library"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }

          defaultConfig { minSdk { version = release(24) } }

          lint {
            disable += "GradleDependency" // such that we don't flag newly available Kotlin versions etc
            xmlReport = true
            xmlOutput = File("lint-report.xml")
            textReport = true
            checkOnly += "NewApi"
          }

          compileOptions {
            isCoreLibraryDesugaringEnabled = true
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
          }
        }

        dependencies {
          api("com.android.support:appcompat-v7:$SUPPORT_LIB_VERSION")
          coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:$DESUGAR_DEPENDENCY_VERSION")
        }
      }

      gradleProperties {
        // Disabled due to a dependency on com.android.support:animated-vector-drawable:28.0.0
        add(BooleanOption.ENFORCE_UNIQUE_PACKAGE_NAMES, false)
      }
    }

  @Test
  fun checkFindErrors() {
    val build = rule.build

    build.executor.run(":app:clean", ":app:lintDebug", ":library:lintDebug")

    val appReport = build.androidApplication(":app").buildDir.resolve("reports/lint-results-debug.txt")
    PathSubject.assertThat(appReport).contains("No issues found.")
    val libReport = build.androidLibrary(":library").buildDir.resolve("reports/lint-results-debug.txt")
    PathSubject.assertThat(libReport).contains("No issues found.")
  }
}
