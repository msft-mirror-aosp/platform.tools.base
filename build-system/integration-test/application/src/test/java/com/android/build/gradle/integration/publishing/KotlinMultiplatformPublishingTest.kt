/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.build.gradle.integration.publishing

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.model.normalizeVersionsOfCommonDependencies
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.junit.Rule
import org.junit.Test
import java.nio.file.Path
import kotlin.io.path.readText

/** Test expected publishing output when AGP is used in Kotlin MPP projects. */
class KotlinMultiplatformPublishingTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withGradleOptions {
            // this is necessary because KMP does not work with project Isolation.
            // There were some tests where it worked but that's because they used the root
            // project, and it's fine in the root (the KMP plugin accesses things in the root
            // folder so if it's already there it's fine)
            withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        }.from {
            androidLibrary {
                applyPlugin(PluginType.KOTLIN_MPP)
                applyPlugin(PluginType.MAVEN_PUBLISH)
                pluginCallbacks += Callback::class.java

                android {
                    defaultConfig.minSdk = 24

                    group = "com.example"
                    version = "0.1.2"
                }
            }
        }

    class Callback: GenericCallback {
        override fun handleProject(project: Project) {
            val publishing = project.extensions.findByType(PublishingExtension::class.java)
                ?: throw RuntimeException("Could not find extension of type PublishingExtension")

            publishing.apply {
                repositories {
                    it.maven {
                        it.url = project.uri(project.projectDir.resolve("build/testRepo"))
                        it.name = "buildDir"
                    }
                }
            }

            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)

            kotlin.apply {
                androidTarget { target ->
                    target.publishAllLibraryVariants()
                }
            }
        }
    }

    @Test
    fun testKotlinMultiplatform() {
        val build = rule.build
        val lib = build.androidLibrary()

        build.executor.run("publishAllPublicationsToBuildDirRepository")

        val mainModule =
            lib.buildDir.resolve("testRepo/com/example/lib/0.1.2/lib-0.1.2.module")
        val androidModule =
            lib.buildDir.resolve("testRepo/com/example/lib-android/0.1.2/lib-android-0.1.2.module")
        val androidDebugModule =
            lib.buildDir.resolve("testRepo/com/example/lib-android-debug/0.1.2/lib-android-debug-0.1.2.module")

        assertThat(normalizeModuleFile(mainModule)).isEqualTo(getExpectedFile("lib.module"))
        assertThat(normalizeModuleFile(androidModule)).isEqualTo(getExpectedFile("lib-android.module"))
        assertThat(normalizeModuleFile(androidDebugModule)).isEqualTo(getExpectedFile("lib-android-debug.module"))
    }

    private fun getExpectedFile(fileName: String): String {
        return KotlinMultiplatformPublishingTest::class.java.let { klass ->
            klass.getResourceAsStream("${klass.simpleName}/$fileName")!!.reader().use {
                it.readText().trim()
            }
        }
    }

    private fun normalizeModuleFile(path: Path): String {
        val original = path.readText().trim()
        return original.normalizeVersionsOfCommonDependencies()
            .replace(Regex("\"sha512\": \".*\""), "\"sha512\": \"{DIGEST}\"")
            .replace(Regex("\"sha256\": \".*\""), "\"sha256\": \"{DIGEST}\"")
            .replace(Regex("\"sha1\": \".*\""), "\"sha1\": \"{DIGEST}\"")
            .replace(Regex("\"md5\": \".*\""), "\"md5\": \"{DIGEST}\"")
            .replace(Regex("\"size\": .*,"), "\"size\": {SIZE},")
    }
}
