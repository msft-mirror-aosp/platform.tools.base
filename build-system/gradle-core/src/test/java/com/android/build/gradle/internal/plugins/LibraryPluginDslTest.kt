/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.build.gradle.internal.plugins

import com.android.build.api.dsl.LibraryBuildFeatures
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.internal.dsl.SigningConfig
import com.android.build.gradle.internal.fixture.TestConstants
import com.android.build.gradle.internal.fixture.TestProjects
import com.android.build.gradle.internal.fixture.VariantChecker
import com.android.build.gradle.internal.fixture.VariantCheckers
import com.android.build.gradle.internal.utils.importOfflineMavenRepo
import com.android.builder.errors.EvalIssueException
import com.google.common.truth.Truth
import org.gradle.api.Project
import org.junit.Assert
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Tests for the public DSL of the Lib plugin ('com.android.library')  */
class LibraryPluginDslTest {
    @get:Rule
    var projectDirectory: TemporaryFolder = TemporaryFolder()

    private lateinit var plugin: LibraryPlugin
    private lateinit var android: LibraryExtension
    private lateinit var checker: VariantChecker
    private lateinit var project: Project

    @Before
    fun setUp() {
        project = TestProjects.builder(projectDirectory.newFolder("project").toPath())
            .withPlugin(TestProjects.Plugin.LIBRARY)
            .build()
        android = project.extensions.getByType(LibraryExtension::class.java)
        android.setCompileSdkVersion(TestConstants.COMPILE_SDK_VERSION)
        android.buildToolsVersion = TestConstants.BUILD_TOOL_VERSION
        android.namespace = "com.example.namespace"
        android.buildFeatures { buildFeatures: LibraryBuildFeatures ->
            buildFeatures.aidl = true
        }
        plugin = project.plugins.getPlugin(LibraryPlugin::class.java)
        checker = VariantCheckers.createLibraryChecker(android)
    }

    @Test
    fun testBasic() {
        plugin.createAndroidTasks(project)

        val variants = checker.variants
        Truth.assertThat(variants).hasSize(2)

        val testVariants: Set<TestVariant?> = android.testVariants
        Truth.assertThat(testVariants).hasSize(1)

        checker.checkTestedVariant(
            "debug", "debugAndroidTest", variants, testVariants)
        checker.checkNonTestedVariant("release", variants)
    }

    @Test
    fun testNewBuildType() {
        android.buildTypes.create("custom")
        plugin.createAndroidTasks(project)

        val variants = checker.variants
        Truth.assertThat(variants).hasSize(3)

        val testVariants: Set<TestVariant?> = android.testVariants
        Truth.assertThat(testVariants).hasSize(1)

        checker.checkTestedVariant(
            "debug", "debugAndroidTest", variants, testVariants)
        checker.checkNonTestedVariant("release", variants)
        checker.checkNonTestedVariant("custom", variants)
    }

    @Test
    fun testNewBuildType_testBuildType() {
        android.buildTypes.create("custom")
        android.testBuildType = "custom"
        plugin.createAndroidTasks(project)

        val variants = checker.variants
        Truth.assertThat(variants).hasSize(3)

        val testVariants: Set<TestVariant?> = android.testVariants
        Truth.assertThat(testVariants).hasSize(1)

        checker.checkTestedVariant(
            "custom", "customAndroidTest", variants, testVariants)
        checker.checkNonTestedVariant("release", variants)
        checker.checkNonTestedVariant("debug", variants)
    }

    /**
     * test that debug build type maps to the SigningConfig object as the signingConfig container
     */
    @Test
    fun testDebugSigningConfig() {
        android.signingConfigs.getByName("debug") { debug: SigningConfig ->
            debug.storePassword("foo")
        }

        val signingConfig =
            android.buildTypes.getByName("debug").signingConfig

        Assert.assertNotNull(signingConfig)
        Assert.assertEquals(android.signingConfigs.getByName("debug"), signingConfig)
        Assert.assertEquals("foo", signingConfig?.storePassword)
    }

    @Test
    fun testResourceShrinkerWithPostProcessing() {
        val debug = android.buildTypes.getByName("debug")
        try {
            debug.postprocessing.isRemoveUnusedResources = true
            Assert.fail("Expected resource shrinker error")
        } catch (e: EvalIssueException) {
            Truth.assertThat(e)
                .hasMessageThat()
                .isEqualTo("Resource shrinker cannot be used for libraries.")
        }

        debug.postprocessing.isRemoveUnusedResources = false
        plugin.createAndroidTasks(project)
    }

    @Test
    fun testResourceShrinker() {
        val debug = android.buildTypes.getByName("debug")
        try {
            debug.isShrinkResources = true
            Assert.fail("Expected resource shrinker error")
        } catch (e: EvalIssueException) {
            Truth.assertThat(e)
                .hasMessageThat()
                .isEqualTo("Resource shrinker cannot be used for libraries.")
        }
        debug.isShrinkResources = false
        plugin.createAndroidTasks(project)
    }

    companion object {
        init {
            importOfflineMavenRepo()
        }
    }
}
