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

import com.android.Version
import com.android.build.api.dsl.AgpTestSuite
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

/**
 * Integration test that verifies the JVM compilation of Compose and cross-module Composable references inside AGP test suites.
 */
class HostJarTestSuiteComposeCompilationTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("com.google.guava:guava:31.1-jre")
        jar("org.junit.platform:junit-platform-engine:1.12.0")
        jar("org.junit.platform:junit-platform-launcher:1.12.0")
        jar("org.junit.platform:junit-platform-commons:1.12.0")
        jar("org.opentest4j:opentest4j:1.3.0")
        jar("org.apiguardian:apiguardian-api:1.1.2")
        jar("org.jetbrains.kotlin:kotlin-stdlib:1.8.20")
        jar("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        jar("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        jar("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
          .addClasses(VerifyingJunitEngine::class.java, VerifyingTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", VerifyingJunitEngine::class.java.name)
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication {
          applyPlugin(PluginType.COMPOSE_COMPILER_PLUGIN)
          android {
            namespace = "com.example.test"
            defaultConfig { minSdk = 21 }
            buildFeatures { compose = true }

            testOptions.suites.create("composeSuite", AgpTestSuite::class.java) {
              it.useJunitEngine.apply {
                includeEngines.add("verifying-junit-engine")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher:1.12.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
                enginesDependencies.add("org.junit.platform:junit-platform-commons:1.12.0")
                enginesDependencies.add("org.opentest4j:opentest4j:1.3.0")
                enginesDependencies.add("org.apiguardian:apiguardian-api:1.1.2")
                enginesDependencies.add("org.jetbrains.kotlin:kotlin-stdlib:1.8.20")
                enginesDependencies.add("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
                addInputProperty(
                  "com.android.junit.engine.expected.classes",
                  "some.compose.AppClassKt, some.compose.TestClassKt",
                )
              }
              it.hostJar {}
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          files {
            // Add a dummy resource file so that generateDebugRFile task generates a non-empty R.jar
            add(
              "src/main/res/values/strings.xml",
              """
              <resources>
                  <string name="app_name">My App</string>
              </resources>
              """
                .trimIndent(),
            )

            // Compose app file compiled with the Compose compiler plugin
            add(
              "src/main/kotlin/some/compose/AppClass.kt",
              """
              package some.compose

              import androidx.compose.runtime.Composable
              import androidx.compose.ui.tooling.preview.Preview

              @Composable
              @Preview
              fun MyWidget() {
              }
              """
                .trimIndent(),
            )

            // Test class inside the test suite referencing a Composable function
            add(
              "src/composeSuite/kotlin/some/compose/TestClass.kt",
              """
              package some.compose

              import androidx.compose.runtime.Composable
              import androidx.compose.ui.tooling.preview.Preview

              class TestClassKt {
                  @Composable
                  @Preview
                  fun TestMyWidget() {
                      MyWidget()
                  }
              }
              """
                .trimIndent(),
            )
          }
          dependencies {
            implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.20")
            implementation("androidx.compose.runtime:runtime:+")
            implementation("androidx.compose.ui:ui-tooling-preview:+")
          }
        }
      }

  @Test
  fun testComposeTestSuiteCompilation() {
    rule.build.executor.run(":app:testComposeSuiteT1DebugTestSuite")
  }
}
