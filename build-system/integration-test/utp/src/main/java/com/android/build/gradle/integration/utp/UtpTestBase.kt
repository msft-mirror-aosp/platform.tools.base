/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.integration.utp

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.utp.plugins.host.device.info.proto.AndroidTestDeviceInfoProto.AndroidTestDeviceInfo
import com.google.common.truth.Truth.assertThat
import com.google.testing.platform.proto.api.core.TestSuiteResultProto.TestSuiteResult
import java.io.File
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assume
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runners.Parameterized.Parameters

/**
 * A base test class for UTP integration tests. Tests defined in this class will be executed against both connected check and managed
 * devices to ensure the feature parity.
 */
abstract class UtpTestBase(val runWithBuiltInPlatform: Boolean) {

  companion object {
    @JvmStatic
    @Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))

    const val ANDROIDX_TEST_VERSION = "1.5.0-alpha02"
  }

  lateinit var testTaskName: String
  lateinit var testResultXmlPath: String
  lateinit var testReportPath: String
  lateinit var testResultPbPath: String
  lateinit var testCoverageXmlPath: String
  lateinit var testLogcatPath: String
  lateinit var testAdditionalOutputPath: String

  val ruleBuilder = GradleRule.configure()

  @get:Rule
  val rule =
    ruleBuilder.from {
      androidApplication {
        android {
          namespace = "com.example.android.kotlin"
          installation { timeOutInMs = 30000 }
          defaultConfig {
            minSdk = 21
            versionCode = 1
            versionName = "1.0"
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          dependencies {
            androidTestImplementation("androidx.test:core:1.4.0-alpha06")
            androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
            androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
            androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
            androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
          }
        }
        kotlin { jvmToolchain(17) }
        files {
          add(
            "src/main/java/com/example/android/kotlin/MainActivity.kt",
            // language=kotlin
            """
            package com.example.android.kotlin

            import android.app.Activity
            import java.util.logging.Logger.getLogger

            class MainActivity : Activity() {
                companion object {
                    fun stubFuncForTestingCodeCoverage() {
                        getLogger("MainActivity").info("stubFuncForTestingCodeCoverage()")
                    }
                }
            }
            """
              .trimIndent(),
          )
          add(
            "src/androidTest/java/com/example/android/kotlin/InstrumentedTest.kt",
            // language=kotlin
            """
            package com.example.android.kotlin

            import androidx.test.ext.junit.runners.AndroidJUnit4

            import org.junit.Test
            import org.junit.runner.RunWith

            import java.util.logging.Logger.getLogger

            @RunWith(AndroidJUnit4::class)
            class ExampleInstrumentedTest {
                private val logger = getLogger("TestLogger")

                @Test
                fun useAppContext() {
                    logger.info("test logs")
                    MainActivity.stubFuncForTestingCodeCoverage()
                }
            }
            """
              .trimIndent(),
          )
          add(
            "src/main/res/values/strings.xml",
            // language=xml
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="title_dynamicfeature1">dynamicfeature1</string>
            </resources>
            """
              .trimIndent(),
          )
        }
      }

      androidLibrary {
        android {
          namespace = "com.example.android.kotlin.library"
          installation { timeOutInMs = 30000 }
          defaultConfig {
            minSdk = 21
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          dependencies {
            androidTestImplementation("androidx.test:core:1.4.0-alpha06")
            androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
            androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
            androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
            androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
          }
        }
        kotlin { jvmToolchain(17) }
        files {
          add(
            "src/androidTest/java/com/example/android/kotlin/lib/InstrumentedTest.kt",
            // language=kotlin
            """
            package com.example.android.kotlin.lib

            import androidx.test.ext.junit.runners.AndroidJUnit4

            import org.junit.Test
            import org.junit.runner.RunWith

            @RunWith(AndroidJUnit4::class)
            class ExampleInstrumentedTest {
                @Test
                fun useAppContext() {}
            }
            """
              .trimIndent(),
          )
        }
      }

      androidTest {
        android {
          namespace = "com.example.android.kotlin.testonly"
          targetProjectPath = ":app"
          installation { timeOutInMs = 30000 }
          defaultConfig {
            minSdk = 21
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          dependencies {
            implementation("androidx.test:core:1.4.0-alpha06")
            implementation("androidx.test.ext:junit:1.1.3-alpha02")
            implementation("androidx.test:monitor:1.4.0-alpha06")
            implementation("androidx.test:rules:1.4.0-alpha06")
            implementation("androidx.test:runner:1.4.0-alpha06")
          }
        }
        kotlin { jvmToolchain(17) }
        files {
          add(
            "src/main/java/com/example/android/kotlin/InstrumentedTest.kt",
            // language=kotlin
            """
            package com.example.android.kotlin

            import androidx.test.ext.junit.runners.AndroidJUnit4
            import org.junit.Test
            import org.junit.runner.RunWith

            @RunWith(AndroidJUnit4::class)
            class ExampleInstrumentedTest {
                @Test
                fun useAppContext() {}
            }
            """
              .trimIndent(),
          )
        }
      }

      androidFeature {
        android {
          namespace = "com.example.android.kotlin.feature"
          installation { timeOutInMs = 30000 }
          defaultConfig {
            minSdk = 21
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          dependencies {
            implementation(project(":app"))
            implementation("androidx.test:core:1.4.0-alpha06")
            implementation("androidx.test.ext:junit:1.1.3-alpha02")
            implementation("androidx.test:monitor:1.4.0-alpha06")
            implementation("androidx.test:rules:1.4.0-alpha06")
            implementation("androidx.test:runner:1.4.0-alpha06")
          }
        }
        kotlin { jvmToolchain(17) }
        files {
          remove("src/main/AndroidManifest.xml")
          add(
            "src/main/AndroidManifest.xml",
            // language=xml
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                      xmlns:dist="http://schemas.android.com/apk/distribution"
                      android:versionCode="1">

                <dist:module
                    dist:instant="false"
                    dist:title="@string/title_dynamicfeature1">
                    <dist:delivery>
                        <dist:on-demand />
                    </dist:delivery>
                    <dist:fusing dist:include="true" />
                </dist:module>
            </manifest>
            """
              .trimIndent(),
          )
          add(
            "src/main/java/com/example/android/kotlin/feature/DynamicFeature1.kt",
            // language=kotlin
            """
            package com.example.android.kotlin.feature

            import java.util.logging.Logger.getLogger

            class DynamicFeature1 () {
                companion object {
                    fun stubDynamicFeature1FuncForTestingCodeCoverage() {
                        getLogger("DynamicFeature1").info("stubDynamicFeature1FuncForTestingCodeCoverage()")
                    }
                }
            }
            """
              .trimIndent(),
          )
          add(
            "src/androidTest/java/com/example/android/kotlin/feature/InstrumentedTest.kt",
            // language=kotlin
            """
            package com.example.android.kotlin.feature

            import androidx.test.ext.junit.runners.AndroidJUnit4

            import org.junit.Test
            import org.junit.runner.RunWith

            import java.util.logging.Logger.getLogger

            @RunWith(AndroidJUnit4::class)
            class ExampleInstrumentedTest {
                private val logger = getLogger("TestLogger")

                @Test
                fun useAppContext() {
                    logger.info("test logs")
                    DynamicFeature1.stubDynamicFeature1FuncForTestingCodeCoverage()
                }
            }
            """
              .trimIndent(),
          )
        }
      }

      androidApplication(":emptyAppProject") {}

      gradleProperties { add(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform) }
    }

  val project: Path
    get() = rule.build.directory

  val executor: GradleTaskExecutor
    get() =
      rule.build.executor
        .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        .withEnableInfoLogging(false)
        .configureGradleTaskExecutor()

  open fun GradleTaskExecutor.configureGradleTaskExecutor(): GradleTaskExecutor {
    return this
  }

  abstract fun selectModule(moduleName: String)

  private fun AndroidProjectDefinition<out CommonExtension>.enableAndroidTestOrchestrator() {
    android.testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"
    android.defaultConfig.testInstrumentationRunnerArguments["useTestStorageService"] = "true"
    android.defaultConfig.testInstrumentationRunnerArguments["clearPackageData"] = "true"

    dependencies {
      add("androidTestUtil", "androidx.test:orchestrator:$ANDROIDX_TEST_VERSION")
      add("androidTestUtil", "androidx.test.services:test-services:$ANDROIDX_TEST_VERSION")
    }
  }

  private fun AndroidProjectDefinition<out CommonExtension>.enableForceCompilation() {
    android.experimentalProperties["android.experimental.force-aot-compilation"] = true
  }

  private fun AndroidProjectDefinition<out CommonExtension>.enableCodeCoverage() {
    android.buildTypes.apply { named("debug") { it.enableAndroidTestCoverage = true } }
    android.defaultConfig.testInstrumentationRunnerArguments["useTestStorageService"] = "true"

    dependencies { add("androidTestUtil", "androidx.test.services:test-services:$ANDROIDX_TEST_VERSION") }
  }

  private fun AndroidProjectDefinition<out CommonExtension>.enableTestStorageService() {
    dependencies { add("androidTestUtil", "androidx.test.services:test-services:$ANDROIDX_TEST_VERSION") }
  }

  private fun AndroidProjectDefinition<out ApplicationExtension>.enableDynamicFeature(subProjectName: String) {
    android.dynamicFeatures.add(":$subProjectName")
  }

  private fun getDeviceInfo(testResultPb: File): AndroidTestDeviceInfo? {
    val testSuiteResult = testResultPb.inputStream().use { TestSuiteResult.parseFrom(it) }
    return testSuiteResult.testResultList
      .asSequence()
      .flatMap { testResult -> testResult.outputArtifactList }
      .filter { artifact -> artifact.label.label == "device-info" && artifact.label.namespace == "android" }
      .map { artifact -> File(artifact.sourcePath.path).inputStream().use { AndroidTestDeviceInfo.parseFrom(it) } }
      .firstOrNull()
  }

  @Test
  @Throws(Exception::class)
  fun androidTestWithCodeCoverage() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("app")
    rule.build.androidApplication().reconfigure { enableCodeCoverage() }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<method name="stubFuncForTestingCodeCoverage" desc="()V" line="9">""")
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<counter type="INSTRUCTION" missed="3" covered="5"/>""")
  }

  @Test
  @Throws(Exception::class)
  fun androidTestWithTestFailures() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("app")

    rule.build.androidApplication().reconfigure {
      files {
        add(
          "src/androidTest/java/com/example/android/kotlin/FailingInstrumentedTest.kt",
          // language=kotlin
          """
          package com.example.android.kotlin

          import androidx.test.ext.junit.runners.AndroidJUnit4

          import org.junit.Assert
          import org.junit.Test
          import org.junit.runner.RunWith

          @RunWith(AndroidJUnit4::class)
          class FailingInstrumentedTest {

              @Test
              fun useAppContext() {
                  Assert.fail()
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.expectFailure().run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
  }

  @Test
  @Throws(Exception::class)
  fun androidTest() {
    selectModule("app")

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath).resolveSibling("index.html")).exists()
    assertThat(project.resolve(testReportPath)).exists()

    // TODO(b/476442048): Support test result proto validation for the built-in test platform.
    //   For now, we skip these assertions because the new JUnit engine implementation does not
    //   yet produce these artifacts in the expected locations.
    if (runWithBuiltInPlatform) {
      return
    }

    assertThat(project.resolve(testResultPbPath)).exists()
  }

  @Test
  @Throws(Exception::class)
  fun androidTestWithOrchestrator() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("app")

    rule.build.androidApplication().reconfigure { enableAndroidTestOrchestrator() }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
  }

  @Test
  @Throws(Exception::class)
  fun androidTestWithOrchestratorAndCodeCoverage() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("app")

    rule.build.androidApplication().reconfigure {
      enableAndroidTestOrchestrator()
      enableCodeCoverage()
    }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<method name="stubFuncForTestingCodeCoverage" desc="()V" line="9">""")
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<counter type="INSTRUCTION" missed="3" covered="5"/>""")
  }

  @Test
  @Throws(Exception::class)
  fun connectedAndroidTestWithLogcat() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("app")

    executor.run(testTaskName)

    assertThat(project.resolve(testLogcatPath)).exists()
    val logcatText = project.resolve(testLogcatPath).readText()
    assertThat(logcatText).contains("TestRunner: started: useAppContext(com.example.android.kotlin.ExampleInstrumentedTest)")
    assertThat(logcatText).contains("TestLogger: test logs")
    assertThat(logcatText).contains("TestRunner: finished: useAppContext(com.example.android.kotlin.ExampleInstrumentedTest)")
  }

  @Test
  @Throws(Exception::class)
  fun connectedAndroidTestFromTestOnlyModule() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("test")

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
  }

  @Test
  @Throws(Exception::class)
  fun additionalTestOutputWithTestStorageService() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("app")

    rule.build.androidApplication().reconfigure {
      enableTestStorageService()
      files {
        add(
          "src/androidTest/java/com/example/helloworld/TestStorageServiceExampleTest.kt",
          // language=kotlin
          """
          package com.example.helloworld

          import androidx.test.ext.junit.runners.AndroidJUnit4
          import androidx.test.services.storage.TestStorage
          import org.junit.Test
          import org.junit.runner.RunWith

          @RunWith(AndroidJUnit4::class)
          class TestStorageServiceExampleTest {
              @Test
              fun writeFileUsingTestStorageService() {
                  TestStorage().openOutputFile("myTestStorageOutputFile1").use {
                      it.write("output message1".toByteArray())
                  }
                  TestStorage().openOutputFile("myTestStorageOutputFile2.txt").use {
                      it.write("output message2".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/myTestStorageOutputFile3").use {
                      it.write("output message3".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/nested/myTestStorageOutputFile4").use {
                      it.write("output message4".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/white space/myTestStorageOutputFile5").use {
                      it.write("output message5".toByteArray())
                  }
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.run(testTaskName)

    assertThat(project.resolve("${testAdditionalOutputPath}/myTestStorageOutputFile1")).contains("output message1")
    assertThat(project.resolve("${testAdditionalOutputPath}/myTestStorageOutputFile2.txt")).contains("output message2")
    assertThat(project.resolve("${testAdditionalOutputPath}/subdir/myTestStorageOutputFile3")).contains("output message3")
    assertThat(project.resolve("${testAdditionalOutputPath}/subdir/nested/myTestStorageOutputFile4")).contains("output message4")
    assertThat(project.resolve("${testAdditionalOutputPath}/subdir/white space/myTestStorageOutputFile5")).contains("output message5")
  }

  @Test
  @Throws(Exception::class)
  fun additionalTestOutputWithoutTestStorageService() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("app")

    rule.build.androidApplication().reconfigure {
      files {
        add(
          "src/androidTest/java/com/example/helloworld/AdditionalTestOutputExampleTest.kt",
          // language=kotlin
          """
          package com.example.helloworld

          import androidx.test.ext.junit.runners.AndroidJUnit4
          import org.junit.Test
          import org.junit.runner.RunWith
          import java.io.File

          @RunWith(AndroidJUnit4::class)
          class AdditionalTestOutputExampleTest {
              @Test
              fun writeFileWithoutTestStorageService() {
                  val dir = File("/sdcard/Android/media/com.example.android.kotlin/additional_test_output").also {
                      it.mkdirs()
                  }
                  File(dir,"myTestFile1").apply {
                      createNewFile()
                      writeText("output message 1")
                  }
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.run(testTaskName)

    assertThat(project.resolve("${testAdditionalOutputPath}/myTestFile1")).contains("output message 1")
  }

  @Test
  @Throws(Exception::class)
  fun additionalTestOutputWithBenchmarkFiles() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("app")

    rule.build.androidApplication().reconfigure {
      files {
        add(
          "src/androidTest/java/com/example/helloworld/AdditionalTestOutputExampleTest.kt",
          // language=kotlin
          """
          package com.example.helloworld

          import android.os.Bundle
          import android.os.Environment
          import androidx.test.platform.app.InstrumentationRegistry
          import androidx.test.ext.junit.runners.AndroidJUnit4

          import org.junit.Test
          import org.junit.runner.RunWith

          import java.io.File

          @RunWith(AndroidJUnit4::class)
          class AdditionalTestOutputExampleTest {
              @Test
              fun createSampleFileAndReportIt() {
                  val instrumentation = InstrumentationRegistry.getInstrumentation()
                  // Tries to report a bundle with additional test output
                  @Suppress("DEPRECATION")
                  val outputFolder = instrumentation
                      .targetContext
                      .externalMediaDirs.firstOrNull {
                          Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                      }
                      ?: throw Exception("Cannot get external storage due to not mounted")

                  val sampleFile = File(outputFolder, "sampleFile_1")
                      .apply { writeText("This is a sample file.") }

                  // Note that the path used here should be relative to outputFolder, so just the filename.
                  val summary = "[sample file](file://" + sampleFile.name + ")"

                  val bundle = Bundle().apply {
                      putString("android.studio.display.benchmark", summary)
                      putString("android.studio.v2display.benchmark", summary)
                      putString("android.studio.v2display.benchmark.outputDirPath", outputFolder.absolutePath)
                      putString("additionalTestOutputFile_sampleFile", sampleFile.absolutePath)
                  }
                  InstrumentationRegistry
                      .getInstrumentation()
                      .sendStatus(2, bundle)
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.run(testTaskName)

    assertThat(project.resolve("${testAdditionalOutputPath}/sampleFile_1")).contains("This is a sample file.")
    assertThat(
        project.resolve(
          "${testAdditionalOutputPath}/" +
            "additionaltestoutput.benchmark.message_com.example.helloworld" +
            ".AdditionalTestOutputExampleTest.createSampleFileAndReportIt.txt"
        )
      )
      .contains("[sample file](file://sampleFile_1)")
  }

  @Test
  @Throws(Exception::class)
  fun additionalTestOutputWithBenchmarkV3Files() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("app")

    rule.build.androidApplication().reconfigure {
      files {
        add(
          "src/androidTest/java/com/example/helloworld/AdditionalTestOutputExampleTest.kt",
          // language=kotlin
          """
                  package com.example.helloworld

          import android.os.Bundle
          import android.os.Environment
          import androidx.test.platform.app.InstrumentationRegistry
          import androidx.test.ext.junit.runners.AndroidJUnit4

          import org.junit.Test
          import org.junit.runner.RunWith

          import java.io.File

          @RunWith(AndroidJUnit4::class)
          class AdditionalTestOutputExampleTest {
              @Test
              fun createSampleFileAndReportIt() {
                  val instrumentation = InstrumentationRegistry.getInstrumentation()
                  // Tries to report a bundle with additional test output
                  @Suppress("DEPRECATION")
                  val outputFolder = instrumentation
                      .targetContext
                      .externalMediaDirs.firstOrNull {
                          Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
                      }
                      ?: throw Exception("Cannot get external storage due to not mounted")

                  val sampleFile = File(outputFolder, "sampleFile_1")
                      .apply { writeText("This is a sample file.") }

                  // Note that the path used here should be relative to outputFolder, so just the filename.
                  val summary = "[sample file](file://" + sampleFile.name + ")"
                  val summaryV3 = "[sample file](uri://" + sampleFile.name + ")"

                  val bundle = Bundle().apply {
                      putString("android.studio.display.benchmark", summary)
                      putString("android.studio.v2display.benchmark", summary)
                      putString("android.studio.v2display.benchmark.outputDirPath", outputFolder.absolutePath)
                      putString("android.studio.v3display.benchmark", summaryV3)
                      putString("android.studio.v3display.benchmark.outputDirPath", outputFolder.absolutePath)
                      putString("additionalTestOutputFile_sampleFile", sampleFile.absolutePath)
                  }
                  InstrumentationRegistry
                      .getInstrumentation()
                      .sendStatus(2, bundle)
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.run(testTaskName)

    assertThat(project.resolve("${testAdditionalOutputPath}/sampleFile_1")).contains("This is a sample file.")
    assertThat(
        project.resolve(
          "${testAdditionalOutputPath}/" +
            "additionaltestoutput.benchmark.message_com.example.helloworld" +
            ".AdditionalTestOutputExampleTest.createSampleFileAndReportIt.txt"
        )
      )
      .contains("[sample file](uri://sampleFile_1)")
  }

  @Test
  @Throws(Exception::class)
  fun androidTestWithDynamicFeature() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("feature")

    rule.build.androidApplication().reconfigure { enableDynamicFeature("feature") }

    executor.run(testTaskName)

    assertThat(project.resolve(testResultXmlPath)).exists()
    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()

    val deviceInfo = getDeviceInfo(project.resolve(testResultPbPath).toFile())
    assertThat(deviceInfo).isNotNull()
    assertThat(deviceInfo?.name).isNotEmpty()

    // Run the task again after clean. This time the task configuration is
    // restored from the configuration cache. We expect no crashes.
    executor.run("clean")

    assertThat(project.resolve(testResultXmlPath)).doesNotExist()
    assertThat(project.resolve(testReportPath)).doesNotExist()
    assertThat(project.resolve(testResultPbPath)).doesNotExist()

    executor.run(testTaskName)

    assertThat(project.resolve(testResultXmlPath)).exists()
    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
  }

  @Test
  @Throws(Exception::class)
  fun androidTestWithOrchestratorWithDynamicFeature() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("feature")

    rule.build.androidApplication().reconfigure { enableDynamicFeature("feature") }
    rule.build.androidFeature().reconfigure { enableAndroidTestOrchestrator() }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
  }

  @Test
  @Throws(Exception::class)
  fun connectedAndroidTestWithLogcatWithDynamicFeature() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("feature")

    rule.build.androidApplication().reconfigure { enableDynamicFeature("feature") }

    executor.run(testTaskName)

    assertThat(project.resolve(testLogcatPath)).exists()
    val logcatText = project.resolve(testLogcatPath).readText()
    assertThat(logcatText).contains("TestRunner: started: useAppContext(com.example.android.kotlin.feature.ExampleInstrumentedTest)")
    assertThat(logcatText).contains("TestLogger: test logs")
    assertThat(logcatText).contains("TestRunner: finished: useAppContext(com.example.android.kotlin.feature.ExampleInstrumentedTest)")
  }

  @Test
  @Throws(Exception::class)
  fun connectedAndroidTestWithAdditionalTestOutputUsingTestStorageServiceWithDynamicFeature() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("feature")

    rule.build.androidApplication().reconfigure { enableDynamicFeature("feature") }
    rule.build.androidFeature().reconfigure {
      enableTestStorageService()
      files {
        add(
          "src/androidTest/java/com/example/helloworld/TestStorageServiceExampleTest.kt",
          // language=kotlin
          """
          package com.example.helloworld

          import androidx.test.ext.junit.runners.AndroidJUnit4
          import androidx.test.services.storage.TestStorage
          import org.junit.Test
          import org.junit.runner.RunWith

          @RunWith(AndroidJUnit4::class)
          class TestStorageServiceExampleTest {
              @Test
              fun writeFileUsingTestStorageService() {
                  TestStorage().openOutputFile("myTestStorageOutputFile1").use {
                      it.write("output message1".toByteArray())
                  }
                  TestStorage().openOutputFile("myTestStorageOutputFile2.txt").use {
                      it.write("output message2".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/myTestStorageOutputFile3").use {
                      it.write("output message3".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/nested/myTestStorageOutputFile4").use {
                      it.write("output message4".toByteArray())
                  }
                  TestStorage().openOutputFile("subdir/white space/myTestStorageOutputFile5").use {
                      it.write("output message5".toByteArray())
                  }
              }
          }
          """
            .trimIndent(),
        )
      }
    }

    executor.run(testTaskName)

    assertThat(project.resolve("${testAdditionalOutputPath}/myTestStorageOutputFile1")).contains("output message1")
    assertThat(project.resolve("${testAdditionalOutputPath}/myTestStorageOutputFile2.txt")).contains("output message2")
    assertThat(project.resolve("${testAdditionalOutputPath}/subdir/myTestStorageOutputFile3")).contains("output message3")
    assertThat(project.resolve("${testAdditionalOutputPath}/subdir/nested/myTestStorageOutputFile4")).contains("output message4")
    assertThat(project.resolve("${testAdditionalOutputPath}/subdir/white space/myTestStorageOutputFile5")).contains("output message5")
  }

  @Test
  @Throws(Exception::class)
  fun androidTestWithForceCompilation() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("app")

    rule.build.androidApplication().reconfigure { enableForceCompilation() }

    val result = executor.withEnableInfoLogging(true).run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
    result.assertOutputContains("Running force AOT compilation for com.example.android.kotlin")
    result.assertOutputContains("Running force AOT compilation for com.example.android.kotlin.test")
  }

  /** TODO: Enable the test once b/261739458 is fixed. */
  @Ignore("b/261739458")
  @Test
  @Throws(Exception::class)
  fun androidTestWithOrchestratorAndCodeCoverageWithDynamicFeature() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("feature")

    rule.build.androidApplication().reconfigure {
      enableAndroidTestOrchestrator()
      enableCodeCoverage()
      enableDynamicFeature("feature")
    }
    rule.build.androidFeature().reconfigure { enableAndroidTestOrchestrator() }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
    assertThat(project.resolve(testCoverageXmlPath))
      .contains("""<method name="stubfeatureFuncForTestingCodeCoverage" desc="()V" line="9">""")
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<counter type="INSTRUCTION" missed="3" covered="5"/>""")
  }

  /** TODO: Enable the test once b/261739458 is fixed. */
  @Ignore("b/261739458")
  @Test
  @Throws(Exception::class)
  fun androidTestWithCodeCoverageWithDynamicFeature() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("feature")

    rule.build.androidApplication().reconfigure {
      enableDynamicFeature("feature")
      enableCodeCoverage()
    }

    executor.run(testTaskName)

    assertThat(project.resolve(testReportPath)).exists()
    assertThat(project.resolve(testResultPbPath)).exists()
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<method name="stubFuncForTestingCodeCoverage" desc="()V" line="9">""")
    assertThat(project.resolve(testCoverageXmlPath)).contains("""<counter type="INSTRUCTION" missed="3" covered="5"/>""")
  }

  @Test
  fun runAndroidTestWithNoTestClasses() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("emptyAppProject")

    val result =
      executor
        .withEnableInfoLogging(true) // "No tests found" message is info level.
        .run(testTaskName)

    result.assertOutputContains("No tests found, nothing to do.")
  }

  /** Regression test for b/466374462. */
  @Test
  fun connectedAndroidTestDoesNotOutputNoClassDefFoundError() {
    // TODO(b/476442048): Implement built-in test platform.
    Assume.assumeFalse(runWithBuiltInPlatform)

    selectModule("test")

    // NoClassDefFoundError typically happen when you return too early from work action
    // and some callback happens after Gradle unloads classes in worker daemon.
    // We repeat 10 times here to give Gradle a chance to unload some worker daemons
    // between multiple builds.
    repeat(10) {
      executor.run(testTaskName).apply {
        assertOutputDoesNotContain("java.lang.NoClassDefFoundError")
        assertErrorDoesNotContain("java.lang.NoClassDefFoundError")
      }
    }
  }
}
