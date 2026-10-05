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
import com.android.build.api.dsl.TestTaskContext
import com.android.build.gradle.internal.fixtures.FakeSyncIssueReporter
import com.android.build.gradle.internal.fixtures.ProjectFactory
import com.android.build.gradle.internal.profile.AnalyticsService
import com.android.build.gradle.internal.services.DslServices
import com.android.build.gradle.internal.services.createDslServices
import com.android.build.gradle.internal.test.recordBackupTestRun
import com.google.common.truth.Truth.assertThat
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.TestRun
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.DependencyCollector
import org.gradle.api.services.BuildServiceRegistry
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class BackupTestSuiteImplTest {

  private val dslServices = createDslServices()
  private val project = ProjectFactory.project
  private val dependencyHandler = project.dependencies
  private val providers = project.providers

  @Test
  fun testInitializationDefaults() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

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
  fun testDependenciesUseConfiguredLibraryVersion() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    // The DSL block sets backupTestLibraryVersion after the suite is created
    dslSuite.backupTestLibraryVersion = "1.0.0-alpha02"

    // Verify custom hostJar and testApk blocks
    dslSuite.hostJar { dependencies { implementation.add("com.google.truth:truth:1.4.2") } }
    dslSuite.testApk { dependencies { implementation.add("androidx.room:room-testing:2.6.1") } }
    val hostJarSources = suite.getSourceContainers().filterIsInstance<TestSuiteHostJarSpecImpl>()
    assertThat(hostJarSources).hasSize(1)
    val hostJar = hostJarSources.single()
    assertThat(hostJar.dependencies.implementation.notations())
      .containsExactly("androidx.test.backup:backup-host:1.0.0-alpha02", "com.google.truth:truth:1.4.2")

    val testApkSources = suite.getSourceContainers().filterIsInstance<TestSuiteTestApkSpecImpl>()
    assertThat(testApkSources).hasSize(1)
    val testApk = testApkSources.single()
    assertThat(testApk.dependencies.implementation.notations())
      .containsExactly("androidx.test.backup:backup:1.0.0-alpha02", "androidx.room:room-testing:2.6.1")
  }

  @Test
  fun testDependenciesUseDefaultLibraryVersionWhenUnconfigured() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    val hostJar = suite.getSourceContainers().filterIsInstance<TestSuiteHostJarSpecImpl>().single()
    assertThat(hostJar.dependencies.implementation.notations())
      .containsExactly("androidx.test.backup:backup-host:${BackupAgpTestSuiteImpl.DEFAULT_BACKUP_VERSION}")
    val testApk = suite.getSourceContainers().filterIsInstance<TestSuiteTestApkSpecImpl>().single()
    assertThat(testApk.dependencies.implementation.notations())
      .containsExactly("androidx.test.backup:backup:${BackupAgpTestSuiteImpl.DEFAULT_BACKUP_VERSION}")

    val reporter = dslServices.issueReporter as FakeSyncIssueReporter
    assertThat(reporter.errors).isEmpty()
  }

  @Test
  fun testDependenciesUseDefaultLibraryVersionWhenBlank() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    dslSuite.backupTestLibraryVersion = "  "

    val hostJar = suite.getSourceContainers().filterIsInstance<TestSuiteHostJarSpecImpl>().single()
    assertThat(hostJar.dependencies.implementation.notations())
      .containsExactly("androidx.test.backup:backup-host:${BackupAgpTestSuiteImpl.DEFAULT_BACKUP_VERSION}")
    val testApk = suite.getSourceContainers().filterIsInstance<TestSuiteTestApkSpecImpl>().single()
    assertThat(testApk.dependencies.implementation.notations())
      .containsExactly("androidx.test.backup:backup:${BackupAgpTestSuiteImpl.DEFAULT_BACKUP_VERSION}")
  }

  @Test
  fun testBackupLibrariesNotAddedToOtherConfigurations() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    // Compiler plugin classpaths that KGP creates for the suite's host and device compilations
    for (name in listOf("kotlinCompilerPluginClasspathDebugBackupTestTest", "kotlinCompilerPluginClasspathDebugBackupTestAndroidTest")) {
      assertThat(dslServices.configurations.create(name).incoming.dependencies).isEmpty()
    }
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
      declarativeDslServices.newDecoratedInstance(
        BackupAgpTestSuiteImpl::class.java,
        dslSuite,
        declarativeDslServices,
        dependencyHandler,
        providers,
      )

    // Instantiation should succeed even if configurations is unsupported
    assertThat(suite.targets.names).containsExactly("default")
  }

  @Test
  fun testRecordBackupTestRunAnalytics() {
    val mockAnalyticsService = mock<AnalyticsService>()
    val eventCaptor = argumentCaptor<AndroidStudioEvent.Builder>()

    recordBackupTestRun(
      testCount = 5,
      passedTestCount = 4,
      failedTestCount = 1,
      analyticsService = mockAnalyticsService,
      backupTestLibraryVersion = "1.0.0-alpha01",
      totalRunTimeMs = 3200L,
      infrastructureCrashed = false,
    )

    verify(mockAnalyticsService).recordEvent(eventCaptor.capture())
    val event = eventCaptor.firstValue.build()

    assertThat(event.category).isEqualTo(AndroidStudioEvent.EventCategory.TESTS)
    assertThat(event.kind).isEqualTo(AndroidStudioEvent.EventKind.TEST_RUN)
    assertThat(event.testRun.testKind).isEqualTo(TestRun.TestKind.BACKUP_TEST)
    assertThat(event.testRun.numberOfTestsExecuted).isEqualTo(5)
    assertThat(event.testRun.backupTestRun.passedTestCount).isEqualTo(4)
    assertThat(event.testRun.backupTestRun.failedTestCount).isEqualTo(1)
    assertThat(event.testRun.backupTestRun.backupTestLibraryVersion).isEqualTo("1.0.0-alpha01")
    assertThat(event.testRun.backupTestRun.hasIsCi()).isFalse()
    assertThat(event.testRun.backupTestRun.totalRunTimeMs).isEqualTo(3200L)
  }

  @Test
  fun testBackupTestListenerConfiguration() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    assertThat(suite.testTaskConfigActions).isNotEmpty()

    val mockTestTask = mock<org.gradle.api.tasks.testing.Test>()
    val mockContext = mock<TestTaskContext>()
    suite.testTaskConfigActions.first().invoke(mockTestTask, mockContext)

    val listenerCaptor = argumentCaptor<TestListener>()
    verify(mockTestTask).addTestListener(listenerCaptor.capture())
    assertThat(listenerCaptor.firstValue).isInstanceOf(BackupAgpTestSuiteImpl.BackupTestListener::class.java)
  }

  @Test
  fun testBackupTestListenerReportsConfiguredLibraryVersion() {
    val analyticsService = mock<AnalyticsService>()
    val registry = mock<BuildServiceRegistry>()
    whenever(registry.registerIfAbsent(any(), eq(AnalyticsService::class.java), any())).thenReturn(project.provider { analyticsService })
    val servicesWithAnalytics =
      object : DslServices by dslServices {
        override val buildServiceRegistry: BuildServiceRegistry
          get() = registry
      }
    val dslSuite = servicesWithAnalytics.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", servicesWithAnalytics)
    val suite =
      servicesWithAnalytics.newDecoratedInstance(
        BackupAgpTestSuiteImpl::class.java,
        dslSuite,
        servicesWithAnalytics,
        dependencyHandler,
        providers,
      )

    // The DSL block sets backupTestLibraryVersion after the suite is created
    dslSuite.backupTestLibraryVersion = "1.0.0-alpha02"

    val mockTestTask = mock<org.gradle.api.tasks.testing.Test>()
    suite.testTaskConfigActions.forEach { it(mockTestTask, mock<TestTaskContext>()) }
    val listenerCaptor = argumentCaptor<TestListener>()
    verify(mockTestTask).addTestListener(listenerCaptor.capture())

    val rootSuiteDescriptor = mock<TestDescriptor>()
    whenever(rootSuiteDescriptor.parent).thenReturn(null)
    listenerCaptor.firstValue.afterSuite(rootSuiteDescriptor, mock<TestResult>())

    val eventCaptor = argumentCaptor<AndroidStudioEvent.Builder>()
    verify(analyticsService).recordEvent(eventCaptor.capture())
    assertThat(eventCaptor.firstValue.build().testRun.backupTestRun.backupTestLibraryVersion).isEqualTo("1.0.0-alpha02")
  }

  @Test
  fun testBackupTestListenerAfterSuite() {
    val mockAnalyticsService = mock<AnalyticsService>()
    val provider = project.provider { mockAnalyticsService }
    val listener =
      BackupAgpTestSuiteImpl.BackupTestListener(
        analyticsServiceProvider = provider,
        backupTestLibraryVersion = "1.0.0-alpha01",
      )

    val rootSuiteDescriptor = mock<TestDescriptor>()
    whenever(rootSuiteDescriptor.parent).thenReturn(null)

    val testResult = mock<TestResult>()
    whenever(testResult.testCount).thenReturn(3L)
    whenever(testResult.successfulTestCount).thenReturn(2L)
    whenever(testResult.failedTestCount).thenReturn(1L)
    whenever(testResult.startTime).thenReturn(1000L)
    whenever(testResult.endTime).thenReturn(2500L)
    whenever(testResult.resultType).thenReturn(TestResult.ResultType.FAILURE)

    listener.afterSuite(rootSuiteDescriptor, testResult)

    val eventCaptor = argumentCaptor<AndroidStudioEvent.Builder>()
    verify(mockAnalyticsService).recordEvent(eventCaptor.capture())
    val event = eventCaptor.firstValue.build()

    assertThat(event.category).isEqualTo(AndroidStudioEvent.EventCategory.TESTS)
    assertThat(event.kind).isEqualTo(AndroidStudioEvent.EventKind.TEST_RUN)
    assertThat(event.testRun.testKind).isEqualTo(TestRun.TestKind.BACKUP_TEST)
    assertThat(event.testRun.numberOfTestsExecuted).isEqualTo(3)
    assertThat(event.testRun.backupTestRun.passedTestCount).isEqualTo(2)
    assertThat(event.testRun.backupTestRun.failedTestCount).isEqualTo(1)
    assertThat(event.testRun.backupTestRun.totalRunTimeMs).isEqualTo(1500L)
    assertThat(event.testRun.backupTestRun.backupTestLibraryVersion).isEqualTo("1.0.0-alpha01")
    assertThat(event.testRun.backupTestRun.hasIsCi()).isFalse()
  }

  private fun DependencyCollector.notations(): List<String> = dependencies.get().map { "${it.group}:${it.name}:${it.version}" }
}
