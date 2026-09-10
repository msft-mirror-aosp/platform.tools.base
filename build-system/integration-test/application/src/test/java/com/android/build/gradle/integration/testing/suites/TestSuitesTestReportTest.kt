/*
 * Copyright (C) 2025 The Android Open Source Project
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
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.options.BooleanOption
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.EngineExecutionListener
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.TestEngine
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.descriptor.MethodSource

class TestSuitesTestReportTest {

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("org.junit.platform:junit-platform-engine:1.13.3")
        jar("org.junit.platform:junit-platform-launcher:1.13.3")
        jar("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
        jar("com.test:custom-junit-engine:1.0")
          .addClasses(
            CustomJunitEngineForTesting::class.java,
            CustomTestDescriptor::class.java,
            CustomEngineDescriptor::class.java,
            CustomClassDescriptor::class.java,
          )
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", CustomJunitEngineForTesting::class.java.name)
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication {
          android {
            testOptions.customSuites.create("first") {
              it.useJunitEngine.apply {
                inputs.add(AgpTestSuiteInputParameters.MERGED_MANIFEST)
                includeEngines.add("[engine:custom-junit-engine-for-tests]")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                enginesDependencies.add("com.test:custom-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
              }
              it.assets {}
              it.targetVariants.add("debug")
              it.targets.create("t1") {}
              it.targets.create("t2") {}
            }
          }
          files { add("src/first/test.txt", "dummy content") }
          dependencies { implementation("com.google.truth:truth:0.44") }
        }
        gradleProperties { add(BooleanOption.REPORT_AGGREGATION_SUPPORT, true) }
      }

  @Test
  fun testSingleModuleReporting() {
    val build = rule.build
    build.executor.run(":app:testAllSuites")
    val xmlFiles =
      build
        .androidApplication()
        .intermediatesDir
        .resolve("project_level_test_results/global/testResultsCollectionDebug")
        .toFile()
        .listFiles()
        .filter { it.isFile && it.extension == "xml" }
        .toList()
    assertThat(xmlFiles.size).isEqualTo(2)

    val coverageReportDir = build.androidApplication().buildDir.resolve("reports/coverage/first")
    assertThat(coverageReportDir.toFile().exists()).isFalse()
  }

  @Test
  fun testReportingDisabled() {
    val build = rule.build { gradleProperties { add(BooleanOption.REPORT_AGGREGATION_SUPPORT, false) } }
    val result = build.executor.expectFailure().run(":app:testAllSuites")
    result.assertFailureMessage().contains("task 'testAllSuites' not found in project ':app'")
  }

  @Test
  fun testAcrossModuleReporting() {
    val build = rule.build {
      androidLibrary {
        android {
          testOptions.customSuites.create("second") {
            it.useJunitEngine.apply {
              inputs.add(AgpTestSuiteInputParameters.MERGED_MANIFEST)
              includeEngines.add("[engine:custom-junit-engine-for-tests]")
              enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
              enginesDependencies.add("org.junit.platform:junit-platform-launcher")
              enginesDependencies.add("com.test:custom-junit-engine:1.0")
              enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
            }
            it.assets {}
            it.targetVariants.add("debug")
            it.targets.create("t1") {}
            it.targets.create("t2") {}
          }
        }
        files { add("src/second/test.txt", "dummy content") }
      }
      androidApplication { dependencies { implementation(project(DEFAULT_LIB_PATH)) } }
    }

    build.executor.run(":app:testAllSuitesWithDependencies")

    val xmlFiles =
      build
        .androidApplication()
        .intermediatesDir
        .resolve("all_project_test_results/global/aggregatedTestResultsCollectionDebug")
        .toFile()
        .listFiles()
        .filter { it.isFile && it.extension == "xml" }
        .toList()
    assertThat(xmlFiles.size).isEqualTo(4)

    val appCoverageReportDir = build.androidApplication().buildDir.resolve("reports/coverage/first")
    assertThat(appCoverageReportDir.toFile().exists()).isFalse()

    val libCoverageReportDir = build.androidLibrary().buildDir.resolve("reports/coverage/second")
    assertThat(libCoverageReportDir.toFile().exists()).isFalse()
  }

  @Test
  fun testUpdateTasksNotIncludedInTestReport() {
    val build = rule.build {
      androidApplication {
        android {
          testOptions.customSuites.create("withUpdate") {
            it.useJunitEngine.apply {
              inputs.add(AgpTestSuiteInputParameters.MERGED_MANIFEST)
              includeEngines.add("[engine:custom-junit-engine-for-tests]")
              enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
              enginesDependencies.add("org.junit.platform:junit-platform-launcher")
              enginesDependencies.add("com.test:custom-junit-engine:1.0")
              enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
            }
            it.requiresUpdateTask = true
            it.assets {}
            it.targetVariants.add("debug")
            it.targets.create("t1") {}
          }
        }
        files { add("src/withUpdate/test.txt", "dummy content") }
      }
    }

    // Running testAllSuites should only execute test tasks, excluding update tasks.
    build.executor.run(":app:testAllSuites")

    val testResultsXmlFiles =
      build
        .androidApplication()
        .intermediatesDir
        .resolve("project_level_test_results/global/testResultsCollectionDebug")
        .toFile()
        .listFiles()
        ?.filter { it.isFile && it.extension == "xml" } ?: emptyList()
    // 2 from 'first' (t1, t2) + 1 from 'withUpdate' (t1) = 3 test results
    assertThat(testResultsXmlFiles.size).isEqualTo(3)

    // Update tasks should not be executed by testAllSuites, so TEST_SUITE_UPDATE_RESULTS directory does not exist yet.
    val updateResultsDir =
      build.androidApplication().intermediatesDir.resolve("test_suite_update_results/debug/updateWithUpdateT1DebugTestSuite").toFile()
    assertThat(updateResultsDir.exists()).isFalse()

    // Now explicitly execute the update task and verify its results are published to TEST_SUITE_UPDATE_RESULTS
    build.executor.run(":app:updateWithUpdateT1DebugTestSuite")
    assertThat(updateResultsDir.exists()).isTrue()
    val updateXmlFiles = updateResultsDir.listFiles()?.filter { it.isFile && it.extension == "xml" } ?: emptyList()
    assertThat(updateXmlFiles.size).isEqualTo(1)

    val firstCoverageReportDir = build.androidApplication().buildDir.resolve("reports/coverage/first")
    assertThat(firstCoverageReportDir.toFile().exists()).isFalse()

    val withUpdateCoverageReportDir = build.androidApplication().buildDir.resolve("reports/coverage/withUpdate")
    assertThat(withUpdateCoverageReportDir.toFile().exists()).isFalse()
  }

  @Test
  fun testTestSuiteCodeCoverageEnabled() {
    val build = rule.build {
      androidApplication {
        android {
          testOptions.suites.create("coverageSuite", AgpTestSuite::class.java) {
            it.codeCoverage = true
            it.useJunitEngine.apply {
              inputs.add(AgpTestSuiteInputParameters.MERGED_MANIFEST)
              includeEngines.add("[engine:custom-junit-engine-for-tests]")
              enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
              enginesDependencies.add("org.junit.platform:junit-platform-launcher")
              enginesDependencies.add("com.test:custom-junit-engine:1.0")
              enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
            }
            it.hostJar {}
            it.targetVariants.add("debug")
            it.targets.create("t1") {}
          }
        }
        files {
          add("src/main/java/com/example/dummy/Dummy.java", "package com.example.dummy; public class Dummy {}")
          add("src/coverageSuite/java/DummyTest.java", "package com.example.dummy; public class DummyTest {}")
        }
      }
    }

    build.executor.run(":app:testCoverageSuiteT1DebugTestSuite")

    val coverageReportDir = build.androidApplication().buildDir.resolve("reports/coverage/coverageSuite")
    val htmlReportIndex = coverageReportDir.resolve("index.html").toFile()
    val xmlReport = coverageReportDir.resolve("report.xml").toFile()

    assertThat(htmlReportIndex.exists()).isTrue()
    assertThat(xmlReport.exists()).isTrue()

    val reportDataFile = coverageReportDir.resolve("data/report-data.js").toFile()
    assertThat(reportDataFile.exists()).isTrue()

    val report = parseReportJs<TestCoverageReport>(reportDataFile)
    assertThat(report.name).isNotEmpty()
    assertThat(report.modules).hasSize(1)

    val appModule = report.modules.find { it.name == ":app" }
    assertThat(appModule).isNotNull()

    val appPackage = appModule!!.packages.find { it.name == "com.example.dummy" }
    assertThat(appPackage).isNotNull()
    val dummyClass = appPackage!!.classes.find { it.name == "Dummy" }
    assertThat(dummyClass).isNotNull()

    assertThat(dummyClass!!.variantSourceFilePaths).isNotEmpty()
    val variantSourcePath = dummyClass.variantSourceFilePaths.first()
    assertThat(variantSourcePath.variantName).isEqualTo("debug")
    assertThat(variantSourcePath.path).isEqualTo("app/src/main/java/com.example.dummy/Dummy.java")
  }

  @Test
  fun testTestSuiteCodeCoverageDisabled() {
    val build = rule.build {
      androidApplication {
        android {
          testOptions.suites.create("noCoverageSuite", AgpTestSuite::class.java) {
            it.codeCoverage = false
            it.useJunitEngine.apply {
              inputs.add(AgpTestSuiteInputParameters.MERGED_MANIFEST)
              includeEngines.add("[engine:custom-junit-engine-for-tests]")
              enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
              enginesDependencies.add("org.junit.platform:junit-platform-launcher")
              enginesDependencies.add("com.test:custom-junit-engine:1.0")
              enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
            }
            it.hostJar {}
            it.targetVariants.add("debug")
            it.targets.create("t1") {}
          }
        }
        files { add("src/noCoverageSuite/java/Dummy.java", "package com.example.dummy; public class Dummy {}") }
      }
    }

    build.executor.run(":app:testNoCoverageSuiteT1DebugTestSuite")

    val coverageReportDir = build.androidApplication().buildDir.resolve("reports/coverage/noCoverageSuite")
    assertThat(coverageReportDir.toFile().exists()).isFalse()
  }

  @Test
  fun testUpdateSuiteCodeCoverage() {
    val build = rule.build {
      androidApplication {
        android {
          testOptions.suites.create("updateCoverageSuite", AgpTestSuite::class.java) {
            it.codeCoverage = true
            it.requiresUpdateTask = true
            it.useJunitEngine.apply {
              inputs.add(AgpTestSuiteInputParameters.MERGED_MANIFEST)
              includeEngines.add("[engine:custom-junit-engine-for-tests]")
              enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
              enginesDependencies.add("org.junit.platform:junit-platform-launcher")
              enginesDependencies.add("com.test:custom-junit-engine:1.0")
              enginesDependencies.add("org.junit.platform:junit-platform-engine:1.13.3")
            }
            it.hostJar {}
            it.targetVariants.add("debug")
            it.targets.create("t1") {}
          }
        }
        files {
          add("src/main/java/com/example/dummy/Dummy.java", "package com.example.dummy; public class Dummy {}")
          add("src/updateCoverageSuite/java/DummyTest.java", "package com.example.dummy; public class DummyTest {}")
        }
      }
    }

    build.executor.run(":app:testUpdateCoverageSuiteT1DebugTestSuite")

    val coverageReportDir = build.androidApplication().buildDir.resolve("reports/coverage/updateCoverageSuite")
    val htmlReportIndex = coverageReportDir.resolve("index.html").toFile()
    val xmlReport = coverageReportDir.resolve("report.xml").toFile()

    assertThat(htmlReportIndex.exists()).isTrue()
    assertThat(xmlReport.exists()).isTrue()

    coverageReportDir.toFile().deleteRecursively()
    assertThat(coverageReportDir.toFile().exists()).isFalse()

    build.executor.run(":app:updateUpdateCoverageSuiteT1DebugTestSuite")

    assertThat(coverageReportDir.toFile().exists()).isFalse()
  }

  private val gson = Gson()

  private inline fun <reified T> parseReportJs(file: File): T {
    val content = file.readText().removePrefix("const fullReport = ").removeSuffix(";")
    return gson.fromJson(content, T::class.java)
  }

  // --- Data classes for parsing JSON from report files ---

  data class TestCoverageReport(
    val name: String,
    val modules: List<TestModuleReport>,
    @SerializedName("numberOfTestsSuites") val numberOfTestsSuites: Int,
  )

  data class TestModuleReport(
    val name: String,
    val testSuiteCoverages: List<TestTestSuiteReportCoverage>,
    val packages: List<TestPackageReport>,
  )

  data class TestTestSuiteReportCoverage(val name: String, val variantCoverages: List<TestVariantCoverage>)

  data class TestPackageReport(val name: String, val classes: List<TestClassReport>)

  data class TestClassReport(val name: String, val sourceFileName: String, val variantSourceFilePaths: List<TestVariantSourceFilePath>)

  data class TestVariantSourceFilePath(val variantName: String, val path: String)

  data class TestVariantCoverage(val name: String, val instruction: TestCoverageInfo, val branch: TestCoverageInfo)

  data class TestCoverageInfo(val percent: Int, val covered: Int, val total: Int)

  class CustomEngineDescriptor(uniqueId: UniqueId) : AbstractTestDescriptor(uniqueId, "Custom Engine Root") {
    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER
  }

  class CustomClassDescriptor(uniqueId: UniqueId, className: String) :
    AbstractTestDescriptor(uniqueId, className, ClassSource.from(className)) {
    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.CONTAINER
  }

  class CustomTestDescriptor(uniqueId: UniqueId, testDescriptor: String, private val className: String) :
    AbstractTestDescriptor(uniqueId, testDescriptor, MethodSource.from(className, testDescriptor)) {
    override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST
  }

  class CustomJunitEngineForTesting : TestEngine {

    override fun getId(): String {
      return "[engine:custom-junit-engine-for-tests]"
    }

    override fun discover(discoveryRequest: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor {
      val engineDescriptor = CustomEngineDescriptor(uniqueId)

      val classId = uniqueId.append("class", "some-class")
      val classDescriptor = CustomClassDescriptor(classId, "testClassName")
      engineDescriptor.addChild(classDescriptor)

      val testId = classId.append("test", "some-test")
      val testDescriptor = CustomTestDescriptor(testId, "testFunctionName", "testClassName")
      classDescriptor.addChild(testDescriptor)

      return engineDescriptor
    }

    override fun execute(request: ExecutionRequest) {
      val listener: EngineExecutionListener = request.engineExecutionListener
      fun executeNode(descriptor: TestDescriptor) {
        listener.executionStarted(descriptor)
        if (descriptor is CustomTestDescriptor) {
          val testResult =
            try {
              val testSucceeded = System.getenv("CUSTOM_ENGINE_SUCCEED")?.toBoolean() ?: true
              if (testSucceeded) {
                TestExecutionResult.successful()
              } else {
                TestExecutionResult.failed(Exception("Test failed"))
              }
            } catch (t: Throwable) {
              TestExecutionResult.failed(t)
            }
          listener.executionFinished(descriptor, testResult)
        } else {
          for (child in descriptor.children) {
            executeNode(child)
          }
          listener.executionFinished(descriptor, TestExecutionResult.successful())
        }
      }

      executeNode(request.rootTestDescriptor)
    }
  }
}
