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
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.function.ThrowingRunnable

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

  @Test
  fun testVersionOverrideOlderThanMinimumFails() {
    val olderVersion = "8.2.47"
    val createWithOlderOption = ThrowingRunnable {
      R8FromMaven.create(project) { option -> if (option == StringOption.R8_VERSION_OVERRIDE) olderVersion else null }
    }
    val createWithOlderProvider = ThrowingRunnable { R8FromMaven.create(project, { null }, { olderVersion }) }

    for (create in listOf(createWithOlderOption, createWithOlderProvider)) {
      val exception = assertThrows(InvalidUserDataException::class.java, create)
      assertThat(exception)
        .hasMessageThat()
        .isEqualTo(
          "android.r8.versionOverride=8.2.47 is older than R8 ${R8Version.VERSION_AGP_WAS_SHIPPED_WITH}, the minimum R8 version supported " +
            "by this version of the Android Gradle Plugin. Remove the override or use version " +
            "${R8Version.VERSION_AGP_WAS_SHIPPED_WITH} or newer."
        )
    }
  }

  @Test
  fun testVersionOverrideEqualToMinimumIsAccepted() {
    val r8FromMaven =
      R8FromMaven.create(project) { option ->
        if (option == StringOption.R8_VERSION_OVERRIDE) R8Version.VERSION_AGP_WAS_SHIPPED_WITH else null
      }

    assertThat(r8FromMaven.version).isEqualTo(R8Version.VERSION_AGP_WAS_SHIPPED_WITH)
  }

  @Test
  fun testVersionOverrideNewerThanMinimumIsAccepted() {
    for (newerVersion in listOf("99.0.0", "main (build 1234567)")) {
      val r8FromMaven = R8FromMaven.create(project, { null }, { newerVersion })

      assertThat(r8FromMaven.version).isEqualTo(newerVersion)
    }
  }

  @Test
  fun testUnparseableVersionOverrideIsAccepted() {
    val r8FromMaven = R8FromMaven.create(project, { null }, { "latest" })

    assertThat(r8FromMaven.version).isEqualTo("latest")
  }

  @Test
  fun testNoVersionOverrideIsAccepted() {
    val r8FromMaven = R8FromMaven.create(project, { null }, { null })

    assertThat(r8FromMaven.version).isEqualTo(R8Version.VERSION_AGP_WAS_SHIPPED_WITH)
  }

  @Test
  fun testIsolatedR8ClasspathWithoutOverrideIsNull() {
    val r8FromMaven = R8FromMaven.create(project) { null }

    for (options in listOf(emptyMap(), mapOf(StringOption.R8_VERSION_OVERRIDE.propertyName to " "))) {
      val projectOptions = ProjectOptions(FakeProviderFactory(FakeProviderFactory.factory, options))

      // Without the override, tasks run the R8 bundled with AGP.
      assertThat(isolatedR8Classpath(projectOptions, r8FromMaven)).isNull()
    }
  }

  @Test
  fun testIsolatedR8ClasspathWithOverrideIsR8FromMavenClasspath() {
    val projectOptions =
      ProjectOptions(
        FakeProviderFactory(
          FakeProviderFactory.factory,
          mapOf(StringOption.R8_VERSION_OVERRIDE.propertyName to R8Version.VERSION_AGP_WAS_SHIPPED_WITH),
        )
      )
    val r8FromMaven = R8FromMaven.create(project, projectOptions)

    assertThat(isolatedR8Classpath(projectOptions, r8FromMaven)).isSameInstanceAs(r8FromMaven.r8Classpath)
    assertThat(isolatedR8Classpath(projectOptions, null)).isNull()
  }
}
