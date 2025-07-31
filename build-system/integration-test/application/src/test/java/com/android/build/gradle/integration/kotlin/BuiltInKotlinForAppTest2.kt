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

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_LIB_PATH
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.testutils.truth.PathSubject
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.Rule
import org.junit.Test

class BuiltInKotlinForAppTest2 {

    @get:Rule
    val rule = GradleRule.from {
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        }
    }

    @Test
    fun testBuiltInKotlinSupportAndKagpUsedInDifferentModules() {
        val build = rule.build {
            androidLibrary {
                applyPlugin(PluginType.KOTLIN_ANDROID)
                kotlin {
                    compilerOptions.jvmTarget.set(JvmTarget.JVM_1_8)
                }
                files.add(
                    "src/main/java/LibFoo.kt",
                    //language=kotlin
                    """
                        package com.foo.library
                        class LibFoo
                    """.trimIndent())
            }
            androidApplication {
                dependencies {
                    api(project(DEFAULT_LIB_PATH))
                }

                files.add(
                    "src/main/kotlin/AppFoo.kt",
                    //language=kotlin
                    """
                        package com.foo.application
                        val l = com.foo.library.LibFoo()
                    """.trimIndent())
            }
        }

        build.executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .run(":app:assembleDebug")
        build.androidApplication().assertApk(ApkSelector.DEBUG) {
            classes().containsExactly(
                "com/foo/application/AppFooKt",
                "com/foo/library/LibFoo",
                "pkg/name/app/R",
                "pkg/name/lib/R",
                "kotlin/",
                "org/intellij/",
                "org/jetbrains/"
            )
        }
    }

    @Test
    fun testKotlinCompilerOptionsDsl() {
        val build = rule.build {
            androidApplication {
                // Add some kotlin code so that `compileDebugKotlin` task isn't skipped.
                files.add(
                    "src/main/kotlin/KotlinAppFoo.kt",
                    //language=kotlin
                    """
                        package com.foo.application
                        class KotlinAppFoo
                    """.trimIndent()
                )
                // Set some values in the built-in Kotlin DSL and check that the values flow to the task
                kotlin {
                    compilerOptions {
                        moduleName.set("foo")
                        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
                    }
                }

                pluginCallbacks += KotlinTaskCallback::class.java
            }
        }

        val result = build.executor.run(":app:compileDebugKotlin")
        assertThat(result.didWorkTasks).contains(":app:compileDebugKotlin")
    }

    class KotlinTaskCallback: ApplicationComponentCallback {
        override fun handleExtension(
            project: Project,
            androidComponents: ApplicationAndroidComponentsExtension
        ) {
            project.afterEvaluate {
                project.tasks.named("compileDebugKotlin") {
                    it.doLast { task ->
                        task as KotlinCompile
                        val moduleName = task.compilerOptions.moduleName.get()
                        if (moduleName != "foo") {
                            throw RuntimeException("Unexpected module name: $moduleName")
                        }
                        val languageVersion = task.compilerOptions.languageVersion.get()
                        if (languageVersion != org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9) {
                            throw RuntimeException("Unexpected app language version: $languageVersion")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun testKotlinSourceSets() {
        val build = rule.build {
            androidApplication {
                // Add some custom source directories.
                files {
                    add(
                        "src/fooMain/kotlin/FooMain.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class FooMain {}
                        """.trimIndent()
                    )
                    add(
                        "src/fooDebug/kotlin/FooDebug.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class FooDebug {}
                        """.trimIndent()
                    )
                    add(
                        "src/fooAndroidTest/kotlin/FooAndroidTest.kt",
                        //language=kotlin
                        """
                            package com.foo.application
                            class FooAndroidTest {}
                        """.trimIndent()
                    )
                }
                // Add the custom source directories to the source sets.
                kotlin {
                    sourceSets {
                        create("main") {
                            it.kotlin.srcDir("src/fooMain/kotlin")
                        }
                        create("debug") {
                            it.kotlin.srcDir("src/fooDebug/kotlin")
                        }
                        create("androidTest") {
                            it.kotlin.srcDir("src/fooAndroidTest/kotlin")
                        }
                    }
                }
            }
        }

        // Run Kotlin compilation tasks and check that the expected class files are created.
        build.executor.run(":app:compileDebugKotlin", ":app:compileDebugAndroidTestKotlin")

        val kotlincOutputDir = build.androidApplication()
            .resolve(InternalArtifactType.BUILT_IN_KOTLINC)
        PathSubject.assertThat(kotlincOutputDir).exists()

        val fooMainClassFile = kotlincOutputDir.resolve(
            "debug/compileDebugKotlin/classes/com/foo/application/FooMain.class"
        )
        PathSubject.assertThat(fooMainClassFile).exists()

        val fooDebugClassFile = kotlincOutputDir.resolve(
                "debug/compileDebugKotlin/classes/com/foo/application/FooDebug.class"
            )
        PathSubject.assertThat(fooDebugClassFile).exists()

        val fooAndroidTestClassFile = kotlincOutputDir.resolve(
                "debugAndroidTest/compileDebugAndroidTestKotlin/classes/com/foo/application/FooAndroidTest.class"
            )
        PathSubject.assertThat(fooAndroidTestClassFile).exists()
    }
}
