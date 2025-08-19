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
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.provider.Provider
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.jvm.toolchain.JavaToolchainSpec
import org.gradle.util.GradleVersion
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.Mockito.`when`

/**
 * Unit tests for [PreviewScreenshotGradlePlugin]
 */
class PreviewScreenshotGradlePluginTest {
    @get:Rule
    val mockitoJUnitRule: MockitoRule = MockitoJUnit.rule()
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    lateinit var mockProject: Project
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    lateinit var mockAndroidPlugin: AndroidComponentsExtension<*, *, *>
    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    lateinit var mockCommonExtension: CommonExtension

    @Before
    fun setupMocks() {
        `when`(mockProject.extensions.getByType(eq(AndroidComponentsExtension::class.java))).thenReturn(mockAndroidPlugin)
        `when`(mockProject.extensions.getByType(eq(CommonExtension::class.java))).thenReturn(mockCommonExtension)
        `when`(mockProject.findProperty(PreviewScreenshotGradlePlugin.ST_SOURCE_SET_ENABLED)).thenReturn(true)
    }
    private fun applyScreenshotPlugin(
        agpVersion: AndroidPluginVersion = AndroidPluginVersion(8, 7).dev(),
        validationEngineVersion: String = PreviewScreenshotGradlePlugin.SCREENSHOT_TEST_PLUGIN_VERSION) {
        `when`(mockAndroidPlugin.pluginVersion).thenReturn(agpVersion)
        `when`(mockProject.findProperty(PreviewScreenshotGradlePlugin.VALIDATION_ENGINE_VERSION_OVERRIDE)).thenReturn(validationEngineVersion)
        val plugin = PreviewScreenshotGradlePlugin()

        plugin.apply(mockProject)
        val captor = argumentCaptor<Action<AndroidBasePlugin>>()
        Mockito.verify(mockProject.plugins, Mockito.atLeastOnce())
            .withType(eq(AndroidBasePlugin::class.java), captor.capture())
        captor.firstValue.execute(AndroidBasePlugin())
    }
    @Test
    fun agpVersionCheck() {
        val unsupportedVersionsTooOld = listOf(
                AndroidPluginVersion(8, 5, 0).alpha(8),
                AndroidPluginVersion(8, 4),
        )
        val supportedVersions = listOf(
                AndroidPluginVersion(8, 5).dev(),
                AndroidPluginVersion(8, 5, 0).beta(1),
                AndroidPluginVersion(8, 6, 0).alpha(1),
                AndroidPluginVersion(8, 7, 0).alpha(1),
                AndroidPluginVersion(8, 8, 0).alpha(1),
                AndroidPluginVersion(8, 9, 0).alpha(1),
                AndroidPluginVersion(8, 10, 0).alpha(1),
                AndroidPluginVersion(8, 11, 0).alpha(1),
                AndroidPluginVersion(8, 12, 0).alpha(1),
                AndroidPluginVersion(8, 13, 0).alpha(1),
                AndroidPluginVersion(9, 0, 0).alpha(1),
                AndroidPluginVersion(9, 0, Int.MAX_VALUE),
            )
        val unsupportedVersionsTooNew = listOf(
            AndroidPluginVersion(9, 1, 0).alpha(1),
            AndroidPluginVersion(9, 1),
        )
        unsupportedVersionsTooOld.forEach {
            val e = assertThrows(IllegalStateException::class.java) {
                applyScreenshotPlugin(it)
            }
            assertThat(e).hasMessageThat()
                    .contains("requires Android Gradle plugin version between 8.5.0-beta01 and 9.0.")
        }
        unsupportedVersionsTooNew.forEach {
            val e = assertThrows(IllegalStateException::class.java) {
                applyScreenshotPlugin(it)
            }
            assertThat(e).hasMessageThat()
                .contains("requires Android Gradle plugin version between 8.5.0-beta01 and 9.0.")
        }
        supportedVersions.forEach {
            applyScreenshotPlugin(it)
        }
    }

    @Test
    fun validationEngineVersionCheck() {
        val unsupportedVersionsTooOld = listOf(
            "0.0.1-alpha01",
            "0.0.1-alpha02",
        )
        val supportedVersions = listOf(
            "0.0.1-dev",
            "0.0.1-alpha03",
        )

        unsupportedVersionsTooOld.forEach {
            val e = assertThrows(IllegalStateException::class.java) {
                applyScreenshotPlugin(validationEngineVersion = it)
            }
            assertThat(e).hasMessageThat()
                .contains("Preview screenshot plugin requires the screenshot validation engine version to be at least ${PreviewScreenshotGradlePlugin.MIN_VALIDATION_ENGINE_VERSION}, ${PreviewScreenshotGradlePlugin.VALIDATION_ENGINE_VERSION_OVERRIDE} cannot be set to $it.")
        }

        supportedVersions.forEach {
            applyScreenshotPlugin(validationEngineVersion = it)
        }
    }

