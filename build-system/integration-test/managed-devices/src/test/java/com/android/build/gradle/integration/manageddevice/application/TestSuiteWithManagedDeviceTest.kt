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

package com.android.build.gradle.integration.manageddevice.application

import com.android.Version
import com.android.build.api.dsl.AgpTestSuite
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.api.testsuites.TestEngineInputProperty
import com.android.build.api.testsuites.TestSuiteExecutionClient
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomAndroidSdk
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomSdkDir
import com.android.build.gradle.integration.manageddevice.utils.addManagedDevice
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import java.io.FileReader
import java.util.Properties
import java.util.concurrent.TimeUnit
import org.gradle.api.Project
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.platform.engine.EngineDiscoveryRequest
import org.junit.platform.engine.ExecutionRequest
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.EngineDescriptor
import org.junit.platform.engine.support.hierarchical.EngineExecutionContext
import org.junit.platform.engine.support.hierarchical.HierarchicalTestEngine
import org.junit.platform.engine.support.hierarchical.Node

class TestSuiteWithManagedDeviceTest {

  @get:Rule val customAndroidSdkRule = CustomAndroidSdkRule()

  class MyTestEngine : HierarchicalTestEngine<MyTestEngineContext>() {
    override fun getId(): String = "MyTestEngine"

    override fun discover(discoveryRequest: EngineDiscoveryRequest, uniqueId: UniqueId): TestDescriptor =
      MyTestEngineDescriptor(uniqueId).apply { addChild(MyTestDescriptor(uniqueId, "myTestCase")) }

    override fun createExecutionContext(request: ExecutionRequest) = MyTestEngineContext()
  }

  class MyTestEngineDescriptor(uniqueId: UniqueId) : EngineDescriptor(uniqueId, "MyTestEngine")

  class MyTestEngineContext : EngineExecutionContext

  class MyTestDescriptor(parentId: UniqueId, testName: String) :
    AbstractTestDescriptor(parentId.append("my-test-segment", testName), testName), Node<MyTestEngineContext> {
    override fun getType() = TestDescriptor.Type.TEST

    override fun execute(context: MyTestEngineContext, dynamicTestExecutor: Node.DynamicTestExecutor): MyTestEngineContext {
      val client = TestSuiteExecutionClient.default()
      val serialIds = client.getInputParameter(TestEngineInputProperty.SERIAL_IDS)
      assertEquals("emulator-5554", serialIds)

      val inputProperties = Properties().also { it.load(FileReader(System.getenv("com.android.junit.engine.input.parameters"))) }
      val adbPath = inputProperties.getProperty("com.android.agp.test.ADB_EXECUTABLE")

      val process = ProcessBuilder(adbPath, "devices").start()
      process.waitFor(1, TimeUnit.MINUTES)
      val stdout = process.inputStream.bufferedReader().use { it.readText() }
      assertThat(stdout).contains("emulator-5554")

      return context
    }
  }

  @get:Rule
  val rule =
    GradleRule.configure()
      .withCustomSdkDir(customAndroidSdkRule)
      .withMavenRepository {
        jar("org.junit.platform:junit-platform-engine:1.12.0")
        jar("org.junit.platform:junit-platform-launcher:1.12.0")
        jar("com.test:my-test-engine:1.0.0")
          .addClasses(
            MyTestDescriptor::class.java,
            MyTestEngine::class.java,
            MyTestEngineContext::class.java,
            MyTestEngineDescriptor::class.java,
          )
          .addTextFile("META-INF/services/org.junit.platform.engine.TestEngine", MyTestEngine::class.java.name)
      }
      .from {
        gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
        androidApplication {
          addManagedDevice("myGMD")
          android {
            testOptions.suites.create("myTestSuite", AgpTestSuite::class.java) {
              it.useJunitEngine.apply {
                inputs.add(AgpTestSuiteInputParameters.TESTED_APKS)
                inputs.add(AgpTestSuiteInputParameters.ADB_EXECUTABLE)
                includeEngines.add("MyTestEngine")
                enginesDependencies.add("com.android.tools.build:gradle-api:${Version.ANDROID_GRADLE_PLUGIN_VERSION}")
                enginesDependencies.add("org.junit.platform:junit-platform-engine:+")
                enginesDependencies.add("org.junit.platform:junit-platform-launcher:+")
                enginesDependencies.add("org.jetbrains.kotlin:kotlin-stdlib:+")
                enginesDependencies.add("com.test:my-test-engine:+")
                enginesDependencies.add("com.google.truth:truth:+")
              }
              it.targetVariants.add("debug")
              it.targets.create("GMD") { it.targetDevices.add("myGMD") }
            }
          }
          pluginCallbacks += PrintTestLogsCallback::class.java
        }
      }

  class PrintTestLogsCallback : GenericCallback {
    override fun handleProject(project: Project) {
      project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java) {
        it.testLogging {
          it.events("passed", "skipped", "failed")
          it.showExceptions = true
          it.exceptionFormat = TestExceptionFormat.FULL
          it.showCauses = true
          it.showStackTraces = true
        }
      }
    }
  }

  val executor: GradleTaskExecutor
    get() = rule.build.executor.withCustomAndroidSdk(customAndroidSdkRule).withEnableInfoLogging(false)

  @Test
  fun testSuiteWithManagedDevice() {
    executor.run(":app:testMyTestSuiteGMDMyGMDDebugTestSuite")
  }
}
