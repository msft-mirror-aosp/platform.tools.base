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

import com.android.build.api.component.impl.ComponentIdentityImpl
import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.api.dsl.TestTaskContext
import com.android.build.gradle.internal.fixtures.FakeProviderFactory
import com.android.build.gradle.internal.fixtures.FakeSyncIssueReporter
import com.android.build.gradle.internal.fixtures.ProjectFactory
import com.android.build.gradle.internal.profile.AnalyticsService
import com.android.build.gradle.internal.services.DslServices
import com.android.build.gradle.internal.services.createDslServices
import com.android.build.gradle.internal.services.createProjectServices
import com.android.build.gradle.internal.test.recordBackupTestRun
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.ProjectOptions
import com.android.build.gradle.tasks.TestSuiteTestTask
import com.android.testutils.SystemPropertyOverrides
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.wireless.android.sdk.stats.AndroidStudioEvent
import com.google.wireless.android.sdk.stats.TestRun
import com.google.wireless.android.sdk.stats.TestRun.BackupTestRun.BackupFailureReason
import com.google.wireless.android.sdk.stats.TestRun.BackupTestRun.BackupTransportMode
import java.time.Instant
import org.gradle.api.artifacts.ConfigurationContainer
import org.gradle.api.artifacts.dsl.DependencyCollector
import org.gradle.api.services.BuildServiceRegistry
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestKeyValueDataEvent
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.process.CommandLineArgumentProvider
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Differs from [BackupAgpTestSuiteImpl.DEFAULT_BACKUP_VERSION], so the tests can tell a configured version from the default. */
private const val CUSTOM_BACKUP_VERSION = "${BackupAgpTestSuiteImpl.DEFAULT_BACKUP_VERSION}-custom"

class BackupTestSuiteImplTest {

  private val dslServices = createDslServices()
  private val project = ProjectFactory.project
  private val dependencyHandler = project.dependencies
  private val providers = project.providers

  /** A suite without a parent, like the root suite of a test run. */
  private val rootSuite = mock<TestDescriptor>()

  private val passed = testResult(TestResult.ResultType.SUCCESS)
  private val failed = testResult(TestResult.ResultType.FAILURE)

  @Test
  fun testInitializationDefaults() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    // 1. Assert targets: default target is created
    assertThat(suite.targets.names).containsExactly("default")
    assertThat(suite.requiresUpdateTask).isFalse()

    // 2. Assert JUnit engine defaults: "junit-jupiter" engine is configured
    assertThat(suite.useJunitEngine.includeEngines).containsExactly("junit-jupiter")

    // 3. Assert engine dependencies: the JUnit Jupiter engine and a matching launcher are registered as engine dependencies
    val notations = suite.useJunitEngine.enginesDependencies.dependencies.get().map { it.toString() }
    assertThat(notations)
      .containsAtLeast("org.junit.jupiter:junit-jupiter-engine:5.13.3", "org.junit.platform:junit-platform-launcher:1.13.3")

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
    dslSuite.backupTestLibraryVersion = CUSTOM_BACKUP_VERSION

    // Verify custom hostJar and testApk blocks
    dslSuite.hostJar { dependencies { implementation.add("com.google.truth:truth:1.4.2") } }
    dslSuite.testApk { dependencies { implementation.add("androidx.room:room-testing:2.6.1") } }
    val hostJarSources = suite.getSourceContainers().filterIsInstance<TestSuiteHostJarSpecImpl>()
    assertThat(hostJarSources).hasSize(1)
    val hostJar = hostJarSources.single()
    assertThat(hostJar.dependencies.implementation.notations())
      .containsExactly("androidx.test.backup:backup-host:$CUSTOM_BACKUP_VERSION", "com.google.truth:truth:1.4.2")

