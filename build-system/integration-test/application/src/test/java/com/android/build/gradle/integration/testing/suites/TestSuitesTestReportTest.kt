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
        jar("org.junit.platform:junit-platform-engine:1.10.1")
        jar("org.junit.platform:junit-platform-launcher:1.10.1")
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
            testOptions.suites.create("first", AgpTestSuite::class.java) {
              it.useJunitEngine.apply {
                inputs.add(AgpTestSuiteInputParameters.MERGED_MANIFEST)
                includeEngines.add("[engine:custom-junit-engine-for-tests]")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                enginesDependencies.add("com.test:custom-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
              }
              it.assets {}
              it.targetVariants.add("debug")
              it.targets.create("t1") {}
              it.targets.create("t2") {}
            }
          }
          dependencies { implementation("com.google.truth:truth:0.44") }
        }
      }

  @Test
  fun testSingleModuleReporting() {
    val build = rule.build
    build.executor.run(":app:createTestReport")
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
  }

  @Test
  fun testReportingDisabled() {
    val build = rule.build { gradleProperties { add(BooleanOption.REPORT_AGGREGATION_SUPPORT, false) } }
    val result = build.executor.run(":app:createTestReport")
    assertThat(result.didWorkTasks).doesNotContain(":app:testResultsCollectionDebug")
    result.assertOutputContains("Aggregated Test reporting feature is disabled, TestReportTask's execution is skipped.")
  }

  @Test
  fun testAcrossModuleReporting() {
    val build =
      rule.build {
        androidLibrary {
          android {
            testOptions.suites.create("second", AgpTestSuite::class.java) {
              it.useJunitEngine.apply {
                inputs.add(AgpTestSuiteInputParameters.MERGED_MANIFEST)
                includeEngines.add("[engine:custom-junit-engine-for-tests]")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                enginesDependencies.add("com.test:custom-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
              }
              it.assets {}
              it.targetVariants.add("debug")
              it.targets.create("t1") {}
              it.targets.create("t2") {}
            }
          }
        }
        androidApplication { dependencies { implementation(project(DEFAULT_LIB_PATH)) } }
      }

    build.executor.run(":app:createAggregatedTestReport")

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
  }

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
