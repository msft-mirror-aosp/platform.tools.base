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

package com.android.build.gradle.integration.testing.screenshot

import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.LoggingLevel
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.integration.common.truth.forEachLine
import com.android.build.gradle.internal.TaskManager
import com.android.testutils.TestUtils
import com.android.utils.usLocaleCapitalize
import com.google.common.truth.Truth.assertThat
import com.sun.management.HotSpotDiagnosticMXBean
import java.io.File
import java.lang.management.ManagementFactory
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import org.gradle.api.Project
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestOutputEvent
import org.gradle.api.tasks.testing.TestOutputListener
import org.gradle.api.tasks.testing.TestResult
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.TestIdentifier

class ConfigureMaxParallelForksCallback : GenericCallback {
  override fun handleProject(project: Project) {
    project.afterEvaluate {
      project.tasks.withType(com.android.compose.screenshot.tasks.PreviewScreenshotValidationTask::class.java) {
        println("Forcibly setting maxParallelForks to 4 for task ${it.path}")
        it.maxParallelForks = 4
      }
    }
  }
}

fun AndroidProjectDefinition<out CommonExtension>.setupProject(addEmptyJarToClassPath: Boolean = true) {
  setupProjectNoScreenshotTestSource()

  if (addEmptyJarToClassPath) {
    val customJarName = UUID.randomUUID().toString() + ".jar"
    buildscript { classpath(localJar(customJarName) { addEmptyClasses("RandomClass_${UUID.randomUUID()}") }) }
  }

  kotlin { jvmToolchain(17) }

  dependencies { screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:+") }

  files {
    add(
      "src/screenshotTest/java/com/AnotherPreviewParameterProvider.kt",
      """
      package pkg.name

      import androidx.compose.ui.tooling.preview.PreviewParameterProvider

      class AnotherPreviewParameterProvider : PreviewParameterProvider<String> {
          override val values = sequenceOf(
              "text 1", "text 2"
          )
      }
      """
        .trimIndent(),
    )
    add(
      "src/screenshotTest/java/com/ExampleTest.kt",
      """
      package pkg.name

      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.tooling.preview.PreviewParameter
      import androidx.compose.runtime.Composable
      import com.android.tools.screenshot.PreviewTest

      class ExampleTest {
          @PreviewTest
          @Preview(name = "simpleComposable", showBackground = true)
          @Composable
          fun simpleComposableTest() {
              SimpleComposable()
          }

          @PreviewTest
          @Preview(name = "simpleComposable", widthDp = 800, heightDp = 800)
          @Composable
          fun simpleComposableTest2() {
              SimpleComposable()
          }

          @PreviewTest
          @Preview(name = "with_Background", showBackground = true)
          @Preview(name = "withoutBackground", showBackground = false)
          @Composable
          fun multiPreviewTest() {
              SimpleComposable()
          }

          @PreviewTest
          @Preview(name = "simplePreviewParameterProvider")
          @Composable
          fun parameterProviderTest(
              @PreviewParameter(SimplePreviewParameterProvider::class) data: String
          ) {
             SimpleComposable(data)
          }

          @PreviewTest
          @Preview(name = "invalid/File/Name")
          @Composable
          fun previewNameCannotBeUsedAsFileNameTest() {
              SimpleComposable()
          }
      }
      """
        .trimIndent(),
    )
    add(
      "src/screenshotTest/java/com/TopLevelPreviewTest.kt",
      """
      package pkg.name

      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.tooling.preview.PreviewParameter
      import androidx.compose.runtime.Composable
      import com.android.tools.screenshot.PreviewTest

      @PreviewTest
      @Preview(showBackground = true)
      @Composable
      fun simpleComposableTest_3() {
          SimpleComposable()
      }
      """
        .trimIndent(),
    )
  }
}

fun AndroidProjectDefinition<out CommonExtension>.setupProjectNoScreenshotTestSource() {
  applyPlugin(
    PluginType.Custom(
      id = com.android.build.gradle.internal.utils.COMPOSE_COMPILER_PLUGIN_ID,
      version = TestUtils.KOTLIN_VERSION_FOR_TESTS,
      artifact = "org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin",
      hasMarker = false,
    )
  )
  applyPlugin(
    PluginType.Custom(
      id = "com.android.compose.screenshot",
      version = "+",
      artifact = "com.android.compose.screenshot:screenshot-test-gradle-plugin",
      hasMarker = false,
    )
  )

  android {
    defaultConfig.apply {
      minSdk = 24
      testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures.apply { compose = true }
    composeOptions.kotlinCompilerExtensionVersion = TestUtils.COMPOSE_COMPILER_FOR_TESTS
    experimentalProperties["android.experimental.enableScreenshotTest"] = true
  }
  dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.compose.ui:ui-tooling:${TaskManager.COMPOSE_UI_VERSION}")
    implementation("androidx.compose.ui:ui-tooling-preview:${TaskManager.COMPOSE_UI_VERSION}")
    implementation("androidx.compose.material:material:${TaskManager.COMPOSE_UI_VERSION}")
  }
  kotlin { jvmToolchain(17) }
  pluginCallbacks += ScreenshotCallback::class.java

  files {
    add(
      "src/main/java/com/Example.kt",
      """
      package pkg.name

      import androidx.compose.material.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun SimpleComposable(text: String = "Hello World") {
          Text(text)
      }
      """
        .trimIndent(),
    )
    add(
      "src/main/java/com/ParameterProviders.kt",
      """
      package pkg.name

      import androidx.compose.ui.tooling.preview.PreviewParameterProvider

      class SimplePreviewParameterProvider : PreviewParameterProvider<String> {
          override val values = sequenceOf(
              "Primary text", "Secondary text"
          )
      }
      """
        .trimIndent(),
    )
  }
}

class ScreenshotCallback : GenericCallback {
  override fun handleProject(project: Project) {
    println(
      "Class loader for AGP API = " + com.android.build.api.variant.AndroidComponentsExtension::class.java.getClassLoader().hashCode()
    )

    project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java) {
      it.addTestListener(
        object : TestListener {
          override fun beforeSuite(suite: TestDescriptor) {
            println("Starting test suite: ${suite.name}")
          }

          override fun afterSuite(suite: TestDescriptor, result: TestResult) {
            println("Finished test suite: ${suite.name} with result: ${result.resultType}")
            result.exception?.printStackTrace()
          }

          override fun beforeTest(testDescriptor: TestDescriptor) {
            println("Starting test: ${testDescriptor.name}")
          }

          override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
            println("Finished test: ${testDescriptor.name} with result: ${result.resultType}")
            result.exception?.printStackTrace()
          }
        }
      )
    }
  }
}

