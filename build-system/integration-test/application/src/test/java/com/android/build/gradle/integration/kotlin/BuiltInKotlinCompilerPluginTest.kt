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
import com.android.build.gradle.internal.dsl.ModulePropertyKey.BooleanWithDefault
import com.android.build.gradle.options.BooleanOption
import org.junit.Rule
import org.junit.Test

/** Tests that built-in Kotlin support works when Kotlin compiler Gradle plugins are used. */
class BuiltInKotlinCompilerPluginTest {

    @get:Rule
    val rule = GradleRule.from {
        buildFileType = BuildFileType.KTS
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android.experimentalProperties[BooleanWithDefault.SCREENSHOT_TEST.key] = true
        }
        gradleProperties {
            add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
        }
    }

    @Test
    fun `test Kotlin compiler Gradle plugin is invoked`() {
        val build = rule.build
        build.addKotlinCompilerGradlePlugin()

        // Check Kotlin compiler Gradle plugin is invoked
        val result = build.executor.run(":app:help")
        result.assertOutputContains("Applying ExampleKotlinCompilerGradlePlugin to Kotlin compilation 'debug'")

        // Also check KotlinCompilation details
        result.assertOutputContains("KotlinAndroidTarget.compilations = [debug, debugAndroidTest, debugUnitTest, release, releaseUnitTest]")
        result.assertOutputContains(
            """
            Details of KotlinCompilation 'debug':
            allAssociatedCompilations = []
            allKotlinSourceSets = [[src/main/kotlin,src/main/java,src/debug/kotlin,src/debug/java]]
            apiConfigurationName = debugCompilationApi
            associateWith = []
            associatedCompilations = []
            compilationName = debug
            compileAllTaskName = debugClasses
            compileDependencyConfigurationName = debugCompileClasspath
            compileDependencyFiles = <can't resolve at this point as it is too early>
            compileKotlinTask = task ':app:compileDebugKotlin'
            compileKotlinTaskName = compileDebugKotlin
            compileKotlinTaskProvider = provider(task 'compileDebugKotlin', class org.jetbrains.kotlin.gradle.tasks.KotlinCompile)
            compileOnlyConfigurationName = debugCompilationCompileOnly
            compileTaskProvider = provider(task 'compileDebugKotlin', class org.jetbrains.kotlin.gradle.tasks.KotlinCompile)
            compilerOptions = org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.factory.KotlinJvmCompilerOptionsFactory${"$"}create${"$"}compilerOptions$1@<hash-code>
            defaultSourceSet = [src/main/kotlin,src/main/java,src/debug/kotlin,src/debug/java]
            defaultSourceSetName = debug
            disambiguatedName = debug
            extras = [org.jetbrains.kotlin.gradle.utils.StoredPropertyStorage=org.jetbrains.kotlin.gradle.utils.StoredPropertyStorage@<hash-code>,org.jetbrains.kotlin.gradle.plugin.hierarchy.KotlinSourceSetTreeClassifier=property(org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree, fixed(class org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree, main))]
            getAttributes = org.jetbrains.kotlin.gradle.plugin.mpp.HierarchyAttributeContainer@<hash-code>
            getName = debug
            implementationConfigurationName = debugCompilationImplementation
            kotlinOptions = org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.factory.KotlinJvmCompilerOptionsFactory${"$"}create${"$"}kotlinOptions$1@<hash-code>
            kotlinSourceSets = [[src/main/kotlin,src/main/java,src/debug/kotlin,src/debug/java]]
            output = org.jetbrains.kotlin.gradle.plugin.mpp.DefaultKotlinCompilationOutput@<hash-code>
            platformType = androidJvm
            project = project ':app'
            runtimeDependencyConfigurationName = debugRuntimeClasspath
            runtimeDependencyFiles = <can't resolve at this point as it is too early>
            runtimeOnlyConfigurationName = debugCompilationRuntimeOnly
            target = target  (androidJvm)
            toString = compilation 'debug' (target  (androidJvm))
            """.trimIndent()
        )
        // Check KotlinCompilation 'debugUnitTest' too as there could be some confusion around the
        // name of defaultSourceSet ('debugUnitTest') vs. the name of the source directories
        // ('testDebug/kotlin' and others).
        result.assertOutputContains(
            """
            Details of KotlinCompilation 'debugUnitTest':
            allAssociatedCompilations = []
            allKotlinSourceSets = [[src/test/kotlin,src/test/java,src/testDebug/kotlin,src/testDebug/java]]
            apiConfigurationName = debugUnitTestCompilationApi
            associateWith = []
            associatedCompilations = []
            compilationName = debugUnitTest
            compileAllTaskName = debugUnitTestClasses
            compileDependencyConfigurationName = debugUnitTestCompileClasspath
            compileDependencyFiles = <can't resolve at this point as it is too early>
            compileKotlinTask = task ':app:compileDebugUnitTestKotlin'
            compileKotlinTaskName = compileDebugUnitTestKotlin
            compileKotlinTaskProvider = provider(task 'compileDebugUnitTestKotlin', class org.jetbrains.kotlin.gradle.tasks.KotlinCompile)
            compileOnlyConfigurationName = debugUnitTestCompilationCompileOnly
            compileTaskProvider = provider(task 'compileDebugUnitTestKotlin', class org.jetbrains.kotlin.gradle.tasks.KotlinCompile)
            compilerOptions = org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.factory.KotlinJvmCompilerOptionsFactory${"$"}create${"$"}compilerOptions$1@<hash-code>
            defaultSourceSet = [src/test/kotlin,src/test/java,src/testDebug/kotlin,src/testDebug/java]
            defaultSourceSetName = debugUnitTest
            disambiguatedName = debugUnitTest
            extras = [org.jetbrains.kotlin.gradle.utils.StoredPropertyStorage=org.jetbrains.kotlin.gradle.utils.StoredPropertyStorage@<hash-code>,org.jetbrains.kotlin.gradle.plugin.hierarchy.KotlinSourceSetTreeClassifier=property(org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree, fixed(class org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree, test))]
            getAttributes = org.jetbrains.kotlin.gradle.plugin.mpp.HierarchyAttributeContainer@<hash-code>
            getName = debugUnitTest
            implementationConfigurationName = debugUnitTestCompilationImplementation
            kotlinOptions = org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.factory.KotlinJvmCompilerOptionsFactory${"$"}create${"$"}kotlinOptions$1@<hash-code>
            kotlinSourceSets = [[src/test/kotlin,src/test/java,src/testDebug/kotlin,src/testDebug/java]]
            output = org.jetbrains.kotlin.gradle.plugin.mpp.DefaultKotlinCompilationOutput@<hash-code>
            platformType = androidJvm
            project = project ':app'
            runtimeDependencyConfigurationName = debugUnitTestRuntimeClasspath
            runtimeDependencyFiles = <can't resolve at this point as it is too early>
            runtimeOnlyConfigurationName = debugUnitTestCompilationRuntimeOnly
            target = target  (androidJvm)
            toString = compilation 'debugUnitTest' (target  (androidJvm))
            """.trimIndent()
        )
        // Also check screenshot test as screenshot-test and test-fixture components are handled
        // slightly differently.
        // Currently, the KotlinCompilation instances for these components don't exist, but this
        // will be fixed soon (tracked by b/429161295).
        result.assertOutputDoesNotContain(
            """
            Details of KotlinCompilation 'debugScreenshotTest':
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
                            else -> value.toString().replaceAfter("@", "<hash-code>")
                        }
                    }

                    org.jetbrains.kotlin.gradle.plugin.KotlinCompilation::class.members.sortedBy { it.name }.forEach { member ->
                        if (member.visibility!!.name != "PUBLIC") return@forEach
                        if (member.parameters.size != 1) return@forEach
                        if (member.name == "hashCode") return@forEach

                        val value = when (member.name) {
                            "compileDependencyFiles", "runtimeDependencyFiles" -> "<can't resolve at this point as it is too early>"
                            else -> member.call(kotlinCompilation)
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
            """
            apply<ExampleKotlinCompilerGradlePlugin>()

            afterEvaluate {
                println("KotlinAndroidTarget.compilations = " + kotlin.target.compilations.map { it.name })
            }
            """.trimIndent()
        )
    }

}
