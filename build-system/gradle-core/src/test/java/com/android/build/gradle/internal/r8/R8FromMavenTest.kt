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

package com.android.build.gradle.internal.r8

import com.android.build.gradle.internal.fixtures.FakeProviderFactory
import com.android.build.gradle.internal.fixtures.ProjectFactory
import com.android.build.gradle.options.ProjectOptions
import com.android.build.gradle.options.StringOption
import com.android.builder.dexing.R8Version
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.junit.Before
import org.junit.Test

/** Unit tests for [R8FromMaven]. */
class R8FromMavenTest {

  private lateinit var project: Project

  @Before
  fun setUp() {
    project = ProjectFactory.project
  }

  @Test
  fun testDefaultR8VersionResolution() {
    val r8FromMaven = R8FromMaven.create(project) { null }

    assertThat(r8FromMaven.version).isEqualTo(R8Version.VERSION_AGP_WAS_SHIPPED_WITH)
    assertThat(r8FromMaven.r8Classpath).isNotNull()

    val configuration = r8FromMaven.r8Classpath as Configuration
    assertThat(configuration.isCanBeResolved).isTrue()
    assertThat(configuration.isCanBeConsumed).isFalse()
    assertThat(configuration.isTransitive).isTrue()

    val dependencies = configuration.dependencies
    assertThat(dependencies).hasSize(1)
    val dependency = dependencies.first()
    assertThat(dependency.group).isEqualTo("com.android.tools")
    assertThat(dependency.name).isEqualTo("r8")
    assertThat(dependency.version).isEqualTo(R8Version.VERSION_AGP_WAS_SHIPPED_WITH)
  }

  @Test
  fun testR8VersionOverrideViaStringOption() {
    val customVersion = "9.9.9-custom-test"
    val r8FromMaven = R8FromMaven.create(project) { option -> if (option == StringOption.R8_VERSION_OVERRIDE) customVersion else null }

    assertThat(r8FromMaven.version).isEqualTo(customVersion)
    val configuration = r8FromMaven.r8Classpath as Configuration
    val dependency = configuration.dependencies.first()
    assertThat(dependency.group).isEqualTo("com.android.tools")
    assertThat(dependency.name).isEqualTo("r8")
    assertThat(dependency.version).isEqualTo(customVersion)
  }

  @Test
  fun testR8VersionOverrideViaProjectOptions() {
    val customVersion = "10.0.1-override"
    val providerFactory =
      FakeProviderFactory(FakeProviderFactory.factory, mapOf(StringOption.R8_VERSION_OVERRIDE.propertyName to customVersion))
    val projectOptions = ProjectOptions(providerFactory)

    val r8FromMaven = R8FromMaven.create(project, projectOptions)
    assertThat(r8FromMaven.version).isEqualTo(customVersion)
    val configuration = r8FromMaven.r8Classpath as Configuration
    val dependency = configuration.dependencies.first()
    assertThat(dependency.group).isEqualTo("com.android.tools")
    assertThat(dependency.name).isEqualTo("r8")
    assertThat(dependency.version).isEqualTo(customVersion)
  }

  @Test
  fun testR8VersionOverrideViaExplicitProvider() {
    val dslVersion = "10.1.0-dsl"
    val r8FromMaven = R8FromMaven.create(project, { null }, { dslVersion })

    assertThat(r8FromMaven.version).isEqualTo(dslVersion)
    val configuration = r8FromMaven.r8Classpath as Configuration
    val dependency = configuration.dependencies.first()
    assertThat(dependency.group).isEqualTo("com.android.tools")
    assertThat(dependency.name).isEqualTo("r8")
    assertThat(dependency.version).isEqualTo(dslVersion)
  }

