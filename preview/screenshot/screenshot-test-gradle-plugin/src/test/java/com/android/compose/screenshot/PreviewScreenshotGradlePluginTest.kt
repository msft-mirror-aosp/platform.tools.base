/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.compose.screenshot

import com.android.build.api.AndroidPluginVersion
import com.android.build.api.dsl.CommonExtension
import com.android.build.api.variant.AndroidComponentsExtension
import com.android.build.gradle.api.AndroidBasePlugin
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Action
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.util.GradleVersion
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq

/** Unit tests for [PreviewScreenshotGradlePlugin] */
class PreviewScreenshotGradlePluginTest {

  @get:Rule val mockitoJUnitRule: MockitoRule = MockitoJUnit.rule()
  @Mock(answer = Answers.RETURNS_DEEP_STUBS) lateinit var mockProject: Project
  @Mock(answer = Answers.RETURNS_DEEP_STUBS) lateinit var mockAndroidPlugin: AndroidComponentsExtension<*, *, *>
  @Mock(answer = Answers.RETURNS_DEEP_STUBS) lateinit var mockCommonExtension: CommonExtension

  @Before
  fun setupMocks() {
    `when`(mockProject.extensions.getByType(eq(AndroidComponentsExtension::class.java))).thenReturn(mockAndroidPlugin)
    `when`(mockProject.extensions.getByType(eq(CommonExtension::class.java))).thenReturn(mockCommonExtension)
    `when`(mockProject.providers.gradleProperty(PreviewScreenshotGradlePlugin.ST_SOURCE_SET_ENABLED).getOrNull()).thenReturn("true")
  }

  private fun applyScreenshotPlugin(
    agpVersion: AndroidPluginVersion = AndroidPluginVersion(8, 7).dev(),
    validationEngineVersion: String = PreviewScreenshotGradlePlugin.SCREENSHOT_TEST_PLUGIN_VERSION,
  ) {
    `when`(mockAndroidPlugin.pluginVersion).thenReturn(agpVersion)
    `when`(mockProject.providers.gradleProperty(PreviewScreenshotGradlePlugin.VALIDATION_ENGINE_VERSION_OVERRIDE).getOrNull())
      .thenReturn(validationEngineVersion)

    val plugin = PreviewScreenshotGradlePlugin()
    plugin.apply(mockProject)
    val captor = argumentCaptor<Action<AndroidBasePlugin>>()
    Mockito.verify(mockProject.plugins, Mockito.atLeastOnce()).withType(eq(AndroidBasePlugin::class.java), captor.capture())
    captor.firstValue.execute(AndroidBasePlugin())
  }

  @Test
  fun agpVersionCheck() {
    // Mock the JDK to a compatible version to isolate this test from the environment.
    Mockito.mockStatic(JavaVersion::class.java).use { mockedJava ->
      mockedJava.`when`<JavaVersion> { JavaVersion.current() }.thenReturn(JavaVersion.VERSION_17)

      val unsupportedVersionsTooOld = listOf(AndroidPluginVersion(8, 4), AndroidPluginVersion(8, 5, 0).alpha(8))
      val supportedVersions = listOf(AndroidPluginVersion(8, 5, 0).beta(1), AndroidPluginVersion(9, Int.MAX_VALUE, Int.MAX_VALUE))
      val unsupportedVersionsTooNew = listOf(AndroidPluginVersion(10, 0).alpha(1), AndroidPluginVersion(10, 0))
      unsupportedVersionsTooOld.forEach {
        val e = assertThrows(IllegalStateException::class.java) { applyScreenshotPlugin(it) }
        assertThat(e).hasMessageThat().contains("requires Android Gradle plugin version 8.5.0-beta01 or higher, but less than 10.0")
      }
      unsupportedVersionsTooNew.forEach {
        val e = assertThrows(IllegalStateException::class.java) { applyScreenshotPlugin(it) }
        assertThat(e).hasMessageThat().contains("requires Android Gradle plugin version 8.5.0-beta01 or higher, but less than 10.0")
      }
      supportedVersions.forEach { applyScreenshotPlugin(it) }
    }
  }

  @Test
  fun validationEngineVersionCheck() {
    // Mock the JDK to a compatible version to isolate this test from the environment.
    Mockito.mockStatic(JavaVersion::class.java).use { mockedJava ->
      mockedJava.`when`<JavaVersion> { JavaVersion.current() }.thenReturn(JavaVersion.VERSION_17)

      val unsupportedVersionsTooOld = listOf("0.0.1-alpha01", "0.0.1-alpha02")
      val supportedVersions = listOf("0.0.1-dev", "0.0.1-alpha03")

      unsupportedVersionsTooOld.forEach {
        val e = assertThrows(IllegalStateException::class.java) { applyScreenshotPlugin(validationEngineVersion = it) }
        assertThat(e)
          .hasMessageThat()
          .contains(
            "Preview screenshot plugin requires the screenshot validation engine version to be at least ${PreviewScreenshotGradlePlugin.MIN_VALIDATION_ENGINE_VERSION}, ${PreviewScreenshotGradlePlugin.VALIDATION_ENGINE_VERSION_OVERRIDE} cannot be set to $it."
          )
      }

      supportedVersions.forEach { applyScreenshotPlugin(validationEngineVersion = it) }
    }
  }

  @Test
  fun gradleVersionCheck_whenGradleIsCompatible_doesNotThrow() {
    val compatibleGradleVersion = GradleVersion.version("8.14")
    val requiredGradleVersion = GradleVersion.version("8.14")

    Mockito.mockStatic(GradleVersion::class.java).use { mockedGradle ->
      mockedGradle.`when`<GradleVersion> { GradleVersion.current() }.thenReturn(compatibleGradleVersion)
      mockedGradle.`when`<GradleVersion> { GradleVersion.version("8.14") }.thenReturn(requiredGradleVersion)
      Mockito.mockStatic(JavaVersion::class.java).use { mockedJava ->
        mockedJava.`when`<JavaVersion> { JavaVersion.current() }.thenReturn(JavaVersion.VERSION_17)
        applyScreenshotPlugin()
      }
    }
  }

  @Test
  fun gradleVersionCheck_whenGradleIsTooOld_throwsIllegalStateException() {
    val oldGradleVersion = GradleVersion.version("8.13")
    val requiredGradleVersion = GradleVersion.version("8.14")

    Mockito.mockStatic(JavaVersion::class.java).use { mockedJava ->
      Mockito.mockStatic(GradleVersion::class.java).use { mockedGradle ->
        mockedJava.`when`<JavaVersion> { JavaVersion.current() }.thenReturn(JavaVersion.VERSION_24)
        mockedGradle.`when`<GradleVersion> { GradleVersion.current() }.thenReturn(oldGradleVersion)
        mockedGradle.`when`<GradleVersion> { GradleVersion.version("8.14") }.thenReturn(requiredGradleVersion)

        val e = assertThrows(IllegalStateException::class.java) { applyScreenshotPlugin() }
        assertThat(e)
          .hasMessageThat()
          .isEqualTo(
            """
            Using JDK 24 requires Gradle version 8.14 or newer for screenshot tests.
            Current Gradle version is 8.13.
            Please upgrade your project's Gradle version.
            """
              .trimIndent()
          )
      }
    }
  }
}
