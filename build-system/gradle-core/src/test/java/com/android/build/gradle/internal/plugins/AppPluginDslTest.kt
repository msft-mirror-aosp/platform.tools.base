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

import com.android.build.api.dsl.ApplicationExtension
import com.android.build.gradle.AppExtension
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.internal.fixture.TestConstants
import com.android.build.gradle.internal.fixture.TestProjects
import com.android.build.gradle.internal.fixture.VariantChecker
import com.android.build.gradle.internal.fixture.VariantCheckers
import com.android.build.gradle.internal.utils.importOfflineMavenRepo
import com.android.build.gradle.tasks.MergeResources
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
import groovy.util.Eval
import org.gradle.api.Project
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.*

/** Tests for the public DSL of the App plugin ("com.android.application")  */
class AppPluginDslTest {

    @get:Rule
    val projectDirectory: TemporaryFolder = TemporaryFolder()

    private lateinit var plugin: AppPlugin
    private lateinit var android: ApplicationExtension
    private lateinit var project: Project
    private val pluginType = TestProjects.Plugin.APP

    @Before
    fun setUp() {
        project =
            TestProjects.builder(projectDirectory.newFolder("project").toPath())
                .withPlugin(pluginType)
                .build()

        initFieldsFromProject()
    }

    private fun initFieldsFromProject() {
        android = project.extensions.getByType(pluginType.extensionClass) as ApplicationExtension
        android.compileSdk {
            version = release(TestConstants.COMPILE_SDK_VERSION)
        }
        android.buildToolsVersion = TestConstants.BUILD_TOOL_VERSION
        android.namespace = "com.example.namespace"
        plugin = project.plugins.getPlugin(pluginType.pluginClass) as AppPlugin
    }

