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
import com.android.testutils.apk.Zip
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

/**
 * Integration test verifying that Android Resources are accessible, successfully compiled, and packaged into apk-for-local-test.ap_ within
 * HostJar Test Suites.
 */
class HostJarTestSuiteAndroidResourcesTest {

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
        jar("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
          .addClasses(VerifyingJunitEngine::class.java, VerifyingTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", VerifyingJunitEngine::class.java.name)
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication {
          android {
            namespace = "com.example.test"

            testOptions.unitTests.isIncludeAndroidResources = true

            // Android Resources Suite
            testOptions.suites.create("androidResSuite", AgpTestSuite::class.java) {
              it.useJunitEngine.apply {
                inputs.add(com.android.build.api.dsl.AgpTestSuiteInputParameters.RESOURCES_AP_ARCHIVE)
                includeEngines.add("verifying-junit-engine")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher:1.12.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
                enginesDependencies.add("org.junit.platform:junit-platform-commons:1.12.0")
                enginesDependencies.add("org.opentest4j:opentest4j:1.3.0")
                enginesDependencies.add("org.apiguardian:apiguardian-api:1.1.2")
                enginesDependencies.add("com.android.build.gradle.integration.testing.suites:verifying-junit-engine:1.0")
                addInputProperty("com.android.junit.engine.expected.classes", "some.androidres.TestClass")
              }
              it.hostJar {}
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          files {
            add(
              "src/main/res/values/strings.xml",
              """
              <resources>
                  <string name="app_name">My Application</string>
              </resources>
              """
                .trimIndent(),
            )
            add(
              "src/androidResSuite/java/some/androidres/TestClass.java",
              """
              package some.androidres;
              public class TestClass {
                public void testResourceAccess() {
                   int stringRes = com.example.test.R.string.app_name;
                }
              }
              """
                .trimIndent(),
            )
          }
        }
      }

  @Test
  fun testAndroidResourcesCompilationAndPackaging() {
    rule.build.executor.run(":app:testAndroidResSuiteT1DebugTestSuite")

    val intermediatesDir = rule.build.androidApplication().intermediatesDir

    val apkForLocalTest =
      intermediatesDir.resolve("apk_for_local_test").toFile().walkTopDown().firstOrNull { it.name == "apk-for-local-test.ap_" }

    assertThat(apkForLocalTest).isNotNull()

    Zip(apkForLocalTest!!).use { zip -> assertThat(zip.entries.map { it.toString() }).contains("/resources.arsc") }
  }
}
