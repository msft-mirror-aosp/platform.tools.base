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

package com.android.build.gradle.integration.kotlin

import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import org.junit.Rule
import org.junit.Test

/** Tests that built-in Kotlin support works when Kotlin compiler Gradle plugins are used. */
class BuiltInKotlinCompilerPluginTest {

    @get:Rule
    val rule = GradleRule.from {
        buildFileType = BuildFileType.KTS
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        }
    }

    @Test
    fun `test Kotlin compiler Gradle plugin is invoked`() {
        val build = rule.build
        build.addKotlinCompilerGradlePlugin()

        // Check Kotlin compiler Gradle plugin is invoked
        val result = build.executor.run(":app:compileDebugKotlin")
        result.assertOutputContains("Applying ExampleKotlinCompilerGradlePlugin to Kotlin compilation 'debug'")

        // Also check KotlinCompilation details
        result.assertOutputContains(
            """
            Details of KotlinCompilation 'debug':
            allAssociatedCompilations = BuiltInKotlinUnsupportedApiException
            allKotlinSourceSets = [[src/main/kotlin,src/main/java,src/debug/kotlin,src/debug/java]]
            apiConfigurationName = BuiltInKotlinUnsupportedApiException
            associateWith = BuiltInKotlinUnsupportedApiException
            associatedCompilations = BuiltInKotlinUnsupportedApiException
            compilationName = debug
            compileAllTaskName = BuiltInKotlinUnsupportedApiException
            compileDependencyConfigurationName = BuiltInKotlinUnsupportedApiException
            compileDependencyFiles = BuiltInKotlinUnsupportedApiException
            compileKotlinTask = BuiltInKotlinUnsupportedApiException
            compileKotlinTaskName = compileDebugKotlin
            compileKotlinTaskProvider = BuiltInKotlinUnsupportedApiException
            compileOnlyConfigurationName = BuiltInKotlinUnsupportedApiException
            compileTaskProvider = provider(task 'compileDebugKotlin', class org.jetbrains.kotlin.gradle.tasks.KotlinCompile)
            compilerOptions = BuiltInKotlinUnsupportedApiException
            defaultSourceSet = [src/main/kotlin,src/main/java,src/debug/kotlin,src/debug/java]
            defaultSourceSetName = debug
            disambiguatedName = debug
            extras = []
            getAttributes = BuiltInKotlinUnsupportedApiException
            getName = debug
            implementationConfigurationName = BuiltInKotlinUnsupportedApiException
            kotlinOptions = BuiltInKotlinUnsupportedApiException
            kotlinSourceSets = [[src/main/kotlin,src/main/java,src/debug/kotlin,src/debug/java]]
            output = BuiltInKotlinUnsupportedApiException
            platformType = androidJvm
            project = project ':app'
            runtimeDependencyConfigurationName = BuiltInKotlinUnsupportedApiException
            runtimeDependencyFiles = BuiltInKotlinUnsupportedApiException
            runtimeOnlyConfigurationName = BuiltInKotlinUnsupportedApiException
            target = target  (androidJvm)
            """.trimIndent()
        )
        // Check KotlinCompilation 'debugUnitTest' too as there could be some confusion around the
        // name of defaultSourceSet ('debugUnitTest') vs. the name of the source directories
        // ('testDebug/kotlin' and others).
        result.assertOutputContains(
            """
            Applying ExampleKotlinCompilerGradlePlugin to Kotlin compilation 'debugUnitTest'
            Details of KotlinCompilation 'debugUnitTest':
            allAssociatedCompilations = BuiltInKotlinUnsupportedApiException
            allKotlinSourceSets = [[src/test/kotlin,src/test/java,src/testDebug/kotlin,src/testDebug/java]]
            apiConfigurationName = BuiltInKotlinUnsupportedApiException
            associateWith = BuiltInKotlinUnsupportedApiException
            associatedCompilations = BuiltInKotlinUnsupportedApiException
            compilationName = debugUnitTest
            compileAllTaskName = BuiltInKotlinUnsupportedApiException
            compileDependencyConfigurationName = BuiltInKotlinUnsupportedApiException
            compileDependencyFiles = BuiltInKotlinUnsupportedApiException
            compileKotlinTask = BuiltInKotlinUnsupportedApiException
            compileKotlinTaskName = compileDebugUnitTestKotlin
            compileKotlinTaskProvider = BuiltInKotlinUnsupportedApiException
            compileOnlyConfigurationName = BuiltInKotlinUnsupportedApiException
            compileTaskProvider = provider(task 'compileDebugUnitTestKotlin', class org.jetbrains.kotlin.gradle.tasks.KotlinCompile)
            compilerOptions = BuiltInKotlinUnsupportedApiException
            defaultSourceSet = [src/test/kotlin,src/test/java,src/testDebug/kotlin,src/testDebug/java]
            defaultSourceSetName = debugUnitTest
            disambiguatedName = debugUnitTest
            extras = []
            getAttributes = BuiltInKotlinUnsupportedApiException
            getName = debugUnitTest
            implementationConfigurationName = BuiltInKotlinUnsupportedApiException
            kotlinOptions = BuiltInKotlinUnsupportedApiException
            kotlinSourceSets = [[src/test/kotlin,src/test/java,src/testDebug/kotlin,src/testDebug/java]]
            output = BuiltInKotlinUnsupportedApiException
            platformType = androidJvm
            project = project ':app'
            runtimeDependencyConfigurationName = BuiltInKotlinUnsupportedApiException
            runtimeDependencyFiles = BuiltInKotlinUnsupportedApiException
            runtimeOnlyConfigurationName = BuiltInKotlinUnsupportedApiException
            target = target  (androidJvm)
            """.trimIndent()
        )
    }

    private fun GradleBuild.addKotlinCompilerGradlePlugin() {
        val exampleKotlinCompilerGradlePlugin =
            // language=kotlin
            """
            class ExampleKotlinCompilerGradlePlugin : org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin {

                override fun isApplicable(kotlinCompilation: org.jetbrains.kotlin.gradle.plugin.KotlinCompilation<*>): Boolean = true

                override fun applyToCompilation(
                    kotlinCompilation: org.jetbrains.kotlin.gradle.plugin.KotlinCompilation<*>
                ): Provider<List<org.jetbrains.kotlin.gradle.plugin.SubpluginOption>> {
                    println("Applying ExampleKotlinCompilerGradlePlugin to Kotlin compilation '${'$'}{kotlinCompilation.name}'")
                    println("Details of KotlinCompilation '${'$'}{kotlinCompilation.name}':")

                    fun <T> printValue(value: T): String {
                        return when (value) {
                            is File -> value.relativeTo(kotlinCompilation.project.projectDir).invariantSeparatorsPath
                            is Iterable<*> -> value.joinToString(",", prefix = "[", postfix = "]") { printValue(it) }
                            is org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet -> printValue(value.kotlin.sourceDirectories)
                            else -> value.toString()
                        }
                    }

                    org.jetbrains.kotlin.gradle.plugin.KotlinCompilation::class.members.sortedBy { it.name }.forEach { member ->
                        if (member.visibility!!.name != "PUBLIC") return@forEach
                        if (member.parameters.size != 1) return@forEach
                        if (member.name == "hashCode" || member.name == "toString") return@forEach

                        val value = try {
                            member.call(kotlinCompilation)
                        } catch (e: java.lang.reflect.InvocationTargetException) {
                            if (e.cause is com.android.build.gradle.internal.BuiltInKotlinJvmAndroidCompilation.BuiltInKotlinUnsupportedApiException) {
                                com.android.build.gradle.internal.BuiltInKotlinJvmAndroidCompilation.BuiltInKotlinUnsupportedApiException::class.simpleName
                            } else {
                                throw e
                            }
                        }
                        println(member.name + " = " + printValue(value))
                    }

                    return kotlinCompilation.target.project.provider {
                        listOf(org.jetbrains.kotlin.gradle.plugin.SubpluginOption("exampleKey", "exampleValue"))
                    }
                }

                override fun getCompilerPluginId(): String = "com.example.example-kotlin-compiler-gradle-plugin"

                // Setting up an example Kotlin compiler plugin is a bit cumbersome, so this method
                // returns the Compose compiler plugin instead. This is okay because we are testing
                // the Kotlin compiler *Gradle plugin*, not the Kotlin compiler plugin.
                override fun getPluginArtifact(): org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact =
                    org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact(groupId = "org.jetbrains.kotlin", artifactId = "kotlin-compose-compiler-plugin-embeddable")
            }
            """.trimIndent()

        androidApplication().files.update("build.gradle.kts").append(
            exampleKotlinCompilerGradlePlugin + "\n\n" +
            "apply<ExampleKotlinCompilerGradlePlugin>()"
        )
    }

}
