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
import com.android.build.api.testsuites.TestEngineInputProperty
import com.android.build.api.testsuites.TestSuiteExecutionClient
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.options.BooleanOption
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

class TestSuiteWithDependenciesTest {
  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        jar("com.google.truth:truth:0.44")
        jar("org.junit.platform:junit-platform-engine:1.10.1")
        jar("org.junit.platform:junit-platform-launcher:1.10.1")
        jar("org.jetbrains.kotlin:kotlin-stdlib:2.1.20")
        jar("com.test:toy-junit-engine:1.0")
          .addClasses(
            ToyJunitEngineForTestingDependencies::class.java,
            ToyTestDescriptorForTestingDependencies::class.java,
            TestEngineLogger::class.java,
          )
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", ToyJunitEngineForTestingDependencies::class.java.name)
      }
      .from {
        gradleProperties { apply { add(BooleanOption.TEST_SUITE_SUPPORT, true) } }
        androidApplication(":app") {
          android {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

            namespace = "com.example.test"
            testOptions.suites.create("first", AgpTestSuite::class.java) {
              it.useJunitEngine.apply {
                inputs.add(AgpTestSuiteInputParameters.TESTED_APKS)
                includeEngines.add("toy-junit-engine-for-tests")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher")
                enginesDependencies.add("com.test:toy-junit-engine:1.0")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:1.12.0")
              }
              it.assets {}
              it.targetVariants.add("debug")
              it.targets.apply { create("t1") {} }
            }
          }
          dependencies { implementation(project(":lib")) }
          files {
            add(
              "src/main/kotlin/com/example/test/SomeApp.kt",
              """
              package com.example.test

              import com.example.lib.SomeApiClass

              class SomeApp {
                  override fun toString(): String {
                      val api = SomeApiClass()
                      println(api.publicApi())
                      return api.publicApi()
                  }
              }
              """
                .trimIndent(),
            )
          }
        }

        androidLibrary(":lib") {
          dependencies { implementation("com.google.truth:truth:0.44") }
          android {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

            namespace = "com.example.lib"
            kotlin {}
          }
          files {
            add(
              "src/main/kotlin/com/example/lib/SomeApiClass.kt",
              """
              package com.example.lib

              class SomeApiClass {
                  fun publicApi(): String = "Success !"
                  override fun toString() = "SomeApiClass coming from lib"
              }
              """
                .trimIndent(),
            )
          }
        }
      }

  @Test
  fun testConfigurationBlockExecutes() {
    rule.build.executor.run("app:testFirstT1DebugTestSuite")
  }
}

class ToyJunitEngineForTestingDependencies : TestEngine {

  // load my input properties as a json object, I am only using a handful of those so far.
  private val inputParams = TestSuiteExecutionClient.default()

  private val logger = TestEngineLogger(File(inputParams.getInputParameter(TestEngineInputProperty.LOGGING_FILE)))

  override fun getId(): String = "toy-junit-engine-for-tests"

  override fun discover(p0: EngineDiscoveryRequest?, p1: UniqueId?): TestDescriptor =
    ToyTestDescriptorForTestingDependencies(UniqueId.parse("[method: some-test]"))

  override fun execute(p0: ExecutionRequest?) {
    p0?.let { executionRequest ->
      logger.info("Executing toy engine ! ${executionRequest.rootTestDescriptor}")
      val listener: EngineExecutionListener = executionRequest.engineExecutionListener
      val engineDescriptor = executionRequest.rootTestDescriptor
      listener.executionStarted(engineDescriptor)

      // Simulated test execution
      try {
        val appClass = ToyTestDescriptorForTestingDependencies::class.java.classLoader.loadClass("com.example.lib.SomeApiClass")
        logger.info(appClass.getDeclaredConstructor().newInstance().toString())

        logger.info("Test Passed !")
        listener.executionFinished(engineDescriptor, TestExecutionResult.successful())
      } catch (t: Throwable) {
        listener.executionFinished(engineDescriptor, TestExecutionResult.failed(t))
      }
      logger.info("Finished $engineDescriptor test.")
    }
  }
}

class ToyTestDescriptorForTestingDependencies(uniqueId: UniqueId) : AbstractTestDescriptor(uniqueId, "toy descriptor") {
  override fun getType(): TestDescriptor.Type = TestDescriptor.Type.TEST
}
