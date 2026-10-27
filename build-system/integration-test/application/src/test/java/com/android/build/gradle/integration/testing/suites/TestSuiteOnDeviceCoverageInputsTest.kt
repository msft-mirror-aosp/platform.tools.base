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
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.Properties
import org.gradle.api.Project
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

  @Test
  fun suiteWithOfflineCoverageEnablesDeviceCoverageWithoutAgent() {
    rule.build.executor.with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, false).run(":app:$TEST_TASK")

    val inputs = engineInputs()
    assertThat(inputs.getProperty(COVERAGE_TYPE)).isEqualTo("NONE")
    assertThat(inputs.getProperty("android-test.is-test-coverage-enabled")).isEqualTo("true")
    assertThat(inputs.getProperty(INSTRUMENTATION_ARGS)).isNull()
  }

  @Test
  fun suiteForwardsCliInstrumentationArgsTestsFilterAndLeaveApksFlag() {
    val build = rule.build {
      androidApplication(":app") {
        pluginCallbacks += FailOnlyOnT2Callback::class.java
        android {
          testOptions.customSuites.named("deviceSuite") { suite ->
            suite.codeCoverage = false
            suite.targets.create("t2") {}
          }
        }
      }
    }

    build.executor
      .withArgument("-Pandroid.testInstrumentationRunnerArguments.custom=a,b=c")
      .withArgument("-Pandroid.testInstrumentationRunnerArguments.useTestStorageService=true")
      .withArgument("-Pandroid.testInstrumentationRunnerArguments.coverageFile=/data/local/tmp/cov.ec")
      .with(BooleanOption.ANDROID_TEST_LEAVE_APKS_INSTALLED_AFTER_RUN, true)
      .withArguments(listOf(":app:$TEST_TASK", "--tests", "com.example.app.SuiteTestClass.myMethod"))
      .run()

    val inputs = engineInputs()
    assertThat(inputs.getProperty("android-test.uninstall-after-tests")).isEqualTo("false")
    assertThat(inputs.getProperty("android-test.use-test-storage-service")).isEqualTo("true")
    assertThat(inputs.getProperty("android-test.coverage-file-on-device")).isEqualTo("/data/local/tmp/cov.ec")
    val args = inputs.getProperty(INSTRUMENTATION_ARGS)
    assertThat(args).contains("custom=a\\,b=c")
    assertThat(args).contains("tests_regex=^com[.#]example[.#]app[.#]SuiteTestClass[.#]myMethod(?:\\[.*\\])?(?:#.*)?$")

    // Target-scoped configureTestTasks on t2 applies to t2 without leaking to t1 above.
    val t2Failure = build.executor.expectFailure().run(":app:testDebugDeviceSuiteT2TestSuite")
    t2Failure.assertErrorContains("There were failing tests")
  }

  class FailOnlyOnT2Callback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.finalizeDsl { extension ->
        extension.testOptions.customSuites.getByName("deviceSuite").targets.getByName("t2").configureTestTasks {
          systemProperty("toyEngine.failure", "failed-only-on-t2")
        }
      }
    }
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
