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
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

/**
 * Integration test that uses [VerifyingJunitEngine] to ensure that Kotlin compilation outputs are correctly wired to the test engine via
 * BINARY_FOLDERS.
 */
class VerifyingHostJarTestSuiteKotlinCompilationTest {
  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("org.junit.platform:junit-platform-engine:1.13.3")
        jar("org.junit.platform:junit-platform-launcher:1.13.3")
        jar("org.junit.platform:junit-platform-commons:1.13.3")
        jar("org.opentest4j:opentest4j:1.3.0")
        jar("org.apiguardian:apiguardian-api:1.1.2")
        jar("org.jetbrains.kotlin:kotlin-stdlib:1.8.20")
        jar("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
          .addClasses(VerifyingJunitEngine::class.java, VerifyingTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", VerifyingJunitEngine::class.java.name)
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication {
          android {
            namespace = "com.example.test"
            testOptions.customSuites.create("first") {
              it.useJunitEngine.apply {
                includeEngines.add("verifying-junit-engine")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-commons:1.13.3")
                enginesDependencies.add("org.opentest4j:opentest4j:1.3.0")
                enginesDependencies.add("org.apiguardian:apiguardian-api:1.1.2")
                enginesDependencies.add("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")

                // Specify expected classes to be found and loaded
                addInputProperty("com.android.junit.engine.expected.classes", "some.random.TestTarget, some.random.Test1")
              }
              it.hostJar {}
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          files {
            add(
              "src/main/kotlin/some/random/TestTarget.kt",
              """
              package some.random

              class TestTarget {
                  fun someFunctionToBeTested(value: String): String {
                      return "Tested " + value
                  }
              }
              """
                .trimIndent(),
            )
            add(
              "src/first/kotlin/some/random/Test1.kt",
              """
              package some.random

              class Test1 {
                  fun testSomething() {
                      val testTarget = TestTarget()
                      testTarget.someFunctionToBeTested("some!")
                  }
              }
              """
                .trimIndent(),
            )
          }
          dependencies { implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.20") }
        }
      }

  @Test
  fun testWiring() {
    rule.build.executor.run(":app:testFirstT1DebugTestSuite")
  }
}
