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
}
