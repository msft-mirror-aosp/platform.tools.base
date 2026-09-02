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
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.output.ApkSubject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThatApk
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * Integration test verifying Custom Test Suites with TEST_APK source type:
 * 1. Source delegation: compiles test suite sources into the test APK, excluding main app sources.
 * 2. Manifest creation config: generates instrumented test manifest targeting tested app with proper attributes (handleProfiling,
 *    functionalTest, testLabel) without merging main app activities.
 * 3. Dependency isolation: does not package the main app's runtime dependencies into the test APK.
 */
class TestSuiteApkIntegrationTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("org.junit.platform:junit-platform-engine:1.13.3")
        jar("org.junit.platform:junit-platform-launcher:1.13.3")
        jar("com.test:toy-junit-engine:1.0")
          .addClasses(ToyJunitEngineForTesting::class.java, ToyTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", ToyJunitEngineForTesting::class.java.name)
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication(":app") {
          android {
            namespace = "com.example.test"
            testOptions.customSuites.create("customTest") {
              it.useJunitEngine.apply {
                includeEngines.add("[engine:toy-junit-engine-for-tests]")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                enginesDependencies.add("com.test:toy-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
                inputs.add(AgpTestSuiteInputParameters.TEST_APKS)
              }
              // Configures this test suite to generate a standalone Test APK
              // targeting the debug variant of the main application.
              it.testApk {}
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          // Main app depends on :lib to test dependency isolation in the Test APK.
          dependencies { implementation(project(":lib")) }
          files {
            // Main application manifest defining an activity.
            // This activity must NOT be merged into the test APK's manifest.
            update("src/main/AndroidManifest.xml")
              .replaceWith(
                """
                <?xml version="1.0" encoding="utf-8"?>
                <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                    <application>
                        <activity android:name=".MainActivity" android:exported="true" />
                    </application>
                </manifest>
                """
                  .trimIndent()
              )
            // Main application classes: verify these are NOT compiled into the test APK.
            add(
              "src/main/java/com/example/test/MainActivity.java",
              """
              package com.example.test;
              public class MainActivity {}
              """
                .trimIndent(),
            )
            add(
              "src/main/java/com/example/test/MainAppClass.java",
              """
              package com.example.test;
              public class MainAppClass {}
              """
                .trimIndent(),
            )
            // Test suite source: must be compiled into the test APK via source delegation.
            add(
              "src/customTest/java/com/example/test/SuiteTestClass.java",
              """
              package com.example.test;
              public class SuiteTestClass {}
              """
                .trimIndent(),
            )
          }
        }
        androidLibrary(":lib") {
          android {
            namespace = "com.example.lib"
          }
          files {
            // Library dependency class: should only exist in the main app runtime classpath,
            // and must NOT be packaged into the test APK.
            add(
              "src/main/java/com/example/lib/LibClass.java",
              """
              package com.example.lib;
              public class LibClass {}
              """
                .trimIndent(),
            )
          }
        }
      }

  @Test
  fun testTestApkSourcesManifestAndDependencyIsolation() {
    // Packaging the test APK triggers compilation, manifest processing, and packaging
    // for the custom test suite.
    val result = rule.build.executor.run(":app:packageCustomTestTestApkDebug")

    // Verify expected tasks executed
    assertThat(result.didWorkTasks).contains(":app:compileCustomTestTestApkDebugJavaWithJavac")
    assertThat(result.didWorkTasks).contains(":app:processCustomTestTestApkDebugManifest")
    assertThat(result.didWorkTasks).contains(":app:packageCustomTestTestApkDebug")

    val buildDir = rule.build.androidApplication(":app").buildDir.toFile()
    val testApk = buildDir.walkTopDown().firstOrNull { it.name.endsWith(".apk") && it.name.contains("customTest", ignoreCase = true) }
    assertThat(testApk).isNotNull()

    // 1. Verify Class and Dependency Assertions via TruthHelper:
    assertThatApk(testApk).apply {
      // Source Delegation: Test suite's sources (SuiteTestClass) are compiled into the test APK,
      // but the main application's sources (MainAppClass, MainActivity) are excluded.
      containsClass("Lcom/example/test/SuiteTestClass;")
      doesNotContainClass("Lcom/example/test/MainAppClass;")
      doesNotContainClass("Lcom/example/test/MainActivity;")

      // Dependency Isolation: The main app's runtime dependencies (:lib -> LibClass)
      // are not bundled into the test APK classes (preventing dex collisions and duplicate classes).
      doesNotContainClass("Lcom/example/lib/LibClass;")
    }

    // 2. Verify Manifest Creation Config via ApkSubject:
    ApkSubject.assertThat(testApk!!) {
      // Test manifest targets the tested application, includes instrumented test attributes,
      // and does not merge main app activities.
      manifest().contains("android:targetPackage=\"com.example.test\"")
      manifest().contains("android:handleProfiling=false")
      manifest().contains("android:functionalTest=false")
      manifest().contains("android:label=\"Tests for com.example.test\"")
      manifest().doesNotContain("MainActivity")
    }
  }
}