    val testApkSources = suite.getSourceContainers().filterIsInstance<TestSuiteTestApkSpecImpl>()
    assertThat(testApkSources).hasSize(1)
    val testApk = testApkSources.single()
    assertThat(testApk.dependencies.implementation.notations())
      .containsExactly("androidx.test.backup:backup:$CUSTOM_BACKUP_VERSION", "androidx.room:room-testing:2.6.1")
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
  fun testDevicePropertiesAreForwardedToTestJvm() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)
    val argumentProviders = mutableListOf<CommandLineArgumentProvider>()
    val mockTestTask = mock<org.gradle.api.tasks.testing.Test>()
    whenever(mockTestTask.jvmArgumentProviders).thenReturn(argumentProviders)
    suite.testTaskConfigActions.forEach { it(mockTestTask, mock<TestTaskContext>()) }

    SystemPropertyOverrides().use { systemPropertyOverrides ->
      // Set after the task is configured, because the properties are read when the test task runs.
      systemPropertyOverrides.setProperty("androidx.test.backup.device.serial", "emulator-5554")
      systemPropertyOverrides.setProperty("androidx.test.backup.device.api", "34")
      systemPropertyOverrides.setProperty("androidx.test.backup.other", "value")
      assertThat(argumentProviders.flatMap { it.asArguments() })
        .containsExactly("-Dandroidx.test.backup.device.serial=emulator-5554", "-Dandroidx.test.backup.device.api=34")
    }
  }

  @Test
  fun testFailedTestsShowTheExceptionMessage() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)
    val task = project.tasks.register("backupTestSuiteExceptionFormat", org.gradle.api.tasks.testing.Test::class.java).get()

    suite.testTaskConfigActions.forEach { it(task, mock<TestTaskContext>()) }

    assertThat(task.testLogging.exceptionFormat).isEqualTo(TestExceptionFormat.FULL)
  }

  @Test
  fun testDiscoveryIssuesFailTheRun() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)
    val task = project.tasks.register("backupTestSuiteDiscoveryIssues", org.gradle.api.tasks.testing.Test::class.java).get()

    suite.testTaskConfigActions.forEach { it(task, mock<TestTaskContext>()) }

    assertThat(task.systemProperties).containsEntry("junit.platform.discovery.issue.severity.critical", "WARNING")
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
  fun testTargetsVariantsOfTestedBuildTypeByDefault() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    assertThat(suite.targetsVariant(variant("freeDebug", "debug"), testBuildType = "debug")).isTrue()
    assertThat(suite.targetsVariant(variant("paidDebug", "debug"), testBuildType = "debug")).isTrue()
    assertThat(suite.targetsVariant(variant("freeRelease", "release"), testBuildType = "debug")).isFalse()
    assertThat(suite.targetsVariant(variant("freeStaging", "staging"), testBuildType = "staging")).isTrue()
    assertThat(suite.targetsVariant(variant("freeDebug", "debug"), testBuildType = "staging")).isFalse()
  }

  @Test
  fun testTargetsNoVariantByDefaultWithoutTestedBuildType() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    assertThat(suite.targetsVariant(variant("debug", "debug"), testBuildType = null)).isFalse()
    assertThat(suite.targetsVariant(ComponentIdentityImpl("main"), testBuildType = null)).isFalse()
  }

  @Test
  fun testTargetVariantsReplaceDefault() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    // The DSL block sets targetVariants after the suite is created
    dslSuite.targetVariants.add("paidRelease")

    assertThat(suite.targetsVariant(variant("paidRelease", "release"), testBuildType = "debug")).isTrue()
    assertThat(suite.targetsVariant(variant("paidDebug", "debug"), testBuildType = "debug")).isFalse()
  }

  @Test
  fun testCustomSuitesTargetNoVariantByDefault() {
    val suite = dslServices.newInstance(AgpTestSuiteImpl::class.java, "customSuite", dslServices, false)

    assertThat(suite.targetsVariant(variant("debug", "debug"), testBuildType = "debug")).isFalse()
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
      backupTestLibraryVersion = CUSTOM_BACKUP_VERSION,
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
    assertThat(event.testRun.backupTestRun.backupTestLibraryVersion).isEqualTo(CUSTOM_BACKUP_VERSION)
    assertThat(event.testRun.backupTestRun.hasIsCi()).isFalse()
    assertThat(event.testRun.backupTestRun.totalRunTimeMs).isEqualTo(3200L)
  }

  @Test
  fun testBackupTestListenerConfiguration() {
    val dslSuite = dslServices.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", dslServices)
    val suite = dslServices.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, dslServices, dependencyHandler, providers)

    assertThat(suite.testTaskConfigActions).isNotEmpty()

    val mockTestTask = mock<TestSuiteTestTask>()
    val mockContext = mock<TestTaskContext>()
    suite.testTaskConfigActions.first().invoke(mockTestTask, mockContext)

    val listenerCaptor = argumentCaptor<TestListener>()
    verify(mockTestTask).addTestListener(listenerCaptor.capture())
    val listener = listenerCaptor.firstValue
    assertThat(listener).isInstanceOf(BackupAgpTestSuiteImpl.BackupTestListener::class.java)
    // The same listener receives the JUnit report entries of the backup library, and runs without a device.
    verify(mockTestTask).addTestMetadataListener(listener as BackupAgpTestSuiteImpl.BackupTestListener)
    verify(mockTestTask).addNoDeviceListener(listener)
  }

  @Test
  fun testBackupTestListenerReportsConfiguredLibraryVersion() {
    val analyticsService = mock<AnalyticsService>()
    val servicesWithAnalytics = dslServicesWithAnalytics(analyticsService)
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
    dslSuite.backupTestLibraryVersion = CUSTOM_BACKUP_VERSION

    val mockTestTask = mock<org.gradle.api.tasks.testing.Test>()
    suite.testTaskConfigActions.forEach { it(mockTestTask, mock<TestTaskContext>()) }
    val listenerCaptor = argumentCaptor<TestListener>()
    verify(mockTestTask).addTestListener(listenerCaptor.capture())

    val rootSuiteDescriptor = mock<TestDescriptor>()
    whenever(rootSuiteDescriptor.parent).thenReturn(null)
    listenerCaptor.firstValue.afterSuite(rootSuiteDescriptor, mock<TestResult>())

    val eventCaptor = argumentCaptor<AndroidStudioEvent.Builder>()
    verify(analyticsService).recordEvent(eventCaptor.capture())
    assertThat(eventCaptor.firstValue.build().testRun.backupTestRun.backupTestLibraryVersion).isEqualTo(CUSTOM_BACKUP_VERSION)
  }

  @Test
  fun testBackupTestListenerAfterSuite() {
    val mockAnalyticsService = mock<AnalyticsService>()
    val provider = project.provider { mockAnalyticsService }
    val listener =
      BackupAgpTestSuiteImpl.BackupTestListener(
        analyticsServiceProvider = provider,
        backupTestLibraryVersion = CUSTOM_BACKUP_VERSION,
        invocationType = TestRun.TestInvocationType.GRADLE_TEST,
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
    assertThat(event.testRun.backupTestRun.backupTestLibraryVersion).isEqualTo(CUSTOM_BACKUP_VERSION)
    assertThat(event.testRun.backupTestRun.hasIsCi()).isFalse()
  }

  @Test
  fun testBackupTestListenerReportsBackupResults() {
    val testRun = recordedRun {
      test(passed, "BackupRestore.transportMode" to "LOCAL", "BackupRestore.storageDomainCount" to "2")
      test(
        failed,
        "BackupRestore.transportMode" to "CLOUD_ENCRYPTED",
        "BackupRestore.storageDomainCount" to "5",
        "BackupRestore.errorCode" to "VERIFICATION_FAILED",
      )
      test(
        failed,
        "BackupRestore.transportMode" to "CLOUD_UNENCRYPTED",
        "BackupRestore.storageDomainCount" to "1",
        "BackupRestore.errorCode" to "RESTORE_POLL_TIMEOUT",
      )
      afterSuite(rootSuite, failed)
    }

    // The transport that most tests used, the largest storage domain count and the reason of the first failure
    assertThat(testRun.backupTestRun.transportMode).isEqualTo(BackupTransportMode.GMS_TRANSPORT)
    assertThat(testRun.backupTestRun.storageDomainCount).isEqualTo(5)
    assertThat(testRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.ASSERTION_FAILURE)
  }

  @Test
  fun testBackupTestListenerIgnoresRecoveredErrors() {
    // A test can expect a restore to fail.
    val passedRun = recordedRun {
      test(passed, "BackupRestore.errorCode" to "RESTORE_FAILED")
      afterSuite(rootSuite, passed)
    }
    assertThat(passedRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.NO_FAILURE)

    // A test that backs up and restores successfully after an error fails for another reason.
    val recoveredRun = recordedRun {
      test(failed, "BackupRestore.errorCode" to "RESTORE_FAILED", "BackupRestore.status" to "SUCCESS")
      afterSuite(rootSuite, failed)
    }
    assertThat(recoveredRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.UNKNOWN_FAILURE_REASON)

    // The reason is that of the first failed test with an error.
    val failedRun = recordedRun {
      test(passed, "BackupRestore.errorCode" to "RESTORE_FAILED")
      test(failed)
      test(failed, "BackupRestore.errorCode" to "RESTORE_POLL_TIMEOUT")
      afterSuite(rootSuite, failed)
    }
    assertThat(failedRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.TIMEOUT)
  }

  @Test
  fun testBackupTestListenerReportsErrorsOfTestClasses() {
    // A controller that a test class constructor receives reports its errors for the class, while each test of the class runs.
    val testClass = mock<TestDescriptor>()
    whenever(testClass.parent).thenReturn(rootSuite)
    fun BackupAgpTestSuiteImpl.BackupTestListener.testOfClass(result: TestResult, vararg classEntries: Pair<String, String>) {
      classEntries.forEach { onMetadata(testClass, reportEntries(it)) }
      afterTest(mock<TestDescriptor>().also { whenever(it.parent).thenReturn(testClass) }, result)
    }

    // The test fails because the controller finds no device.
    val noDeviceRun = recordedRun {
      testOfClass(failed, "BackupRestore.errorCode" to "NO_MATCHING_DEVICE")
      afterSuite(rootSuite, failed)
    }
    assertThat(noDeviceRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.NO_DEVICE_AVAILABLE)

    // An error that a passed test expected doesn't explain a later failure.
    val unexplainedRun = recordedRun {
      testOfClass(passed, "BackupRestore.errorCode" to "RESTORE_FAILED")
      testOfClass(failed)
      afterSuite(rootSuite, failed)
    }
    assertThat(unexplainedRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.UNKNOWN_FAILURE_REASON)

    // An error that a later test expected doesn't replace the reason of an earlier failure.
    val failedRun = recordedRun {
      testOfClass(failed, "BackupRestore.errorCode" to "VERIFICATION_FAILED")
      testOfClass(passed, "BackupRestore.errorCode" to "RESTORE_FAILED")
      afterSuite(rootSuite, failed)
    }
    assertThat(failedRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.ASSERTION_FAILURE)
  }

  @Test
  fun testBackupTestListenerReportsRunsWithoutBackupResults() {
    val passingRun = recordedRun { afterSuite(rootSuite, passed) }
    assertThat(passingRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.NO_FAILURE)
    assertThat(passingRun.backupTestRun.hasTransportMode()).isFalse()
    assertThat(passingRun.backupTestRun.hasStorageDomainCount()).isFalse()

    assertThat(recordedRun { afterSuite(rootSuite, failed) }.backupTestRun.failureReason)
      .isEqualTo(BackupFailureReason.UNKNOWN_FAILURE_REASON)
  }

  @Test
  fun testBackupTestListenerMapsBackupLibraryValues() {
    val transportModes =
      mapOf(
        "LOCAL" to BackupTransportMode.LOCAL_TRANSPORT,
        "LOCAL_DEVICE_TO_DEVICE" to BackupTransportMode.LOCAL_TRANSPORT,
        "LOCAL_ENCRYPTED" to BackupTransportMode.LOCAL_TRANSPORT,
        "CLOUD_ENCRYPTED" to BackupTransportMode.GMS_TRANSPORT,
        "CLOUD_UNENCRYPTED" to BackupTransportMode.GMS_TRANSPORT,
        "DEVICE_TO_DEVICE" to BackupTransportMode.D2D_TRANSPORT,
        "OTHER" to BackupTransportMode.UNKNOWN_TRANSPORT_MODE,
      )
    for ((name, transportMode) in transportModes) {
      val testRun = recordedRun {
        test(passed, "BackupRestore.transportMode" to name)
        afterSuite(rootSuite, passed)
      }
      assertWithMessage(name).that(testRun.backupTestRun.transportMode).isEqualTo(transportMode)
    }

    val failureReasons =
      mapOf(
        "NO_ONLINE_DEVICE" to BackupFailureReason.NO_DEVICE_AVAILABLE,
        "NO_MATCHING_DEVICE" to BackupFailureReason.NO_DEVICE_AVAILABLE,
        "UNSUPPORTED_API_LEVEL" to BackupFailureReason.API_LEVEL_UNSUPPORTED,
        "GMSCORE_OUTDATED_OR_MISSING" to BackupFailureReason.GMS_CORE_OUTDATED,
        "BMGR_INIT_FAILED" to BackupFailureReason.BMGR_FAILURE,
        "BACKUP_FAILED" to BackupFailureReason.BMGR_FAILURE,
        "RESTORE_FAILED" to BackupFailureReason.BMGR_FAILURE,
        "SEEDING_FAILED" to BackupFailureReason.SETUP_PRECONDITION_FAILURE,
        "CLEAR_DATA_FAILED" to BackupFailureReason.SETUP_PRECONDITION_FAILURE,
        "VERIFICATION_FAILED" to BackupFailureReason.ASSERTION_FAILURE,
        "RESTORE_POLL_TIMEOUT" to BackupFailureReason.TIMEOUT,
        "UNKNOWN_ERROR" to BackupFailureReason.UNKNOWN_FAILURE_REASON,
      )
    for ((errorCode, failureReason) in failureReasons) {
      val testRun = recordedRun {
        test(failed, "BackupRestore.errorCode" to errorCode)
        afterSuite(rootSuite, failed)
      }
      assertWithMessage(errorCode).that(testRun.backupTestRun.failureReason).isEqualTo(failureReason)
    }
  }

  @Test
  fun testBackupTestListenerReportsIdeRuns() {
    val analyticsService = mock<AnalyticsService>()
    val projectOptions =
      ProjectOptions(FakeProviderFactory(FakeProviderFactory.factory, mapOf(BooleanOption.IDE_INVOKED_FROM_IDE.propertyName to "true")))
    val services = dslServicesWithAnalytics(analyticsService, createDslServices(createProjectServices(projectOptions = projectOptions)))
    val dslSuite = services.newDecoratedInstance(BackupTestSuiteImpl::class.java, "backupTest", services)
    val suite = services.newDecoratedInstance(BackupAgpTestSuiteImpl::class.java, dslSuite, services, dependencyHandler, providers)

    val mockTestTask = mock<org.gradle.api.tasks.testing.Test>()
    suite.testTaskConfigActions.forEach { it(mockTestTask, mock<TestTaskContext>()) }
    val listenerCaptor = argumentCaptor<TestListener>()
    verify(mockTestTask).addTestListener(listenerCaptor.capture())
    listenerCaptor.firstValue.afterSuite(rootSuite, mock())

    val eventCaptor = argumentCaptor<AndroidStudioEvent.Builder>()
    verify(analyticsService).recordEvent(eventCaptor.capture())
    assertThat(eventCaptor.firstValue.build().testRun.testInvocationType)
      .isEqualTo(TestRun.TestInvocationType.ANDROID_STUDIO_THROUGH_GRADLE_TEST)
  }

  @Test
  fun testBackupTestListenerRecordsRunsWithoutDevice() {
    val testRun = recordedRun { onNoDevice() }

    assertThat(testRun.testKind).isEqualTo(TestRun.TestKind.BACKUP_TEST)
    assertThat(testRun.crashed).isTrue()
    assertThat(testRun.numberOfTestsExecuted).isEqualTo(0)
    assertThat(testRun.backupTestRun.backupTestLibraryVersion).isEqualTo(CUSTOM_BACKUP_VERSION)
    assertThat(testRun.backupTestRun.failureReason).isEqualTo(BackupFailureReason.NO_DEVICE_AVAILABLE)
  }

  /** Returns the run that a backup test listener records for the test [events]. */
  private fun recordedRun(events: BackupAgpTestSuiteImpl.BackupTestListener.() -> Unit): TestRun {
    val analyticsService = mock<AnalyticsService>()
    BackupAgpTestSuiteImpl.BackupTestListener(
        project.provider { analyticsService },
        CUSTOM_BACKUP_VERSION,
        TestRun.TestInvocationType.GRADLE_TEST,
      )
      .events()
    val eventCaptor = argumentCaptor<AndroidStudioEvent.Builder>()
    verify(analyticsService).recordEvent(eventCaptor.capture())
    return eventCaptor.firstValue.build().testRun
  }

  private fun reportEntries(vararg entries: Pair<String, String>) =
    object : TestKeyValueDataEvent {
      override fun getValues() = mapOf(*entries)

      override fun getLogTime(): Instant = Instant.EPOCH
    }

  /** Reports a test that publishes the JUnit report [entries], one at a time like the backup library, and ends with [result]. */
  private fun BackupAgpTestSuiteImpl.BackupTestListener.test(result: TestResult, vararg entries: Pair<String, String>) {
    val test = mock<TestDescriptor>()
    entries.forEach { onMetadata(test, reportEntries(it)) }
    afterTest(test, result)
  }

  private fun testResult(resultType: TestResult.ResultType): TestResult =
    mock<TestResult>().also { whenever(it.resultType).thenReturn(resultType) }

  private fun dslServicesWithAnalytics(analyticsService: AnalyticsService, services: DslServices = dslServices): DslServices {
    val registry = mock<BuildServiceRegistry>()
    whenever(registry.registerIfAbsent(any(), eq(AnalyticsService::class.java), any())).thenReturn(project.provider { analyticsService })
    return object : DslServices by services {
      override val buildServiceRegistry: BuildServiceRegistry
        get() = registry
    }
  }

  private fun DependencyCollector.notations(): List<String> = dependencies.get().map { "${it.group}:${it.name}:${it.version}" }

  private fun variant(name: String, buildType: String) = ComponentIdentityImpl(name, buildType = buildType)
}
