/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.journeys

import com.android.build.api.AndroidPluginVersion
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.AppPlugin
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Action
import org.gradle.api.Project
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never

/**
 * Unit tests for [JourneysGradlePlugin]
 */
class JourneysGradlePluginTest {

    @get:Rule
    val mockitoJUnitRule: MockitoRule = MockitoJUnit.rule()

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    lateinit var mockProject: Project

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    lateinit var mockAndroidPlugin: ApplicationAndroidComponentsExtension

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    lateinit var mockApplicationExtension: ApplicationExtension

    @Before
    fun setupMocks() {
        `when`(mockProject.extensions.getByType(eq(ApplicationAndroidComponentsExtension::class.java))).thenReturn(
            mockAndroidPlugin
        )
    }

    private fun applyJourneysPlugin(
        agpVersion: AndroidPluginVersion = AndroidPluginVersion(8, 7).dev()
    ) {
        `when`(mockAndroidPlugin.pluginVersion).thenReturn(agpVersion)
        val plugin = JourneysGradlePlugin()

        plugin.apply(mockProject)
        val captor = argumentCaptor<Action<AppPlugin>>()
        verify(mockProject.plugins, atLeastOnce())
            .withType(eq(AppPlugin::class.java), captor.capture())
        captor.firstValue.execute(AppPlugin())

        val finalizeDslCaptor = argumentCaptor<(ApplicationExtension) -> Unit>()
        verify(mockAndroidPlugin, atLeastOnce()).finalizeDsl(finalizeDslCaptor.capture())
        finalizeDslCaptor.firstValue.invoke(mockApplicationExtension)
    }

    @Test
    fun agpVersionCheck() {
        val unsupportedVersionsTooOld = listOf(
            AndroidPluginVersion(8, 2, 0),
            AndroidPluginVersion(8, 1, 0).alpha(8),
            AndroidPluginVersion(8, 0),
        )
        val supportedVersions = listOf(
            AndroidPluginVersion(8, 2, 1),
            AndroidPluginVersion(8, 3, 0).alpha(1),
            AndroidPluginVersion(8, 4, 0).alpha(1),
            AndroidPluginVersion(8, 5, 0).alpha(1),
            AndroidPluginVersion(8, 6, 0).alpha(1),
            AndroidPluginVersion(8, 7, 0).alpha(1),
            AndroidPluginVersion(8, 8, 0).alpha(1),
            AndroidPluginVersion(8, 9, 0).alpha(1),
            AndroidPluginVersion(8, 10, 0).alpha(1),
            AndroidPluginVersion(8, 11, 0).alpha(1),
            AndroidPluginVersion(8, 12, 0).alpha(1),
            AndroidPluginVersion(8, 13, 0).alpha(1),
            AndroidPluginVersion(8, 13, 0)
        )
        val unsupportedVersionsTooNew = listOf(
            AndroidPluginVersion(9, 0, 0).alpha(1),
            AndroidPluginVersion(9, 0),
        )
        unsupportedVersionsTooOld.forEach {
            val e = assertThrows(IllegalStateException::class.java) {
                applyJourneysPlugin(it)
            }
            assertThat(e).hasMessageThat()
                .contains("requires Android Gradle plugin version between 8.2.1 and 8.13.")
        }
        unsupportedVersionsTooNew.forEach {
            val e = assertThrows(IllegalStateException::class.java) {
                applyJourneysPlugin(it)
            }
            assertThat(e).hasMessageThat()
                .contains("requires Android Gradle plugin version between 8.2.1 and 8.13.")
        }
        supportedVersions.forEach {
            applyJourneysPlugin(it)
        }
    }

    @Test
    fun testUniversalApkEnabledWhenAbiSplitsEnabled() {
        `when`(mockApplicationExtension.splits.abi.isEnable).thenReturn(true)
        applyJourneysPlugin()
        verify(mockApplicationExtension.splits.abi, atLeastOnce()).isUniversalApk = true
    }

    @Test
    fun testUniversalApkNotEnabledWhenAbiSplitsDisabled() {
        `when`(mockApplicationExtension.splits.abi.isEnable).thenReturn(false)
        applyJourneysPlugin()
        verify(mockApplicationExtension.splits.abi, never()).isUniversalApk = true
    }
}
