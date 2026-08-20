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
import com.android.build.gradle.internal.fixtures.FakeSyncIssueReporter
import com.android.build.gradle.internal.fixtures.ProjectFactory
import com.android.build.gradle.internal.services.DslServices
import com.android.build.gradle.internal.services.createDslServices
import com.google.common.truth.Truth.assertThat
import org.gradle.api.artifacts.ConfigurationContainer
import org.junit.Test

class BackupTestSuiteImplTest {

  private val dslServices = createDslServices()
  private val project = ProjectFactory.project
  private val dependencyHandler = project.dependencies

  @Test
  fun testInitializationDefaults() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler)

    // 1. Assert targets: default target is created
    assertThat(suite.targets.names).containsExactly("default")
    assertThat(suite.requiresUpdateTask).isFalse()

    // 2. Assert JUnit engine defaults: "junit-jupiter" engine is configured
    assertThat(suite.useJunitEngine.includeEngines).containsExactly("junit-jupiter")

    // 3. Assert engine dependencies: junit-jupiter-engine is registered as an engine dependency
    val notations = suite.useJunitEngine.enginesDependencies.dependencies.get().map { it.toString() }
    assertThat(notations).contains("org.junit.jupiter:junit-jupiter-engine:5.10.0")

    // 4. Assert input parameters mapping matches backup test execution requirements
    val expectedInputs =
      listOf(
        AgpTestSuiteInputParameters.TEST_CLASSES,
        AgpTestSuiteInputParameters.TEST_CLASSPATH,
        AgpTestSuiteInputParameters.MAIN_CLASSES,
        AgpTestSuiteInputParameters.MAIN_CLASSPATH,
        AgpTestSuiteInputParameters.TEST_APKS,
        AgpTestSuiteInputParameters.TESTED_APKS,
      )
    assertThat(suite.useJunitEngine.inputs).containsExactlyElementsIn(expectedInputs)

    // 5. Assert source containers are properly initialized for hostJar and testApk
    val hostJarSources = suite.getSourceContainers().filterIsInstance<TestSuiteHostJarSpecImpl>()
    assertThat(hostJarSources).hasSize(1)
    val testApkSources = suite.getSourceContainers().filterIsInstance<TestSuiteTestApkSpecImpl>()
    assertThat(testApkSources).hasSize(1)
  }

  @Test
  fun testDynamicDependenciesMapping() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler)

    // Set backupTestLibraryVersion
    dslSuite.backupTestLibraryVersion = "1.0.0-alpha01"

    // Simulate standard test configuration created by AGP for this suite
    val hostConfig = dslServices.configurations.create("backupTestHostJarDebugCompileClasspath")
    val hostDependencies = hostConfig.incoming.dependencies.map { it.toString() }
    assertThat(hostDependencies).contains("androidx.test.backup:backup-host:1.0.0-alpha01")

    val testApkConfig = dslServices.configurations.create("backupTestTestApkDebugCompileClasspath")
    val testApkConfigDependencies = testApkConfig.incoming.dependencies.map { it.toString() }
    assertThat(testApkConfigDependencies).contains("androidx.test.backup:backup:1.0.0-alpha01")

    // Verify custom hostJar and testApk blocks
    dslSuite.hostJar { dependencies { implementation.add("com.google.truth:truth:1.4.2") } }
    dslSuite.testApk { dependencies { implementation.add("androidx.room:room-testing:2.6.1") } }
    val hostJarSources = suite.getSourceContainers().filterIsInstance<TestSuiteHostJarSpecImpl>()
    assertThat(hostJarSources).hasSize(1)
    val hostJar = hostJarSources.single()
    val hostJarDeps = hostJar.dependencies.implementation.dependencies.get().map { it.toString() }
    assertThat(hostJarDeps).contains("com.google.truth:truth:1.4.2")

    val testApkSources = suite.getSourceContainers().filterIsInstance<TestSuiteTestApkSpecImpl>()
    assertThat(testApkSources).hasSize(1)
    val testApk = testApkSources.single()
    val testApkDeps = testApk.dependencies.implementation.dependencies.get().map { it.toString() }
    assertThat(testApkDeps).contains("androidx.room:room-testing:2.6.1")
  }

  @Test
  fun testDynamicDependenciesMappingWhenUnconfigured() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler)

    // Do not set version (defaults to uninitialized/null) - should fallback to default 1.0.0-alpha01 out of the box
    val hostConfig = dslServices.configurations.create("backupTestHostJarDebugCompileClasspath_unconfigured")
    val hostDependencies = hostConfig.incoming.dependencies.map { it.toString() }
    assertThat(hostDependencies).contains("androidx.test.backup:backup-host:1.0.0-alpha01")

    val testApkConfig = dslServices.configurations.create("backupTestTestApkDebugCompileClasspath_unconfigured")
    val testApkDependencies = testApkConfig.incoming.dependencies.map { it.toString() }
    assertThat(testApkDependencies).contains("androidx.test.backup:backup:1.0.0-alpha01")

    val reporter = dslServices.issueReporter as FakeSyncIssueReporter
    assertThat(reporter.errors).isEmpty()
  }

  @Test
  fun testDeclarativeDslUnsupportedConfigurations() {
    val declarativeDslServices =
      object : DslServices by dslServices {
        override val configurations: ConfigurationContainer
          get() = throw UnsupportedOperationException("Not supported")
      }

    val dslSuite = declarativeDslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", declarativeDslServices)
    val suite =
      declarativeDslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, declarativeDslServices, dependencyHandler)

    // Instantiation should succeed even if configurations is unsupported
    assertThat(suite.targets.names).containsExactly("default")
  }
}
