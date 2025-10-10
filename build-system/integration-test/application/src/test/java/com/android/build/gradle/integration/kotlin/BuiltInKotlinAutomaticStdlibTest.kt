/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.build.gradle.integration.kotlin

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.testutils.TestUtils
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.junit.Rule
import org.junit.Test

class BuiltInKotlinAutomaticStdlibTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)

            HelloWorldAndroid.setupKotlin(files)
        }
    }

    @Test
    fun testKotlinStdlibAutomaticallyAdded() {
        val result =
            rule.build
                .executor
                .run(":app:dependencies", "--configuration", "debugCompileClasspath")
        result.assertOutputContains(
            "--- org.jetbrains.kotlin:kotlin-stdlib:2.2.10"
        )
    }

    @Test
    fun testKotlinStdlibAddedByUser() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    // This version number should not be changed when upgrading Kotlin. If it must
                    // be changed, it should be set to a version other than KOTLIN_VERSION_FOR_TESTS
                    // to test that this version is used instead of KOTLIN_VERSION_FOR_TESTS.
                    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
                }
            }
        }
        val result =
            build.executor
                .run(":app:dependencies", "--configuration", "debugCompileClasspath")
        result.assertOutputContains("--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
        result.assertOutputDoesNotContain(
            "--- org.jetbrains.kotlin:kotlin-stdlib:${TestUtils.KOTLIN_VERSION_FOR_TESTS}"
        )
    }

    @Test
    fun testKotlinStdlibDefaultDependencyFalse() {
        val build =
            rule.build {
                gradleProperties {
                    add("kotlin.stdlib.default.dependency", "false")
                }
            }
        val result =
            build.executor
                .run(":app:dependencies", "--configuration", "debugCompileClasspath")
        result.assertOutputDoesNotContain(
            "--- org.jetbrains.kotlin:kotlin-stdlib:${TestUtils.KOTLIN_VERSION_FOR_TESTS}"
        )
    }

    /** Regression test for b/443037365. */
    @Test
    fun testKotlinStdlibWithoutVersion() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    implementation("org.jetbrains.kotlin:kotlin-stdlib")
                }
            }
            gradleProperties {
                add("kotlin.stdlib.default.dependency", "false")
            }
        }
        val result = build.executor.run(":app:dependencies", "--configuration", "debugCompileClasspath")
        result.assertOutputContains("--- org.jetbrains.kotlin:kotlin-stdlib -> 2.2.10")
    }

    /** Regression test for b/450851465. */
    @Test
    fun testKotlinStdlibWithoutVersionAndInLibraryPom() {
        val build = rule.build {
            androidLibrary {
                pluginCallbacks += MavenPublishPluginCallback::class.java
                applyPlugin(PluginType.MAVEN_PUBLISH)
                applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
                android {
                    publishing {
                        singleVariant("release")
                    }
                }
                dependencies {
                    implementation("org.jetbrains.kotlin:kotlin-stdlib")
                }
            }
            gradleProperties {
                add("kotlin.stdlib.default.dependency", "false")
            }
        }

        val result = build.executor.run(":lib:generatePomFileForMavenPublication")
        result.assertOutputDoesNotContain("suppressPomMetadataWarningsFor")
    }
}

private class MavenPublishPluginCallback: GenericCallback {
    override fun handleProject(project: Project) {

        val publishing = project.extensions.getByType(PublishingExtension::class.java)
            ?: throw RuntimeException("Could not find extension of type PublishingExtension")

        publishing.apply {
            publications.register("maven", MavenPublication::class.java) { publication ->
                publication.groupId = "com.android"
                publication.artifactId = "lib"
                publication.version = "1.0"

                repositories { repo ->
                    repo.maven {
                        it.url = project.uri(project.projectDir.resolve("build/testRepo"))
                        it.name = "buildDir"
                    }
                }
                project.afterEvaluate {
                    publication.from(project.components.getByName("release"))
                }
            }
        }
    }
}