fun GradleBuild.sstExecutor(
  cc: BaseGradleExecutor.ConfigurationCaching = BaseGradleExecutor.ConfigurationCaching.PROJECT_ISOLATION
): GradleTaskExecutor = executor.withConfigurationCaching(cc).withLoggingLevel(LoggingLevel.LIFECYCLE)

fun GradleBuild.updateReferenceImage(
  buildType: String = "debug",
  flavor: String = "",
  projectName: String = "app",
  cc: BaseGradleExecutor.ConfigurationCaching = BaseGradleExecutor.ConfigurationCaching.PROJECT_ISOLATION,
): GradleBuildResult {
  val variantName = if (flavor.isEmpty()) buildType else flavor + buildType.usLocaleCapitalize()
  return sstExecutor(cc).run(":$projectName:update${variantName.usLocaleCapitalize()}ScreenshotTest")
}

fun GradleBuild.updateReferenceImageForAllProjects(
  variantName: String = "debug",
  cc: BaseGradleExecutor.ConfigurationCaching = BaseGradleExecutor.ConfigurationCaching.PROJECT_ISOLATION,
): GradleBuildResult {
  return sstExecutor(cc).run("update${variantName.usLocaleCapitalize()}ScreenshotTest")
}

fun verifyClassLoaderSetup(result: GradleBuildResult) {
  val taskLogs = mutableSetOf<String>()
  result.stdout.forEachLine {
    if (it.startsWith("Class loader for AGP API = ")) {
      taskLogs.add(it)
    }
  }
  assertThat(taskLogs).named("Log lines that should contain different class loader hashes").hasSize(3)
}