    @Test
    fun testGeneratedDensities() {
        Eval.me(
            "project",
            project,
            ("""
project.android {
    flavorDimensions += 'foo'
    productFlavors {
        f1 {
        }

        f2  {
            vectorDrawables {
                generatedDensities = ['ldpi']
                generatedDensities += ['mdpi']
            }
        }

        f3 {
            vectorDrawables {
                generatedDensities = defaultConfig.generatedDensities - ['ldpi', 'mdpi']
            }
        }

        f4.vectorDrawables.generatedDensities = []

        oldSyntax {
            generatedDensities = ['ldpi']
        }
    }
}
""")
        )
        plugin.createAndroidTasks(project)

        checkGeneratedDensities(
            "mergeF1DebugResources", "ldpi", "mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"
        )
        checkGeneratedDensities("mergeF2DebugResources", "ldpi", "mdpi")
        checkGeneratedDensities("mergeF3DebugResources", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi")
        checkGeneratedDensities("mergeF4DebugResources")
        checkGeneratedDensities("mergeOldSyntaxDebugResources", "ldpi")
    }

    @Test
    fun testUseSupportLibrary_default() {
        plugin.createAndroidTasks(project)

        Truth.assertThat(
            getTask("mergeDebugResources", MergeResources::class.java)
                .isVectorSupportLibraryUsed
        ).isFalse()
    }

    @Test
    fun testUseSupportLibrary_flavors() {
        Eval.me(
            "project",
            project,
            ("""
project.android {

    flavorDimensions += 'foo'
    productFlavors {
        f1 {
        }

        f2  {
            vectorDrawables {
                useSupportLibrary = true
            }
        }

        f3 {
            vectorDrawables {
                useSupportLibrary = false
            }
        }
    }
}
""")
        )
        plugin.createAndroidTasks(project)

        Truth.assertThat(
            getTask("mergeF1DebugResources", MergeResources::class.java)
                .isVectorSupportLibraryUsed
        ).isFalse()
        Truth.assertThat(
            getTask("mergeF2DebugResources", MergeResources::class.java)
                .isVectorSupportLibraryUsed
        ).isTrue()
        Truth.assertThat(
            getTask("mergeF3DebugResources", MergeResources::class.java)
                .isVectorSupportLibraryUsed
        ).isFalse()
    }

    @Test
    fun testPostprocessingBlock_noCodeShrinking_oldDsl() {
        val release = android.buildTypes.getByName("release")
        release.isShrinkResources = true

        try {
            plugin.createAndroidTasks(project)
        } catch (e: Exception) {
            Truth.assertThat(e.message).contains("requires unused code shrinking")
        }
    }

    @Test
    fun testShrinkerChoice_oldDsl_r8Flag() {
        project =
            TestProjects.builder(projectDirectory.newFolder("oldDsl").toPath())
                .withPlugin(pluginType)
                .build()
        initFieldsFromProject()

        val debug = android.buildTypes.getByName("debug")
        debug.isMinifyEnabled = true

        plugin.createAndroidTasks(project)

        Truth.assertThat(project.tasks.names).doesNotContain(PROGUARD_DEBUG)
        Truth.assertThat(project.tasks.names).contains(R8_DEBUG)
    }

    @Test
    fun testShrinkerChoice_oldDsl_r8FlagWithoutMinification() {
        project =
            TestProjects.builder(projectDirectory.newFolder("oldDsl").toPath())
                .withPlugin(pluginType)
                .build()
        initFieldsFromProject()

        val debug = android.buildTypes.getByName("debug")
        debug.isMinifyEnabled = false

        plugin.createAndroidTasks(project)

        Truth.assertThat(project.tasks.names).doesNotContain(PROGUARD_DEBUG)
        Truth.assertThat(project.tasks.names).doesNotContain(R8_DEBUG)
    }

    @Test
    fun testApkShrinker_oldDsl() {
        project =
            TestProjects.builder(projectDirectory.newFolder("oldDsl_builtInShrinker").toPath())
                .withPlugin(pluginType)
                .build()
        initFieldsFromProject()
        val debug = android.buildTypes.getByName("debug")
        debug.isMinifyEnabled = true

        plugin.createAndroidTasks(project)

        Truth.assertThat(project.tasks.names).contains(R8_DEBUG)
        Truth.assertThat(project.tasks.names).contains(R8_DEBUG_ANDROID_TEST)
    }

    @Test
    fun testMinSdkVersionParsing() {
        android.defaultConfig.setMinSdkVersion("P")
        android.defaultConfig.minSdk {
            assertThat(version?.apiLevel)
                .named("android.defaultConfig.minSdk.version.apiLevel")
                .isEqualTo(27)
            assertThat(version?.codeName)
                .named("android.defaultConfig.minSdk.version.codeName")
                .isEqualTo("P")
        }

    }

    @Test
    fun testGroovySupportForSdkVersions() {
        // sanity check for groovy support
        // todo: replace it with new test fixture once b/417470034 is done
        Eval.me(
            "project",
            project,
            """
                project.android {
                    compileSdk = 33
                    compileSdk { version = release(33) }
                    compileSdk { version = release(33) {} }
                    compileSdk { version = preview('S') }
                }

                project.android.defaultConfig {
                    targetSdk = 33
                    targetSdk { version = release(33) }
                    targetSdk { version = preview('S') }

                    maxSdk = 33
                    maxSdk { version = release(33) }

                    minSdk = 33
                    minSdk { version = release(33) }
                }

            """.trimIndent()
        )
    }

    @Test
    fun testLegacyTargetSdkVersion() {

        android.defaultConfig {
            targetSdk = 33
            targetSdk {
                assertThat(version?.apiLevel).isEqualTo(33)
            }

            targetSdkVersion(20)
            targetSdk {
                assertThat(version?.apiLevel).isEqualTo(20)
            }

            targetSdkPreview = "S"
            targetSdk {
                assertThat(version?.apiLevel).isEqualTo(30)
                assertThat(version?.codeName).isEqualTo("S")
            }

            targetSdkVersion("Tiramisu")
            targetSdk {
                assertThat(version?.apiLevel).isEqualTo(32)
                assertThat(version?.codeName).isEqualTo("Tiramisu")
            }
        }
    }

    @Test
    fun testTargetSdkVersion() {
        android.defaultConfig.targetSdk {
            version = release(34)
        }
        android.defaultConfig.targetSdk {
            assertThat(version?.apiLevel).isEqualTo(34)
        }

        android.defaultConfig.targetSdk {
            version = preview("S")
            assertThat(version?.apiLevel).isEqualTo(30)
            assertThat(version?.codeName).isEqualTo("S")
        }
    }

    @Test
    fun testLegacyMaxSdkVersion() {

        android.defaultConfig {
            maxSdk = 34
            maxSdk {
                assertThat(version?.apiLevel).isEqualTo(34)
            }

            maxSdkVersion(33)
            maxSdk {
                assertThat(version?.apiLevel).isEqualTo(33)
            }
        }
    }

    @Test
    fun testMaxSdkVersion() {
        android.defaultConfig.maxSdk {
            version = release(34)
            assertThat(version?.apiLevel).isEqualTo(34)
        }
    }

    @Test
    fun testResourceConfigurations() {
        Eval.me(
            "project",
            project,
            ("""project.android {
    flavorDimensions += ['fruit']
    defaultConfig {
        resourceConfigurations += ['en']
    }
    productFlavors {
        orange {
            resourceConfigurations += ['de']
        }
    }
}
""")
        )
        plugin.createAndroidTasks(project)

        Truth.assertThat(android.defaultConfig.resourceConfigurations).containsExactly("en")

        Truth.assertThat(android.productFlavors.getByName("orange").resourceConfigurations)
            .containsExactly("de")
    }

    private fun checkGeneratedDensities(taskName: String, vararg densities: String) {
        val mergeResources = getTask(
            taskName,
            MergeResources::class.java
        )
        Truth.assertThat(mergeResources.generatedDensities)
            .containsExactlyElementsIn(Arrays.asList(*densities))
    }

    protected fun <T> getTask(name: String, @Suppress("unused") klass: Class<T>?): T {
        return project.tasks.getByName(name) as T
    }


    companion object {
        const val PROGUARD_DEBUG: String = "minifyDebugWithProguard"
        const val R8_DEBUG: String = "minifyDebugWithR8"
        const val R8_RELEASE: String = "minifyReleaseWithR8"
        const val R8_DEBUG_ANDROID_TEST: String = "minifyDebugAndroidTestWithR8"

        private const val DEFAULT_DEBUG = R8_DEBUG
        private const val DEFAULT_DEBUG_ANDROID_TEST = R8_DEBUG_ANDROID_TEST
        private const val DEFAULT_RELEASE = R8_RELEASE

        init {
            importOfflineMavenRepo()
        }
    }
}
