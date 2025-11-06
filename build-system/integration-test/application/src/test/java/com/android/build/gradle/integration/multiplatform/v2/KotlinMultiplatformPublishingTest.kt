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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition.Companion.DEFAULT_COMPILE_SDK_VERSION
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.KotlinMultiplatformCallback
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.utils.disableBuiltInKotlin
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class KotlinMultiplatformPublishingTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withGradleOptions {
            withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        }.from {
            settings {
                addRepository("repo")
            }
            // Simple producer using new AGP-KMP plugin, exposing android and common target by default
            androidKotlinMultiplatformLibrary(":producer") {
                applyPlugin(PluginType.MAVEN_PUBLISH)
                group = "com.example.producer"
                version = "1.0"

                android {
                    namespace = "com.example.producer"
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                }
                pluginCallbacks += PublisherCallback::class.java
            }
        }

    @Test
    fun `test AGP-KMP consumer`() {
        val build = rule.build {
            androidKotlinMultiplatformLibrary(":consumer") {
                group = "com.example.consumer"
                version = "1.0"

                android {
                    namespace = "com.example.consumer"
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                }

                pluginCallbacks += AndroidDependencyCallback::class.java
            }
        }
        build.executor
            .withFailOnWarning(false) // b/455891987
            .run(":producer:publish")
        var buildResult = build.executor
            .withFailOnWarning(false) // b/455891987
            .run(
            ":consumer:dependencyInsight",
            "--configuration", "androidCompileClasspath",
            "--dependency", "com.example.producer:producer:1.0"
        )
        ScannerSubject.assertThat(buildResult.stdout).contains("Variant androidApiElements-published")

        simulateDifferentProducerArtifact(build)
        buildResult = build.executor
            .withFailOnWarning(false) // b/455891987
            .run(
            ":consumer:dependencyInsight",
            "--configuration", "androidCompileClasspath",
            "--dependency", "com.example.producer:producer:1.0"
        )
        ScannerSubject.assertThat(buildResult.stdout).contains("Variant androidApiElements-published")
    }

    @Test
    fun `test kmp and com_android_library consumer`() {
        val build = rule.build {
            androidLibrary(":oldKmpConsumer") {
                applyPlugin(PluginType.KOTLIN_MPP)

                android {
                    namespace = "com.example.oldKmpConsumer"
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                    defaultConfig.minSdk = 24
                }
                pluginCallbacks += AndroidDependencyCallback::class.java
                pluginCallbacks += EnableAndroidTargetCallback::class.java
            }
            disableBuiltInKotlin()
        }
        build.executor
            .withFailOnWarning(false) // b/455891987
            .run(":producer:publish")
        var buildResult = build.executor
            .withFailOnWarning(false) // b/455891987
            .run(
            ":oldKmpConsumer:dependencyInsight",
            "--configuration", "androidDebugCompileClasspath",
            "--dependency", "com.example.producer:producer:1.0"
        )
        ScannerSubject.assertThat(buildResult.stdout).contains("Variant androidApiElements-published")

        simulateDifferentProducerArtifact(build)
        buildResult = build.executor
            .withFailOnWarning(false) // b/455891987
            .run(
            ":oldKmpConsumer:dependencyInsight",
            "--configuration", "androidDebugCompileClasspath",
            "--dependency", "com.example.producer:producer:1.0"
        )
        ScannerSubject.assertThat(buildResult.stdout).contains("Variant androidApiElements-published")
    }

    @Test
    fun `test android library consumer`() {
        val build = rule.build {
            androidLibrary(":plainAndroidLibConsumer") {
                android {
                    namespace = "com.example.plainAndroidLibConsumer"
                    compileSdk = DEFAULT_COMPILE_SDK_VERSION
                    defaultConfig.minSdk = 24
                }
                dependencies {
                    implementation("com.example.producer:producer:1.0")
                }
            }
        }
        build.executor
            .withFailOnWarning(false) // b/455891987
            .run(":producer:publish")
        var buildResult = build.executor
            .withFailOnWarning(false) // b/455891987
            .run(
            ":plainAndroidLibConsumer:dependencyInsight",
            "--configuration", "debugCompileClasspath",
            "--dependency", "com.example.producer:producer:1.0"
        )
        ScannerSubject.assertThat(buildResult.stdout).contains("Variant androidApiElements-published")
        simulateDifferentProducerArtifact(build)
        buildResult = build.executor
            .withFailOnWarning(false) // b/455891987
            .run(
            ":plainAndroidLibConsumer:dependencyInsight",
            "--configuration", "debugCompileClasspath",
            "--dependency", "com.example.producer:producer:1.0"
        )
        ScannerSubject.assertThat(buildResult.stdout).contains("Variant androidApiElements-published")
    }

    /**
     * Here we have a consumer with jvm + common targets consuming an
     * artifact with android + common targets. And the androidApiElements variant is successfully
     * resolved which is wrong. jvmMain cannot consume androidMain
     */
    @Test
    fun `test kmp consumer without android target`() {
        val build = rule.build {
            kotlinMultiplatformLibrary(":kmpWithoutAndroidTargetConsumer") {
                kotlin {
                    jvm()
                }
                pluginCallbacks += CommonDependencyCallback::class.java
            }
        }
        build.executor
            .withFailOnWarning(false) // b/455891987
            .run(":producer:publish")
        // the build succeeds but the Gradle resolution actually fails here
        val buildResult = build.executor
            .withFailOnWarning(false) // b/455891987
            .run(
            ":kmpWithoutAndroidTargetConsumer:dependencyInsight",
            "--configuration", "jvmCompileClasspath",
            "--dependency", "com.example.producer:producer:1.0"
        )
        ScannerSubject.assertThat(buildResult.stdout)
            .contains("No matching variant of com.example.producer:producer:1.0 was found")
    }

    @Test
    fun `test kmp consumer matching jvm target from producer`() {
        val build = rule.build {
            kotlinMultiplatformLibrary(":producer") {
                kotlin {
                    jvm() // add jvm target to the producer
                }
            }
            kotlinMultiplatformLibrary(":kmpWithJvmTargetConsumer") {
                kotlin {
                    jvm()
                }
                pluginCallbacks += CommonDependencyCallback::class.java
            }
        }
        build.executor
            .withFailOnWarning(false) // b/455891987
            .run(":producer:publish")
        val buildResult = build.executor
            .withFailOnWarning(false) // b/455891987
            .run(
            ":kmpWithJvmTargetConsumer:dependencyInsight",
            "--configuration", "jvmCompileClasspath",
            "--dependency", "com.example.producer:producer:1.0"
        )
        ScannerSubject.assertThat(buildResult.stdout).contains("Variant jvmApiElements-published")
    }

    /**
     * This update the metadata file changes the org.jetbrains.kotlin.platform.type attribute
     * in all publication configurations from androidJvm to jvm in order to simulate consuming
     * AGP-KMP library published with "jvm" value
     */
    fun simulateDifferentProducerArtifact(build: GradleBuild) {
        val gradleModuleFile =
            build.directory.resolve("repo/com/example/producer/producer/1.0/producer-1.0.module")

        val toReplace = "\"org.jetbrains.kotlin.platform.type\": \"androidJvm\""
        val replacement = "\"org.jetbrains.kotlin.platform.type\": \"jvm\""
        replaceInFile(gradleModuleFile, toReplace, replacement)
    }

    fun replaceInFile(path: Path, oldString: String, newString: String) {
        if (!path.exists()) {
            println("Error: File does not exist at path: $path")
            return
        }

        val originalContent = path.readText()
        val modifiedContent = originalContent.replace(oldString, newString)
        path.writeText(modifiedContent)
    }
}

class AndroidDependencyCallback : KotlinMultiplatformCallback {
    override fun handleExtension(
        project: Project,
        extension: KotlinMultiplatformExtension
    ) {
        extension.apply {
            sourceSets.androidMain.dependencies {
                implementation("com.example.producer:producer:1.0")
            }
        }
    }
}

class CommonDependencyCallback : KotlinMultiplatformCallback {
    override fun handleExtension(
        project: Project,
        extension: KotlinMultiplatformExtension
    ) {
        extension.apply {
            sourceSets.commonMain.dependencies {
                implementation("com.example.producer:producer:1.0")
            }
        }
    }
}

class EnableAndroidTargetCallback : GenericCallback {
    override fun handleProject(project: Project) {
        val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)

        kotlin.apply {
            androidTarget()
        }
    }
}

class PublisherCallback : GenericCallback {
    override fun handleProject(project: Project) {
        val publishing = project.extensions.findByType(PublishingExtension::class.java)
            ?: throw RuntimeException("Could not find extension of type PublishingExtension")

        publishing.apply {
            repositories {
                it.maven {
                    it.url = project.uri(project.projectDir.parentFile.resolve("repo"))
                }
            }
        }
    }
}
