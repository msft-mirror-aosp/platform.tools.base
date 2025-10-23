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
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.testutils.TestUtils
import com.android.testutils.TestUtils.BUILT_IN_KOTLIN_VERSION
import com.google.common.truth.Truth.assertThat
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.junit.Rule
import org.junit.Test

class BuiltInKotlinAutomaticStdlibTest {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            HelloWorldAndroid.setupKotlin(files)
        }
    }

    @Test
    fun `test kotlin-stdlib automatically added`() {
        val result =
            rule.build
                .executor
                .run(":app:dependencies", "--configuration", "debugCompileClasspath")
        result.assertOutputContains("--- org.jetbrains.kotlin:kotlin-stdlib:$BUILT_IN_KOTLIN_VERSION")
    }

    @Test
    fun `test kotlin-stdlib added by user`() {
        val build = rule.build {
            androidApplication {
                dependencies {
                    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
                }
            }
        }
        val result =
            build.executor
                .run(":app:dependencies", "--configuration", "debugCompileClasspath")
        result.assertOutputContains("--- org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.9.24")
    }

    /** Regression test for b/452246814 */
    @Test
    fun `test kotlin-stdlib automatically added and appears in Gradle module metadata`() {
        val build = rule.build {
            androidLibrary {
                pluginCallbacks += MavenPublishPluginCallback::class.java
                applyPlugin(PluginType.MAVEN_PUBLISH)
                android {
                    publishing {
                        singleVariant("release")
                    }
                }
            }
        }

        build.executor.run(":lib:generateMetadataFileForMavenPublication")

        val moduleMetadata = build.androidLibrary().buildDir.resolve("publications/maven/module.json").toFile().readText()
        val variants = JsonParser.parseString(moduleMetadata).asJsonObject.getAsJsonArray("variants")

        fun JsonArray.getVariant(variantName: String): JsonObject = single {
            it.asJsonObject["name"].asString == variantName
        } as JsonObject

        fun JsonObject.assertContainsKotlinStdlib() {
            val kotlinStdlibDep = getAsJsonArray("dependencies").find {
                it is JsonObject && it.asJsonObject.get("module").asString == "kotlin-stdlib"
            }
            assertThat(kotlinStdlibDep).isNotNull()
        }

        variants.getVariant("releaseVariantReleaseApiPublication").assertContainsKotlinStdlib()
        variants.getVariant("releaseVariantReleaseRuntimePublication").assertContainsKotlinStdlib()
    }

    @Test
    fun `test kotlin_stdlib_default_dependency=false`() {
        val build =
            rule.build {
                gradleProperties {
                    add("kotlin.stdlib.default.dependency", "false")
                }
            }
        val result =
            build.executor
                .run(":app:dependencies", "--configuration", "debugCompileClasspath")
        result.assertOutputDoesNotContain("--- org.jetbrains.kotlin:kotlin-stdlib:$BUILT_IN_KOTLIN_VERSION")
    }

    /** Regression test for b/443037365. */
    @Test
    fun `test kotlin_stdlib_default_dependency=false and user adds kotlin-stdlib without version`() {
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
    fun `test kotlin_stdlib_default_dependency=false and user adds kotlin-stdlib without version, check no warnings when published`() {
        val build = rule.build {
            androidLibrary {
                pluginCallbacks += MavenPublishPluginCallback::class.java
                applyPlugin(PluginType.MAVEN_PUBLISH)
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
        project.extensions.getByType(PublishingExtension::class.java).apply {
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
