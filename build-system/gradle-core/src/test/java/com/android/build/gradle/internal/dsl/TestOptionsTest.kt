/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.build.api.dsl.ScreenshotTestSuite
import com.android.build.api.dsl.TestOptions
import com.android.build.gradle.internal.fixtures.FakeProviderFactory
import com.android.build.gradle.internal.fixtures.FakeSyncIssueReporter
import com.android.build.gradle.internal.services.DslServices
import com.android.build.gradle.internal.services.createDslServices
import com.android.build.gradle.internal.services.createProjectServices
import com.android.build.gradle.options.ProjectOptions
import com.android.builder.model.v2.ide.ProjectType
import com.google.common.collect.ImmutableMap
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class TestOptionsTest {

  private lateinit var testOptionsWrapper: TestOptionsWrapper
  private val dslServices: DslServices = createDslServices()

  private fun testOptions(action: TestOptions.() -> Unit) = testOptionsWrapper.testOptions(action)

  private val testOptions
    get() = testOptionsWrapper.testOptions

  interface TestOptionsWrapper {
    val testOptions: TestOptions

    fun testOptions(action: TestOptions.() -> Unit)
  }

  @Before
  fun init() {
    testOptionsWrapper = dslServices.newDecoratedInstance(TestOptionsWrapper::class.java, dslServices)
  }

  @Test
  fun testTargetSdk() {
    testOptions { targetSdk = 36 }
    assertThat(testOptions.targetSdk).named("testOptions.targetSdk").isEqualTo(36)
    assertThat(testOptions.targetSdkPreview).named("testOptions.targetSdkPreview").isNull()
  }

  @Test
  fun testTargetSdkPreview() {
    testOptions { targetSdkPreview = "Baklava" }
    assertThat(testOptions.targetSdk).named("testOptions.targetSdk").isEqualTo(35)
    assertThat(testOptions.targetSdkPreview).named("testOptions.targetSdkPreview").isEqualTo("Baklava")
  }

  @Test
  fun testTargetSdkSpec() {
    testOptions { targetSdk { version = release(36) } }
    assertThat(testOptions.targetSdk).named("testOptions.targetSdk").isEqualTo(36)
    assertThat(testOptions.targetSdkPreview).named("testOptions.targetSdkPreview").isNull()
  }

  @Test
  fun testTargetSdkSpecPreview() {
    testOptions { targetSdk { version = preview("Baklava") } }
    assertThat(testOptions.targetSdk).named("testOptions.targetSdk").isEqualTo(35)
    assertThat(testOptions.targetSdkPreview).named("testOptions.targetSdkPreview").isEqualTo("Baklava")
  }

  @Test
  fun testScreenshotTestDisabledByFlag() {
    val wrapper = dslServices.newDecoratedInstance(TestOptionsWrapper::class.java, dslServices)
    wrapper.testOptions {
      screenshotTests.create("myTest") {
        // This should fail because accessing the container reports an error
      }
    }
    val reporter = dslServices.issueReporter as FakeSyncIssueReporter
    assertThat(reporter.errors).hasSize(1)
    assertThat(reporter.errors.first()).contains("Compose Preview Screenshot Testing is an experimental feature")
  }

  @Test
  fun testScreenshotTestGetterAllowedWhenFlagDisabled() {
    val wrapper = dslServices.newDecoratedInstance(TestOptionsWrapper::class.java, dslServices)
    wrapper.testOptions {
      val test = screenshotTests // Safe read for reflective model traversers and internal task managers
    }
    val reporter = dslServices.issueReporter as FakeSyncIssueReporter
    assertThat(reporter.errors).isEmpty()
  }

  @Test
  fun testScreenshotTestEnabledByFlag() {
    val providerFactory =
      FakeProviderFactory(FakeProviderFactory.factory, ImmutableMap.of("android.experimental.enableScreenshotTest", "true"))
    val projectOptions = ProjectOptions(providerFactory)
    val projectServices = createProjectServices(projectOptions = projectOptions)
    val customDslServices = createDslServices(projectServices = projectServices)

    val wrapper = customDslServices.newDecoratedInstance(TestOptionsWrapper::class.java, customDslServices)
    wrapper.testOptions { screenshotTests.create("screenshotTest") { it.engineVersion = "1.0" } }

    val reporter = customDslServices.issueReporter as FakeSyncIssueReporter
    assertThat(reporter.errors).isEmpty()
    val suite = wrapper.testOptions.screenshotTests.getByName("screenshotTest") as ScreenshotTestSuite
    assertThat(suite.engineVersion).isEqualTo("1.0")
  }

  @Test
  fun testRegisteredScreenshotSuiteIsBridged() {
    val providerFactory =
      FakeProviderFactory(FakeProviderFactory.factory, ImmutableMap.of("android.experimental.enableScreenshotTest", "true"))
    val customDslServices = createDslServices(projectServices = createProjectServices(projectOptions = ProjectOptions(providerFactory)))

    val wrapper = customDslServices.newDecoratedInstance(TestOptionsWrapper::class.java, customDslServices)
    wrapper.testOptions { screenshotTests.register("lazySuite") }

    assertThat(wrapper.testOptions.customSuites.getByName("lazySuite")).isInstanceOf(ScreenshotAgpTestSuiteImpl::class.java)
  }

  @Test
  fun testBackupTestDisabledByFlag() {
    val wrapper = dslServices.newDecoratedInstance(TestOptionsWrapper::class.java, dslServices)
    wrapper.testOptions {
      backupTests.create("myBackupTest") {
        // This should fail because accessing the container reports an error
      }
    }
    val reporter = dslServices.issueReporter as FakeSyncIssueReporter
    assertThat(reporter.errors).hasSize(1)
    assertThat(reporter.errors.first()).contains("Automated Backup and Restore Testing is an experimental feature")
  }

  @Test
  fun testBackupTestGetterAllowedWhenFlagDisabled() {
    val wrapper = dslServices.newDecoratedInstance(TestOptionsWrapper::class.java, dslServices)
    wrapper.testOptions {
      val test = backupTests // Safe read for reflective model traversers and internal task managers
    }
    val reporter = dslServices.issueReporter as FakeSyncIssueReporter
    assertThat(reporter.errors).isEmpty()
  }

  @Test
  fun testBackupTestEnabledByFlag() {
    val providerFactory = FakeProviderFactory(FakeProviderFactory.factory, ImmutableMap.of("android.experimental.enableBackupTest", "true"))
    val projectOptions = ProjectOptions(providerFactory)
    val projectServices = createProjectServices(projectOptions = projectOptions)
    val customDslServices = createDslServices(projectServices = projectServices)

    val wrapper = customDslServices.newDecoratedInstance(TestOptionsWrapper::class.java, customDslServices)
    wrapper.testOptions {
      backupTests.create("myBackupTest") {
        it.backupTestLibraryVersion = "0.0.1-dev"
        it.targetVariants.add("debug")
      }
    }

    val reporter = customDslServices.issueReporter as FakeSyncIssueReporter
    assertThat(reporter.errors).isEmpty()
    val suite = wrapper.testOptions.backupTests.getByName("myBackupTest")
    assertThat(suite.backupTestLibraryVersion).isEqualTo("0.0.1-dev")
    assertThat(suite.targetVariants).containsExactly("debug")
    assertThat(wrapper.testOptions.customSuites.getByName("myBackupTest")).isInstanceOf(BackupAgpTestSuiteImpl::class.java)
  }

  @Test
  fun testBackupTestRejectedInLibraryModule() {
    val providerFactory = FakeProviderFactory(FakeProviderFactory.factory, ImmutableMap.of("android.experimental.enableBackupTest", "true"))
    val projectServices = createProjectServices(projectOptions = ProjectOptions(providerFactory))
    val libraryDslServices = createDslServices(projectServices = projectServices, projectType = ProjectType.LIBRARY)

    val wrapper = libraryDslServices.newDecoratedInstance(TestOptionsWrapper::class.java, libraryDslServices)
    wrapper.testOptions { backupTests.create("myBackupTest") {} }

    val reporter = libraryDslServices.issueReporter as FakeSyncIssueReporter
    assertThat(reporter.errors).hasSize(1)
    assertThat(reporter.errors.single())
      .isEqualTo(
        "Automated Backup and Restore Testing is only supported in application modules ('com.android.application'). " +
          "Move this backup test suite to an application module."
      )
    assertThat(wrapper.testOptions.customSuites.names).isEmpty()
  }

  @Test
  fun testCrossContainerDuplicateSuiteNamesReportError() {
    val providerFactory =
      FakeProviderFactory(
        FakeProviderFactory.factory,
        ImmutableMap.of("android.experimental.enableScreenshotTest", "true", "android.experimental.enableBackupTest", "true"),
      )
    val projectServices = createProjectServices(projectOptions = ProjectOptions(providerFactory))
    val appDslServices = createDslServices(projectServices = projectServices, projectType = ProjectType.APPLICATION)

    val wrapper = appDslServices.newDecoratedInstance(TestOptionsWrapper::class.java, appDslServices)
    wrapper.testOptions {
      customSuites.create("dupSuite") {}
      screenshotTests.create("dupSuite") {}
      backupTests.create("dupSuite") {}
    }

    val reporter = appDslServices.issueReporter as FakeSyncIssueReporter
    assertThat(reporter.errors)
      .containsExactly(
        "Cannot add screenshot test suite 'dupSuite' because a test suite with that name already exists. " +
          "Choose a unique name for each test suite.",
        "Cannot add backup test suite 'dupSuite' because a test suite with that name already exists. " +
          "Choose a unique name for each test suite.",
      )
    assertThat(wrapper.testOptions.customSuites.single()).isInstanceOf(AgpTestSuiteImpl::class.java)
  }

  @Test
  fun testCustomSuiteDoesNotInheritUnitTestAndroidResources() {
    testOptions {
      unitTests.isIncludeAndroidResources = true
      customSuites.create("unit") { it.hostJar {} }
    }

    val suite = testOptionsWrapper.testOptions.customSuites.getByName("unit") as AgpTestSuiteImpl
    assertThat(suite.androidResourcesIncluded).isFalse()
    val hostJar = suite.getSourceContainers().filterIsInstance<TestSuiteHostJarSpecImpl>().single()
    assertThat(hostJar.enableAndroidResources).isFalse()
  }
}
