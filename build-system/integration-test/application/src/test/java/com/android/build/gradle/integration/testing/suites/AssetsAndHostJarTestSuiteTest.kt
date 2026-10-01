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
import com.android.build.api.testsuites.TestEngineInputProperty
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import java.io.File
import java.util.Properties
import kotlin.io.path.inputStream
import org.junit.Rule
import org.junit.Test

/** Tests for test suites declaring both an `assets` and a `hostJar` source set. */
class AssetsAndHostJarTestSuiteTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("org.junit.platform:junit-platform-engine:1.13.3")
        jar("org.junit.platform:junit-platform-launcher:1.13.3")
        jar("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
        jar("com.test:toy-junit-engine:1.0")
          .addClasses(ToyJunitEngineForTesting::class.java, ToyTestDescriptor::class.java, TestEngineLogger::class.java)
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", ToyJunitEngineForTesting::class.java.name)
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication {
          android {
            namespace = "com.example.test"
            testOptions.customSuites.create("first") {
              it.useJunitEngine.apply {
                includeEngines.add("[engine:toy-junit-engine-for-tests]")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                enginesDependencies.add("com.test:toy-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
              }
              it.assets {}
              it.hostJar {}
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          files {
            add("src/first/java/Dummy.java", "public class Dummy {}")
            add("src/first/assets/some_asset.txt", "some asset content")
          }
          dependencies { implementation("com.google.truth:truth:0.44") }
        }
      }

  @Test
  fun testSuiteWithBothSourceSetsRuns() {
    val project = rule.build
    val result = project.executor.run("testDebugFirstT1TestSuite")

    Truth.assertThat(result.didWorkTasks).contains(":app:testDebugFirstT1TestSuite")
    Truth.assertThat(result.didWorkTasks).contains(":app:compileDebugFirstJavaWithJavac")

    val engineInputs =
      Properties().apply {
        project.subProject(":app").buildDir.resolve("intermediates/debug/testDebugFirstT1TestSuite/junit_inputs.txt").inputStream().use {
          load(it)
        }
      }
    Truth.assertThat(engineInputs.getProperty(TestEngineInputProperty.SOURCE_FOLDERS)).contains("src${File.separator}first")
    Truth.assertThat(engineInputs.getProperty(TestEngineInputProperty.BINARY_FOLDERS)).isNotEmpty()
  }

  @Test
  fun testSuiteWithBothSourceSetsConfigures() {
    rule.build.executor.run(":app:tasks")
  }
}