  @Test
  fun testExplicitProviderTakesPrecedenceOverStringOption() {
    val dslVersion = "10.2.0-dsl-priority"
    val optionVersion = "9.9.9-option"
    val r8FromMaven =
      R8FromMaven.create(
        project,
        { option -> if (option == StringOption.R8_VERSION_OVERRIDE) optionVersion else null },
        { dslVersion },
      )

    assertThat(r8FromMaven.version).isEqualTo(dslVersion)
    val configuration = r8FromMaven.r8Classpath as Configuration
    val dependency = configuration.dependencies.first()
    assertThat(dependency.group).isEqualTo("com.android.tools")
    assertThat(dependency.name).isEqualTo("r8")
    assertThat(dependency.version).isEqualTo(dslVersion)
  }

  @Test
  fun testR8VersionOverrideViaProjectOptionsWithExplicitProvider() {
    val projectOptionVersion = "9.9.9-option"
    val explicitVersion = "10.5.0-explicit"
    val providerFactory =
      FakeProviderFactory(FakeProviderFactory.factory, mapOf(StringOption.R8_VERSION_OVERRIDE.propertyName to projectOptionVersion))
    val projectOptions = ProjectOptions(providerFactory)

    val r8FromMaven = R8FromMaven.create(project, projectOptions) { explicitVersion }
    assertThat(r8FromMaven.version).isEqualTo(explicitVersion)
    val configuration = r8FromMaven.r8Classpath as Configuration
    val dependency = configuration.dependencies.first()
    assertThat(dependency.group).isEqualTo("com.android.tools")
    assertThat(dependency.name).isEqualTo("r8")
    assertThat(dependency.version).isEqualTo(explicitVersion)
  }

  @Test
  fun testProjectOptionsWithNullProviderFallsBackToProjectOptions() {
    val projectOptionVersion = "9.9.9-option"
    val providerFactory =
      FakeProviderFactory(FakeProviderFactory.factory, mapOf(StringOption.R8_VERSION_OVERRIDE.propertyName to projectOptionVersion))
    val projectOptions = ProjectOptions(providerFactory)

    val r8FromMaven = R8FromMaven.create(project, projectOptions, null)
    assertThat(r8FromMaven.version).isEqualTo(projectOptionVersion)
    val configuration = r8FromMaven.r8Classpath as Configuration
    val dependency = configuration.dependencies.first()
    assertThat(dependency.group).isEqualTo("com.android.tools")
    assertThat(dependency.name).isEqualTo("r8")
    assertThat(dependency.version).isEqualTo(projectOptionVersion)
  }

  @Test
  fun testStringOptionWithNullProviderFallsBackToStringOption() {
    val customVersion = "9.9.9-custom-test"
    val r8FromMaven =
      R8FromMaven.create(
        project,
        { option -> if (option == StringOption.R8_VERSION_OVERRIDE) customVersion else null },
        null,
      )

    assertThat(r8FromMaven.version).isEqualTo(customVersion)
    val configuration = r8FromMaven.r8Classpath as Configuration
    val dependency = configuration.dependencies.first()
    assertThat(dependency.group).isEqualTo("com.android.tools")
    assertThat(dependency.name).isEqualTo("r8")
    assertThat(dependency.version).isEqualTo(customVersion)
  }

  @Test
  fun testProviderReturningNullFallsBackToStringOption() {
    val optionVersion = "9.9.9-option"
    val r8FromMaven =
      R8FromMaven.create(
        project,
        { option -> if (option == StringOption.R8_VERSION_OVERRIDE) optionVersion else null },
        { null },
      )

    assertThat(r8FromMaven.version).isEqualTo(optionVersion)
    val configuration = r8FromMaven.r8Classpath as Configuration
    val dependency = configuration.dependencies.first()
    assertThat(dependency.group).isEqualTo("com.android.tools")
    assertThat(dependency.name).isEqualTo("r8")
    assertThat(dependency.version).isEqualTo(optionVersion)
  }

  @Test
  fun testConfigurationIsDetached() {
    val r8FromMaven = R8FromMaven.create(project) { null }
    val configuration = r8FromMaven.r8Classpath as Configuration
    assertThat(project.configurations.contains(configuration)).isFalse()
  }
}
