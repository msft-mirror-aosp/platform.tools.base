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

package com.android.build.gradle.internal.dsl

import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.internal.fixtures.ProjectFactory
import com.android.build.gradle.internal.services.createDslServices
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScreenshotTestSuiteImplTest {

  private val dslServices = createDslServices()
  private val project = ProjectFactory.project
  private val dependencyHandler = project.dependencies
  private val providers = project.providers

  @Test
  fun testInitializationDefaults() {
    val dslSuite = dslServices.newDecoratedInstance(ScreenshotTestSuiteImpl::class.java, "screenshotTest", dslServices)
    val suite =
      dslServices.newDecoratedInstance(ScreenshotAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    // 1. Assert targets: default target is created
    assertThat(suite.targets.names).containsExactly("default")
    assertThat(suite.requiresUpdateTask).isTrue()
    assertThat(dslSuite.imageDifferenceThreshold).isNull()

    // 2. Assert JUnit engine defaults: "preview-screenshot-test-engine" is included
    assertThat(suite.useJunitEngine.includeEngines).containsExactly("preview-screenshot-test-engine")

    // 3. Assert inputs mapping matches SCREENSHOT_TEST_ENGINE_INPUTS exactly
    val expectedInputs =
      listOf(
        AgpTestSuiteInputParameters.TEST_CLASSES,
        AgpTestSuiteInputParameters.TEST_CLASSPATH,
        AgpTestSuiteInputParameters.MAIN_CLASSES,
        AgpTestSuiteInputParameters.MAIN_CLASSPATH,
        AgpTestSuiteInputParameters.R_CLASS_JARS,
        AgpTestSuiteInputParameters.ANDROID_RES_DIRS,
        AgpTestSuiteInputParameters.RESOURCES_AP_ARCHIVE,
        AgpTestSuiteInputParameters.LAYOUTLIB_CLASSPATH,
        AgpTestSuiteInputParameters.LAYOUTLIB_DATA_DIR,
        AgpTestSuiteInputParameters.SDK_FONTS_DIR,
      )
    assertThat(suite.useJunitEngine.inputs).containsExactlyElementsIn(expectedInputs)
  }

  @Test
  fun testDynamicDependenciesMapping() {
    val dslSuite = dslServices.newDecoratedInstance(ScreenshotTestSuiteImpl::class.java, "screenshotTest", dslServices)
    val suite =
      dslServices.newDecoratedInstance(ScreenshotAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    // Set engineVersion
    dslSuite.engineVersion = "1.2.3"

    // Get resolved dependency notations
    val notations = suite.useJunitEngine.enginesDependencies.dependencies.get().map { it.toString() }

    assertThat(notations).contains("com.android.tools.screenshot:screenshot-validation-junit-engine:1.2.3")

    // Call dependencies block to force hostJar source set initialization
    dslSuite.dependencies {}
    val hostJarSources = suite.getSourceContainers().filterIsInstance<TestSuiteHostJarSpecImpl>()
    assertThat(hostJarSources).hasSize(1)
    val hostJar = hostJarSources.single()
    val hostJarDependencies = hostJar.dependencies.implementation.dependencies.get().map { it.toString() }
    assertThat(hostJarDependencies).isEmpty()
  }

  @Test
  fun testDynamicDependenciesMappingWhenUnconfigured() {
    val dslSuite = dslServices.newDecoratedInstance(ScreenshotTestSuiteImpl::class.java, "screenshotTest", dslServices)
    val suite =
      dslServices.newDecoratedInstance(ScreenshotAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    // Do not set engineVersion (defaults to uninitialized/null in the generated decorator)

    // Get resolved dependency notations - it should return a dummy "unspecified" version
    // and report a sync error.
    val notations = suite.useJunitEngine.enginesDependencies.dependencies.get().map { it.toString() }

    assertThat(notations).contains("com.android.tools.screenshot:screenshot-validation-junit-engine:unspecified")

    val reporter = dslServices.issueReporter as com.android.build.gradle.internal.fixtures.FakeSyncIssueReporter
    assertThat(reporter.errors).isNotEmpty()
    assertThat(reporter.errors.first()).contains("Screenshot test engine version must be specified")
  }

  @Test
  fun testDeclarativeDslUnsupportedConfigurations() {
    val declarativeDslServices =
      object : com.android.build.gradle.internal.services.DslServices by dslServices {
        override val configurations: org.gradle.api.artifacts.ConfigurationContainer
          get() = throw UnsupportedOperationException("Not supported")
      }

    val dslSuite =
      declarativeDslServices.newDecoratedInstance(ScreenshotTestSuiteImpl::class.java, "screenshotTest", declarativeDslServices)
    val suite =
      declarativeDslServices.newDecoratedInstance(
        ScreenshotAgpTestSuiteImpl::class.java,
        dslSuite,
        declarativeDslServices,
        dependencyHandler,
        providers,
      )

    // Instantiation should succeed even if configurations is unsupported
    assertThat(suite.targets.names).containsExactly("default")
    assertThat(suite.requiresUpdateTask).isTrue()
  }

  @Test
  fun testImageDifferenceThreshold() {
    val dslSuite = dslServices.newDecoratedInstance(ScreenshotTestSuiteImpl::class.java, "screenshotTest", dslServices)
    assertThat(dslSuite.imageDifferenceThreshold).isNull()
    dslSuite.imageDifferenceThreshold = 0.0001f
    assertThat(dslSuite.imageDifferenceThreshold).isEqualTo(0.0001f)
  }
}
