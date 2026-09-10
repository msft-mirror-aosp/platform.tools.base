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

import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.tasks.TestSuiteTestTask
import com.android.testutils.TestUtils
import com.android.tools.bazel.avd.Emulator
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource

/**
 * Integration test verifying that AndroidTestEngine executes tests on multiple devices in parallel when parallel test result reporting is
 * enabled.
 *
 * Because Gradle's built-in Test task does not support concurrent container reporting (failing with IllegalStateException when container
 * events are interleaved), this test invokes the JUnit Platform Console Launcher manually from a custom task.
 */
class AndroidTestEngineParallelReportingConnectedTest {
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
          testOptions.customSuites.create("myAndroidTestSuite") {
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
              addInputProperty("android-test.test-package-id", "com.example.android.test")
              addInputProperty("android-test.instrumentation-target-package-id", "com.example.android")
              addInputProperty("android-test.uninstall-after-tests", "true")
              enginesDependencies.add("com.android.tools.androidtest:android-test-engine:+")
              enginesDependencies.add("com.android.tools.androidtest:android-test-engine-result-listener:+")
              enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
              enginesDependencies.add("org.junit.platform:junit-platform-launcher:1.12.0")
            }
            it.targetVariants.add("debug")
            it.targets.create("t1") {}
          }

          namespace = "com.example.android"
          defaultConfig {
            applicationId = "com.example.android"
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
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

        pluginCallbacks += ConfigureCustomTestTaskCallback::class.java
      }
    }

  class JunitPlatformArgs(
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val testedApks: FileCollection,
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) val testApks: FileCollection,
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val adbExecutable: Provider<RegularFile>,
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) val aapt2Executable: Provider<RegularFile>,
    @get:Input val engineInputProperties: Provider<Map<String, String>>,
  ) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
      val testedApkPath = testedApks.files.joinToString(",") { it.absolutePath }
      val testApkPath = testApks.files.joinToString(",") { it.absolutePath }
      val adbPath = adbExecutable.get().asFile.absolutePath
      val aapt2Path = aapt2Executable.get().asFile.absolutePath

      val args = mutableListOf<String>()
      args.add("execute")
      args.add("--scan-classpath")
      args.add("--include-engine=android-test-engine")
      args.add("--details=tree")
      args.add("--details-theme=ascii")
      args.add("--disable-ansi-colors")

      engineInputProperties.get().forEach { (k, v) ->
        args.add("--config=$k=$v")
      }

      args.add("--config=android-test.parallel-test-result-reporting=true")
      args.add("--config=android-test.device-serials=emulator-5554,emulator-5556")
      args.add("--config=android-test.adb-path=$adbPath")
      args.add("--config=android-test.aapt2-path=$aapt2Path")
      args.add("--config=android-test.tested-apks=$testedApkPath")
      args.add("--config=android-test.test-apks=$testApkPath")

      return args
    }
  }

  class ConfigureCustomTestTaskCallback : GenericCallback {
    override fun handleProject(project: Project) {
      val junitConsoleConfig = project.configurations.create("junitConsole")
      project.dependencies.add(
        "junitConsole",
        "org.junit.platform:junit-platform-console-standalone:1.12.0",
      )
      val engineDep = project.dependencies.create("com.android.tools.androidtest:android-test-engine:+") as ExternalModuleDependency
      engineDep.exclude(mapOf("group" to "org.junit.platform"))
      junitConsoleConfig.dependencies.add(engineDep)

      project.afterEvaluate {
        val testSuiteTask = project.tasks.named("testMyAndroidTestSuiteT1DebugTestSuite", TestSuiteTestTask::class.java)

        val testedApks = project.objects.fileCollection()
        val testApks = project.objects.fileCollection()
        val adbExecutable = project.objects.fileProperty()
        val aapt2Executable = project.objects.fileProperty()
        val inputProperties = project.objects.mapProperty(String::class.java, String::class.java)

        testSuiteTask.configure { task ->
          task.enabled = false
          testedApks.from(task.engineInputParameters.get().first { it.type == AgpTestSuiteInputParameters.TESTED_APKS }.fileCollection)
          testApks.from(task.engineInputParameters.get().first { it.type == AgpTestSuiteInputParameters.TEST_APKS }.fileCollection)
          adbExecutable.set(task.buildTools.adbExecutable())
          aapt2Executable.set(task.buildTools.aapt2ExecutableProvider())
          inputProperties.set(task.engineInputProperties)
        }

        project.tasks.register("runAndroidTestEngineCustom", JavaExec::class.java) { customTask ->
          customTask.classpath = junitConsoleConfig
          customTask.mainClass.set("org.junit.platform.console.ConsoleLauncher")
          customTask.dependsOn(testSuiteTask)
          customTask.outputs.upToDateWhen { false }

          customTask.argumentProviders.add(
            JunitPlatformArgs(
              testedApks = testedApks,
              testApks = testApks,
              adbExecutable = adbExecutable,
              aapt2Executable = aapt2Executable,
              engineInputProperties = inputProperties,
            )
          )
        }
      }
    }
  }

  val executor: GradleTaskExecutor
    get() = rule.build.executor.withEnableInfoLogging(false).withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.OFF)

  @Test
  fun runParallelAndroidTestUsingJUnitTestEngine() {
    val result = executor.run(":app:runAndroidTestEngineCustom")

    result.assertOutputContains("emulator-5554")
    result.assertOutputContains("emulator-5556")
    result.assertOutputContains("com.example.android.ExampleInstrumentedTest")
    result.assertOutputContains("exampleTestCase1")
    result.assertOutputContains("exampleTestCase2")
    result.assertOutputContains("4 tests successful")
  }
}
