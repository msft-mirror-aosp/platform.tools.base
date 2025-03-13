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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.output.AarSubject
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.testutils.apk.Apk
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

@Suppress("PathAsIterable")
class KotlinMultiplatformAndroidVariantApiTest {
    @get:Rule
    val project = GradleTestProjectBuilder()
        .fromTestProject("kotlinMultiplatform")
        .create()

    @Test
    fun testInstrumentedTestDependencySubstitution() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            """
                androidComponents {
                    onVariants { variant ->
                        variant.nestedComponents.forEach { component ->
                            println(variant.name + ":" + component.name)
                        }
                    }
                }
            """.trimIndent()
        )

        val result = project.executor().run(":kmpFirstLib:assemble")

        ScannerSubject.assertThat(result.stdout).contains("androidMain:androidHostTest")
        ScannerSubject.assertThat(result.stdout).contains("androidMain:androidDeviceTest")
    }

    @Test
    fun testAddGeneratedAssets() {
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            // language=kotlin
            """
                kotlin.androidLibrary {
                    experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true
                }

                abstract class CreateAssets: DefaultTask() {

                    @get:OutputDirectory
                    abstract val outputFolder: DirectoryProperty

                    @TaskAction
                    fun taskAction() {
                        val outputFile = File(outputFolder.asFile.get(), "asset.txt")
                        println(outputFile)
                        outputFile.parentFile.mkdirs()
                        outputFile.writeText("foo")
                    }
                }

                androidComponents {
                    onVariants { variant ->
                        val createAssetsTaskProvider = project.tasks.register<CreateAssets>("${'$'}{variant.name}AddAssets") {
                            outputFolder.set(
                                File(project.layout.buildDirectory.asFile.get(), "assets/gen")
                            )
                        }
                        variant.sources
                            .assets
                            ?.addGeneratedSourceDirectory(
                                createAssetsTaskProvider,
                                CreateAssets::outputFolder
                            )

                        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(
                            createAssetsTaskProvider,
                            CreateAssets::outputFolder
                        )
                    }
                }
            """.trimIndent()
        )

        project.executor().run(":kmpFirstLib:assembleAndroidMain")

        val aarFile = project.getSubproject("kmpFirstLib").getOutputFile("aar", "kmpFirstLib.aar").toPath()
        AarSubject.assertThat(aarFile) {
            assets().containsExactly("asset.txt")
        }

        project.executor().run(":kmpFirstLib:assembleDeviceTest")
        val testApk = project.getSubproject("kmpFirstLib").getOutputFile(
            "apk", "androidTest", "main", "kmpFirstLib-androidTest.apk"
        )
        assertThat(testApk.exists()).isTrue()

        Apk(testApk).use { apk ->
            assertThat(apk.getEntry("assets/asset.txt")).isNotNull()
        }
    }

    @Test
    fun testVariantApkOutput() {
    TestFileUtils.prependToFile(project.getSubproject("kmpFirstLib").ktsBuildFile,
        //language=kotlin
            """
                import com.android.build.api.variant.ApkOutput
                import com.android.build.api.variant.DeviceSpec
                import org.gradle.api.DefaultTask
                import org.gradle.api.GradleException
                import org.gradle.api.tasks.Internal
                import org.gradle.api.tasks.TaskAction
                import org.gradle.api.provider.Property
            """.trimIndent())
        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            // language=kotlin
            """
                abstract class FetchApkTask : DefaultTask() {
                    @get:Internal
                    abstract val privacySandboxEnabledApkOutput: Property<ApkOutput>

                    @get:Internal
                    abstract val privacySandboxDisabledApkOutput: Property<ApkOutput>

                    @TaskAction
                    fun execute() {
                        var apkInstall = privacySandboxEnabledApkOutput.get().apkInstallGroups
                        if (apkInstall.size != 1 || apkInstall[0].apks.size != 1) {
                            throw GradleException("Unexpected number of apks")
                        }
                        assert(apkInstall[0].apks.first().asFile.name.contains("kmpFirstLib-androidTest.apk"))
                        assert(apkInstall[0].description.contains("Testing Apk"))

                        apkInstall = privacySandboxDisabledApkOutput.get().apkInstallGroups
                        if (apkInstall.size != 1 || apkInstall[0].apks.size != 1) {
                            throw GradleException("Unexpected number of apks")
                        }
                        assert(apkInstall[0].apks.first().asFile.name.contains("kmpFirstLib-androidTest.apk"))
                        assert(apkInstall[0].description.contains("Testing Apk"))
                    }
                }
                val taskProvider = tasks.register("fetchApks", FetchApkTask::class.java)
                androidComponents {
                    onVariants { variant ->
                        variant.androidTest?.let {
                            it.outputProviders.provideApkOutputToTask(
                                taskProvider,
                                FetchApkTask::privacySandboxEnabledApkOutput,
                                DeviceSpec.Builder()
                                    .setName("testDevice")
                                    .setApiLevel(34)
                                    .setCodeName("")
                                    .setAbis(listOf())
                                    .setSupportsPrivacySandbox(true)
                                    .build())
                        it.outputProviders.provideApkOutputToTask(
                            taskProvider,
                            FetchApkTask::privacySandboxDisabledApkOutput,
                            DeviceSpec.Builder()
                                .setName("testDevice")
                                .setApiLevel(34)
                                .setCodeName("")
                                .setAbis(listOf())
                                .setSupportsPrivacySandbox(false)
                                .build())
                        }
                    }
                }
            """.trimIndent()
        )

        project.executor().run(":kmpFirstLib:fetchApks")
    }

    @Test
    fun testStaticAssets() {
        FileUtils.createFile(
            project.getSubproject("kmpFirstLib").file("src/assets/static.txt"),
            "foo"
        )

        TestFileUtils.appendToFile(
            project.getSubproject("kmpFirstLib").ktsBuildFile,
            // language=kotlin
            """
                kotlin.androidLibrary {
                    experimentalProperties["android.experimental.kmp.enableAndroidResources"] = true
                }

                androidComponents {
                    onVariants { variant ->
                        variant.androidTest?.sources?.assets?.addStaticSourceDirectory("src/assets")
                    }
                }
            """.trimIndent()
        )

        project.executor().run(":kmpFirstLib:assembleDeviceTest")
        val testApk = project.getSubproject("kmpFirstLib").getOutputFile(
            "apk", "androidTest", "main", "kmpFirstLib-androidTest.apk"
        )
        assertThat(testApk.exists()).isTrue()

        Apk(testApk).use { apk ->
            assertThat(apk.getEntry("assets/static.txt")).isNotNull()
        }
    }
}
