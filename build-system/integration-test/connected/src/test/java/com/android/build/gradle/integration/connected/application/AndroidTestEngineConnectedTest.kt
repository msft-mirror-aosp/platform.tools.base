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

package com.android.build.gradle.integration.connected.application

import com.android.build.api.dsl.AgpTestSuite
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils
import com.android.tools.bazel.avd.Emulator
import org.gradle.api.Project
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

class AndroidTestEngineConnectedTest {
  companion object {
    @ClassRule
    @JvmField
    val emulatorRule1 =
      if (TestUtils.runningFromBazel()) {
        Emulator(System.getProperty("EMULATOR_SCRIPT_PATH"), 5554)
      } else {
        object : ExternalResource() {}
      }

    @ClassRule
    @JvmField
    val emulatorRule2 =
      if (TestUtils.runningFromBazel()) {
        Emulator(System.getProperty("EMULATOR_SCRIPT_PATH"), 5556)
      } else {
        object : ExternalResource() {}
      }
  }

  @get:Rule
  val rule =
    GradleRule.configure().from {
      gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
      androidApplication {
        android {
          testOptions.suites.create("myAndroidTestSuite", AgpTestSuite::class.java) {
            it.testApk {
              dependencies {
                implementation.add("org.jetbrains.kotlin:kotlin-stdlib:1.8.20")
                implementation.add("junit:junit:4.13.2")
                implementation.add("androidx.test:core:1.4.0-alpha06")
                implementation.add("androidx.test.ext:junit:1.1.3-alpha02")
                implementation.add("androidx.test:monitor:1.4.0-alpha06")
                implementation.add("androidx.test:rules:1.4.0-alpha06")
                implementation.add("androidx.test:runner:1.4.0-alpha06")
              }
            }
            it.useJunitEngine.apply {
              inputs.add(AgpTestSuiteInputParameters.TESTED_APKS)
              inputs.add(AgpTestSuiteInputParameters.TEST_APKS)
              inputs.add(AgpTestSuiteInputParameters.ADB_EXECUTABLE)
              inputs.add(AgpTestSuiteInputParameters.AAPT2_EXECUTABLE)
              includeEngines.add("android-test-engine")
              addInputProperty("android-test.listener.stream-base64-encoded-result", "true")
              addInputProperty("android-test.instrumentation-runner-class", "androidx.test.runner.AndroidJUnitRunner")
              addInputProperty("android-test.test-package-id", "pkg.name.app.test")
              addInputProperty("android-test.instrumentation-target-package-id", "pkg.name.app")
              addInputProperty("android-test.uninstall-after-tests", "true")
              enginesDependencies.add("com.android.tools.androidtest:android-test-engine:+")
              enginesDependencies.add("com.android.tools.androidtest:android-test-engine-result-listener:+")
              enginesDependencies.add("org.junit.platform:junit-platform-engine:+")
              enginesDependencies.add("org.junit.platform:junit-platform-launcher:+")
            }
            it.targetVariants.add("debug")
            it.targets.create("t1") {}
          }

          defaultConfig { testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
        }

        files {
          add(
            "src/myAndroidTestSuite/kotlin/com/example/android/ExampleInstrumentedTest.kt",
            // language=kotlin
            """
            package com.example.android

            import androidx.test.ext.junit.runners.AndroidJUnit4

            import org.junit.Test
            import org.junit.runner.RunWith

            @RunWith(AndroidJUnit4::class)
            class ExampleInstrumentedTest {
                @Test
                fun exampleTestCase1() {}

                @Test
                fun exampleTestCase2() {}
            }
            """
              .trimIndent(),
          )
        }

        pluginCallbacks += ConfigureTestTaskCallback::class.java
      }
    }

  class ConfigureTestTaskCallback : GenericCallback {
    override fun handleProject(project: Project) {
      project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java).configureEach { task ->
        task.testLogging.apply {
          events("passed", "skipped", "failed")
          showStandardStreams = true
          showExceptions = true
          exceptionFormat = TestExceptionFormat.FULL
          showCauses = true
          showStackTraces = true
        }
      }
    }
  }

  val executor: GradleTaskExecutor
    get() = rule.build.executor.withEnableInfoLogging(false)

  @Test
  fun runBasicAndroidTestUsingJUnitTestEngine() {
    val result = executor.run(":app:testMyAndroidTestSuiteT1DebugTestSuite")

    result.assertOutputContains("emulator-5554 - 13 > com.example.android.ExampleInstrumentedTest.exampleTestCase1 PASSED")
    result.assertOutputContains("emulator-5554 - 13 > com.example.android.ExampleInstrumentedTest.exampleTestCase2 PASSED")
    result.assertOutputContains("emulator-5556 - 13 > com.example.android.ExampleInstrumentedTest.exampleTestCase1 PASSED")
    result.assertOutputContains("emulator-5556 - 13 > com.example.android.ExampleInstrumentedTest.exampleTestCase2 PASSED")
  }

  @Test
  fun verifyTestResultListenerOutput() {
    val result =
      executor
        .withArgument("-Pcom.android.tools.utp.GradleAndroidProjectResolverExtension.enable=true")
        .run(":app:testMyAndroidTestSuiteT1DebugTestSuite")

    result.assertOutputContains("<UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
    result.assertOutputContains("</UTP_TEST_RESULT_ON_TEST_RESULT_EVENT>")
  }
}
