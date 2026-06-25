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
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

/**
 * Consolidated integration test for verifying Java, Kotlin, and Mixed-language compilation outputs and their wiring to the
 * [VerifyingJunitEngine].
 */
class HostJarTestSuiteCompilationTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("com.google.guava:guava:31.1-jre")
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

            // Java Suite
            testOptions.suites.create("javaSuite", AgpTestSuite::class.java) {
              it.useJunitEngine.apply {
                includeEngines.add("verifying-junit-engine")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-commons:1.13.3")
                enginesDependencies.add("org.opentest4j:opentest4j:1.3.0")
                enginesDependencies.add("org.apiguardian:apiguardian-api:1.1.2")
                enginesDependencies.add("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
                addInputProperty(
                  "com.android.junit.engine.expected.classes",
                  "some.java.AppClass, some.java.TestClass, com.google.common.collect.Lists",
                )
              }
              it.hostJar { dependencies { implementation.add("com.google.guava:guava:31.1-jre") } }
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }

            // Kotlin Suite
            testOptions.suites.create("kotlinSuite", AgpTestSuite::class.java) {
              it.useJunitEngine.apply {
                includeEngines.add("verifying-junit-engine")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-commons:1.13.3")
                enginesDependencies.add("org.opentest4j:opentest4j:1.3.0")
                enginesDependencies.add("org.apiguardian:apiguardian-api:1.1.2")
                enginesDependencies.add("org.jetbrains.kotlin:kotlin-stdlib:1.8.20")
                enginesDependencies.add("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
                addInputProperty("com.android.junit.engine.expected.classes", "some.kotlin.AppClassKt, some.kotlin.TestClassKt")
              }
              it.hostJar {}
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }

            // Mixed Suite
            testOptions.suites.create("mixedSuite", AgpTestSuite::class.java) {
              it.useJunitEngine.apply {
                includeEngines.add("verifying-junit-engine")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
                enginesDependencies.add("org.junit.platform:junit-platform-commons:1.13.3")
                enginesDependencies.add("org.opentest4j:opentest4j:1.3.0")
                enginesDependencies.add("org.apiguardian:apiguardian-api:1.1.2")
                enginesDependencies.add("org.jetbrains.kotlin:kotlin-stdlib:1.8.20")
                enginesDependencies.add("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
                addInputProperty(
                  "com.android.junit.engine.expected.classes",
                  "some.mixed.AppClassJ, some.mixed.AppClassK, some.mixed.TestClassJ, some.mixed.TestClassK",
                )
              }
              it.hostJar {}
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          files {
            // Java files
            add("src/main/java/some/java/AppClass.java", "package some.java; public class AppClass {}")
            add(
              "src/javaSuite/java/some/java/TestClass.java",
              "package some.java; import com.google.common.collect.Lists; import java.util.List; public class TestClass { AppClass a = new AppClass(); List<String> list = Lists.newArrayList(\"a\", \"b\"); }",
            )

            // Kotlin files
            add("src/main/kotlin/some/kotlin/AppClassKt.kt", "package some.kotlin; class AppClassKt")
            add("src/kotlinSuite/kotlin/some/kotlin/TestClassKt.kt", "package some.kotlin; class TestClassKt { val a = AppClassKt() }")

            // Mixed files
            add("src/main/java/some/mixed/AppClassJ.java", "package some.mixed; public class AppClassJ {}")
            add("src/main/kotlin/some/mixed/AppClassK.kt", "package some.mixed; class AppClassK")
            add(
              "src/mixedSuite/java/some/mixed/TestClassJ.java",
              "package some.mixed; public class TestClassJ { AppClassK k = new AppClassK(); }",
            )
            add("src/mixedSuite/kotlin/some/mixed/TestClassK.kt", "package some.mixed; class TestClassK { val j = AppClassJ() }")
          }
          dependencies { implementation("org.jetbrains.kotlin:kotlin-stdlib:1.8.20") }
        }
      }

  @Test
  fun testJavaCompilation() {
    rule.build.executor.run(":app:testJavaSuiteT1DebugTestSuite")
  }

  @Test
  fun testKotlinCompilation() {
    rule.build.executor.run(":app:testKotlinSuiteT1DebugTestSuite")
  }

  @Test
  fun testMixedCompilation() {
    rule.build.executor.run(":app:testMixedSuiteT1DebugTestSuite")
  }
}
