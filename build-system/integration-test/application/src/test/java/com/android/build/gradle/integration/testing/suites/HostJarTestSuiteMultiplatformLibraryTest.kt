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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

/**
 * Checks that a host-jar test suite resolves the Android variant of Kotlin Multiplatform libraries (`androidx.compose.runtime:runtime`
 * 1.7.0 via `org.jetbrains.kotlin.platform.type=androidJvm`, and 1.10.0 via both `KotlinPlatformType` and `TargetJvmEnvironment=android`).
 */
class HostJarTestSuiteMultiplatformLibraryTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("org.junit.platform:junit-platform-engine:1.13.3")
        jar("org.junit.platform:junit-platform-launcher:1.13.3")
        jar("org.junit.platform:junit-platform-commons:1.13.3")
        jar("org.opentest4j:opentest4j:1.3.0")
        jar("org.apiguardian:apiguardian-api:1.1.2")
        // Transitive dependencies of the Android variant of androidx.compose.runtime:runtime.
        jar("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        jar("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        jar("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
          .addClasses(VerifyingJunitEngine::class.java, VerifyingTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", VerifyingJunitEngine::class.java.name)
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidLibrary(":lib") {
          android {
            namespace = "com.example.lib"
            testOptions.customSuites.create("kmpUnit") {
              it.useVerifyingJunitEngine()
              // FloatingPointEquality_androidKt is packaged only in the Android variant of androidx.compose.runtime:runtime 1.7.0.
              it.useJunitEngine.addInputProperty(
                "com.android.junit.engine.expected.classes",
                "some.lib.KmpSuiteTest,androidx.compose.runtime.internal.FloatingPointEquality_androidKt",
              )
              it.hostJar { dependencies { implementation.add("androidx.compose.runtime:runtime:1.7.0") } }
              it.targetVariants.add("debug")
              it.targets.apply { create("jvm") {} }
            }
          }
          files { add("src/kmpUnit/java/some/lib/KmpSuiteTest.java", "package some.lib; public class KmpSuiteTest {}") }
        }
        // Version 1.10.0 publishes both org.jetbrains.kotlin.platform.type=androidJvm and org.gradle.jvm.environment=android.
        androidLibrary(":lib2") {
          android {
            namespace = "com.example.lib2"
            testOptions.customSuites.create("kmpUnit") {
              it.useVerifyingJunitEngine()
              it.useJunitEngine.addInputProperty(
                "com.android.junit.engine.expected.classes",
                "some.lib2.KmpSuiteTest,androidx.compose.runtime.collection.ArrayUtils_androidKt",
              )
              it.hostJar { dependencies { implementation.add("androidx.compose.runtime:runtime:1.10.0") } }
              it.targetVariants.add("debug")
              it.targets.apply { create("jvm") {} }
            }
          }
          files { add("src/kmpUnit/java/some/lib2/KmpSuiteTest.java", "package some.lib2; public class KmpSuiteTest {}") }
        }
      }

  @Test
  fun hostJarSuiteRunsAgainstAndroidVariantOfMultiplatformLibrary() {
    rule.build.executor.run(":lib:testDebugKmpUnitJvmTestSuite", ":lib2:testDebugKmpUnitJvmTestSuite")
  }

  /** With no Kotlin plugin, the suite requests `androidJvm` through AGP's string attribute and its schema rules. */
  @Test
  fun hostJarSuiteRunsAgainstAndroidVariantOfMultiplatformLibraryWithoutKotlinPlugin() {
    val build =
      rule.configure().disableBrokenBuiltInKotlinOptOutChecks().build { gradleProperties { add(BooleanOption.BUILT_IN_KOTLIN, false) } }
    build.executor.run(":lib:testDebugKmpUnitJvmTestSuite", ":lib2:testDebugKmpUnitJvmTestSuite")
  }
}
