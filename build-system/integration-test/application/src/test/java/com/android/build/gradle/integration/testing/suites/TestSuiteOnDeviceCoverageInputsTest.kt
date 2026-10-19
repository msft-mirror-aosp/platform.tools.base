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
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Properties
import org.junit.Rule
import org.junit.Test

/**
 * Verifies the test engine inputs of a device test suite when the on-device coverage agent is enabled
 * (`android.experimental.androidTest.coverage.useOnDeviceAgent`).
 *
 * The toy engine doesn't request any APK, so the suite runs without a device and the test only inspects the inputs AGP passed.
 */
class TestSuiteOnDeviceCoverageInputsTest {

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
        gradleProperties {
          add(BooleanOption.TEST_SUITE_SUPPORT, true)
          add(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
        }
        androidApplication(":app") {
          android {
            namespace = "com.example.app"
            defaultConfig { applicationId = "com.example.app" }
            // The tested application id no longer matches the namespace the app's classes are in.
            buildTypes { named("debug") { it.applicationIdSuffix = ".debug" } }
            testOptions.customSuites.create("deviceSuite") {
              it.codeCoverage = true
              it.useJunitEngine.apply {
                includeEngines.add("[engine:toy-junit-engine-for-tests]")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                enginesDependencies.add("com.test:toy-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
              }
              it.testApk {}
              it.targetVariants.add("debug")
              it.targets.create("t1") {}
            }
          }
          files {
            add(
              "src/deviceSuite/java/com/example/app/SuiteTestClass.java",
              """
              package com.example.app;
              public class SuiteTestClass {}
              """
                .trimIndent(),
            )
          }
        }
      }

  @Test
  fun suiteWithCoveragePassesTheNamespaceAsCoveragePrefix() {
    rule.build.executor.run(":app:$TEST_TASK")

    val inputs = engineInputs()
    assertThat(inputs.getProperty(COVERAGE_TYPE)).isEqualTo("ON_THE_FLY")
    assertThat(inputs.getProperty(INSTRUMENTATION_ARGS)).isEqualTo("com.android.tools.coverage.prefixes=com/example/app")
  }

  @Test
  fun suiteWithCoverageKeepsItsOwnInstrumentationArgs() {
    val build = rule.build {
      androidApplication(":app") {
        android { testOptions.customSuites.named("deviceSuite") { it.useJunitEngine.addInputProperty(INSTRUMENTATION_ARGS, "foo=bar") } }
      }
    }

    build.executor.run(":app:$TEST_TASK")

    assertThat(engineInputs().getProperty(INSTRUMENTATION_ARGS)).isEqualTo("com.android.tools.coverage.prefixes=com/example/app,foo=bar")
  }

  @Test
  fun suiteWithoutCoverageDoesNotUseTheAgent() {
    val build = rule.build {
      androidApplication(":app") { android { testOptions.customSuites.named("deviceSuite") { it.codeCoverage = false } } }
    }

    build.executor.run(":app:$TEST_TASK")

    val inputs = engineInputs()
    assertThat(inputs.getProperty(COVERAGE_TYPE)).isEqualTo("NONE")
    assertThat(inputs.getProperty(INSTRUMENTATION_ARGS)).isNull()
  }

  private fun engineInputs(): Properties {
    val inputsFile = File(rule.build.androidApplication(":app").buildDir.toFile(), "intermediates/debug/$TEST_TASK/junit_inputs.txt")
    return Properties().also { properties -> inputsFile.reader(Charsets.UTF_8).use { properties.load(it) } }
  }

  companion object {
    private const val TEST_TASK = "testDebugDeviceSuiteT1TestSuite"
    private const val COVERAGE_TYPE = "com.android.agp.test.COVERAGE_TYPE"
    private const val INSTRUMENTATION_ARGS = "android-test.instrumentation-args"
  }
}