    @Test
    fun jdkVersionCheck_whenJdkIsCompatible_doesNotThrow() {
        Mockito.mockStatic(JavaVersion::class.java).use { mocked ->
            mocked.`when`<JavaVersion> { JavaVersion.current() }.thenReturn(JavaVersion.VERSION_17)
            applyScreenshotPlugin()
        }
    }

    @Test
    fun jdkVersionCheck_whenGradleIsTooOldForToolchain_throwsIllegalStateException() {
        // Prepare the version objects before mocking.
        val oldGradleVersion = GradleVersion.version("8.13")
        val requiredGradleVersion = GradleVersion.version("8.14")

        Mockito.mockStatic(JavaVersion::class.java).use { mockedJava ->
            Mockito.mockStatic(GradleVersion::class.java).use { mockedGradle ->
                mockedJava.`when`<JavaVersion> { JavaVersion.current() }.thenReturn(JavaVersion.VERSION_24)

                // **THE FIX**: Stub *every* static method that will be called.
                mockedGradle.`when`<GradleVersion> { GradleVersion.current() }.thenReturn(oldGradleVersion)
                mockedGradle.`when`<GradleVersion> { GradleVersion.version("8.14") }.thenReturn(requiredGradleVersion)

                val e = assertThrows(IllegalStateException::class.java) {
                    applyScreenshotPlugin()
                }
                assertThat(e).hasMessageThat().contains("requires Gradle version 8.14 or newer for screenshot tests")
            }
        }
    }

    @Test
    fun jdkVersionCheck_whenJdkIsIncompatibleAndToolchainIsFound_succeeds() {
        // Prepare the version objects before mocking.
        val compatibleGradleVersion = GradleVersion.version("8.14")
        val requiredGradleVersion = GradleVersion.version("8.14")

        Mockito.mockStatic(JavaVersion::class.java).use { mockedJava ->
            Mockito.mockStatic(GradleVersion::class.java).use { mockedGradle ->
                mockedJava.`when`<JavaVersion> { JavaVersion.current() }.thenReturn(JavaVersion.VERSION_24)

                // **THE FIX**: Stub *every* static method that will be called.
                mockedGradle.`when`<GradleVersion> { GradleVersion.current() }.thenReturn(compatibleGradleVersion)
                mockedGradle.`when`<GradleVersion> { GradleVersion.version("8.14") }.thenReturn(requiredGradleVersion)

                val mockToolchainService = Mockito.mock(JavaToolchainService::class.java, Answers.RETURNS_DEEP_STUBS)
                `when`(mockProject.extensions.getByType(eq(JavaToolchainService::class.java))).thenReturn(mockToolchainService)
                @Suppress("UNCHECKED_CAST")
                val mockLauncherProvider = Mockito.mock(Provider::class.java) as Provider<JavaLauncher>
                `when`(mockToolchainService.launcherFor(any<Action<JavaToolchainSpec>>())).thenReturn(mockLauncherProvider)

                applyScreenshotPlugin()
                Mockito.verify(mockToolchainService).launcherFor(any<Action<JavaToolchainSpec>>())
            }
        }
    }

    @Test
    fun jdkVersionCheck_whenJdkIsIncompatibleAndNoToolchainFound_throwsGradleException() {
        // Prepare the version objects before mocking.
        val compatibleGradleVersion = GradleVersion.version("8.14")
        val requiredGradleVersion = GradleVersion.version("8.14")

        Mockito.mockStatic(JavaVersion::class.java).use { mockedJava ->
            Mockito.mockStatic(GradleVersion::class.java).use { mockedGradle ->
                mockedJava.`when`<JavaVersion> { JavaVersion.current() }.thenReturn(JavaVersion.VERSION_24)
                mockedGradle.`when`<GradleVersion> { GradleVersion.current() }.thenReturn(compatibleGradleVersion)
                mockedGradle.`when`<GradleVersion> { GradleVersion.version("8.14") }.thenReturn(requiredGradleVersion)

                val mockToolchainService = Mockito.mock(JavaToolchainService::class.java, Answers.RETURNS_DEEP_STUBS)
                `when`(mockProject.extensions.getByType(eq(JavaToolchainService::class.java))).thenReturn(mockToolchainService)

                `when`(mockToolchainService.launcherFor(any<Action<JavaToolchainSpec>>()))
                    .thenThrow(RuntimeException())

                val e = assertThrows(GradleException::class.java) {
                    applyScreenshotPlugin()
                }
                assertThat(e).hasMessageThat().contains(
                    "Compose Preview Screenshot Testing requires a JDK toolchain between version " +
                            "${PreviewScreenshotGradlePlugin.MIN_SUPPORTED_JDK_MAJOR_VERSION} and " +
                            "${PreviewScreenshotGradlePlugin.MAX_JDK_MAJOR_VERSION}, but none was found."
                )
            }
        }
    }
}
