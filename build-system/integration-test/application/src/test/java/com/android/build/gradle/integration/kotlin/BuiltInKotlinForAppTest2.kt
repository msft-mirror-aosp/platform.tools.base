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

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleTestProject.ApkType.Companion.DEBUG
import com.android.build.gradle.integration.common.fixture.testprojects.PluginType
import com.android.build.gradle.integration.common.fixture.testprojects.createGradleProjectBuilder
import com.android.build.gradle.integration.common.fixture.testprojects.prebuilts.setUpHelloWorld
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.integration.common.utils.TestFileUtils
import org.junit.Rule
import org.junit.Test

class BuiltInKotlinForAppTest2 {

    @get:Rule
    val project = createGradleProjectBuilder {
        subProject(":app") {
            plugins.add(PluginType.ANDROID_APP)
            plugins.add(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android {
                setUpHelloWorld()
            }
            dependencies {
                api(project(":lib"))
            }
            appendToBuildFile { builtInKotlinSupportDependencies }
        }
        subProject(":lib") {
            plugins.add(PluginType.ANDROID_LIB)
            plugins.add(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android {
                setUpHelloWorld()
            }
            appendToBuildFile { builtInKotlinSupportDependencies }
        }
    }.withBuiltInKotlinSupport(true)
        .create()

    @Test
    fun testBuiltInKotlinSupportAndKagpUsedInDifferentModules() {
        val lib = project.getSubproject(":lib")
        TestFileUtils.searchAndReplace(
            lib.buildFile,
            PluginType.ANDROID_BUILT_IN_KOTLIN.id,
            PluginType.KOTLIN_ANDROID.id
        )
        TestFileUtils.appendToFile(
            lib.buildFile,
            """
                android.kotlinOptions.jvmTarget = "1.8"
                """.trimIndent()
        )
        lib.getMainSrcDir("java")
            .resolve("LibFoo.kt")
            .let {
                it.parentFile.mkdirs()
                it.writeText(
                    """
                        package com.foo.library
                        class LibFoo
                        """.trimIndent()
                )
            }
        val app = project.getSubproject(":app")
        app.getMainSrcDir("kotlin")
            .resolve("AppFoo.kt")
            .let {
                it.parentFile.mkdirs()
                it.writeText(
                    """
                        package com.foo.application
                        val l = com.foo.library.LibFoo()
                        """.trimIndent()
                )
            }
        project.executor().withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .run(":app:assembleDebug")
        app.getApk(DEBUG).use {
            assertThat(it).hasClass("Lcom/foo/application/AppFooKt;")
            assertThat(it).hasClass("Lcom/foo/library/LibFoo;")
        }
    }

    @Test
    fun testKotlinCompilerOptionsDsl() {
        val app = project.getSubproject(":app")
        // Add some kotlin code so that `compileDebugKotlin` task isn't skipped.
        app.getMainSrcDir("kotlin")
            .resolve("KotlinAppFoo.kt")
            .let {
                it.parentFile.mkdirs()
                it.writeText(
                    """
                        package com.foo.application
                        class KotlinAppFoo
                        """.trimIndent()
                )
            }
        // Set some values in the built-in Kotlin DSL and check that the values flow to the task
        TestFileUtils.appendToFile(
            app.buildFile,
            // language=groovy
            """
                kotlin {
                    compilerOptions {
                        moduleName.set("foo")
                        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
                    }
                }

                afterEvaluate {
                    tasks.named("compileDebugKotlin") {
                        doLast {
                            def moduleName = it.compilerOptions.moduleName.get()
                            if (moduleName != "foo") {
                                throw new RuntimeException("Unexpected module name: " + moduleName)
                            }
                            def languageVersion = it.compilerOptions.languageVersion.get()
                            if (languageVersion != org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9) {
                                throw new RuntimeException(
                                    "Unexpected app language version: " + languageVersion
                                )
                            }
                        }
                    }
               }
                """.trimIndent()
        )
        val result = project.executor().run(":app:compileDebugKotlin")
        assertThat(result.didWorkTasks).contains(":app:compileDebugKotlin")
    }
}