class CheckMemoryUsageCallback : GenericCallback {
  companion object {
    const val UNUSUAL_HEAP_MEMORY_GROWTH_ERROR_MESSAGE = "Unusual heap memory usage growth detected"
    const val HEAP_MEMORY_GROWTH_THRESHOLD_MB = 30.0
  }

  override fun handleProject(project: Project) {
    project.configurations.findByName("_internal-screenshot-validation-junit-engine")?.let {
      project.dependencies.add("_internal-screenshot-validation-junit-engine", "com.mytest.memory-printer:memory-printer:1.0")
    }
    project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java) {
      it.addTestOutputListener(
        object : TestOutputListener {
          var minHeapUsage: Double = Double.MAX_VALUE
          var maxHeapUsage: Double = 0.0

          override fun onOutput(testDescriptor: TestDescriptor, outputEvent: TestOutputEvent) {
            val currentUsedHeapSizeMb = extractHeapUsed(outputEvent.message) ?: return
            minHeapUsage = min(minHeapUsage, currentUsedHeapSizeMb)
            maxHeapUsage = max(maxHeapUsage, currentUsedHeapSizeMb)
            if (maxHeapUsage - minHeapUsage > HEAP_MEMORY_GROWTH_THRESHOLD_MB) {
              System.err.println(UNUSUAL_HEAP_MEMORY_GROWTH_ERROR_MESSAGE)
              System.err.println("Heap memory usage was grown from $minHeapUsage MB to $maxHeapUsage MB")
            }
          }

          fun extractHeapUsed(input: String): Double? {
            val regex = """HEAP:.*?Used:\s+([0-9.]+)""".toRegex()
            val matchResult = regex.find(input)
            return matchResult?.groupValues?.get(1)?.toDoubleOrNull()
          }
        }
      )
    }
  }
}

class FilterSetupCallback : GenericCallback {
  override fun handleProject(project: Project) {
    project.afterEvaluate {
      project.tasks.named("validateDebugScreenshotTest", org.gradle.api.tasks.testing.Test::class.java) {
        it.setTestNameIncludePatterns(listOf("*simpleComposableTest*"))
      }
    }
  }
}

class CheckMemoryUsage : TestExecutionListener {
  override fun executionFinished(testIdentifier: TestIdentifier, testExecutionResult: TestExecutionResult) {
    MemoryPrinter.printMemoryUsage()
    if (testIdentifier.parentId.isEmpty) {
      MemoryPrinter.dumpHeap()
    }
  }
}

object MemoryPrinter {
  private val decimalFormat = DecimalFormat("#,###.## MB")
  private const val MB = 1024.0 * 1024.0
  private const val HOTSPOT_BEAN_NAME = "com.sun.management:type=HotSpotDiagnostic"

  fun printMemoryUsage() {
    repeat(3) { System.gc() }
    val memoryBean = ManagementFactory.getMemoryMXBean()
    val heapUsage = memoryBean.heapMemoryUsage
    println("HEAP: Used: ${format(heapUsage.used)} | Committed: ${format(heapUsage.committed)} | Max: ${format(heapUsage.max)}")
  }

  fun dumpHeap(live: Boolean = true) {
    repeat(3) { System.gc() }
    try {
      val server = ManagementFactory.getPlatformMBeanServer()
      val mxBean = ManagementFactory.newPlatformMXBeanProxy(server, HOTSPOT_BEAN_NAME, HotSpotDiagnosticMXBean::class.java)
      val tempDir = System.getenv("TEST_UNDECLARED_OUTPUTS_DIR") ?: System.getProperty("java.io.tmpdir")
      val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
      val actualFileName = "heap_dump_$timestamp.hprof"
      val file = File(tempDir, actualFileName)
      val filePath = file.absolutePath
      if (file.exists()) {
        println("Warning: File $filePath already exists. Deleting it...")
        file.delete()
      }
      println("Attempting to dump heap to: $filePath")
      println("Freezing application for heap dump... (This may take a moment)")
      mxBean.dumpHeap(filePath, live)
      println("Successfully dumped heap to: $filePath")
    } catch (e: Exception) {
      println("Failed to dump heap: ${e.message}")
      e.printStackTrace()
    }
  }

  private fun format(bytes: Long): String {
    return decimalFormat.format(bytes / MB)
  }
}
