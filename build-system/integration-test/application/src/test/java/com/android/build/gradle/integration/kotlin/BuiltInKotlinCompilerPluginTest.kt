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
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Tests that built-in Kotlin support works when Kotlin compiler Gradle plugins are used. */
@RunWith(Parameterized::class)
class BuiltInKotlinCompilerPluginTest(
    private val builtInKotlinBooleanOption: Boolean,
) {

    companion object {

        @Parameterized.Parameters(name = "builtInKotlinBooleanOption_{0}")
        @JvmStatic
        fun parameters() = listOf(false, true)
    }

    @get:Rule
    val rule = GradleRule.from {
        buildFileType = BuildFileType.KTS
        androidApplication {
            applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
            android.experimentalProperties[BooleanWithDefault.SCREENSHOT_TEST.key] = true
        }
        gradleProperties {
            add(BooleanOption.BUILT_IN_KOTLIN, builtInKotlinBooleanOption)
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
        result.assertOutputContains("KotlinAndroidTarget.compilations = [debug, debugAndroidTest, debugScreenshotTest, debugUnitTest, release, releaseScreenshotTest]")
        result.assertOutputContains(
            """
            Details of KotlinCompilation 'debug':
            allAssociatedCompilations = []
            allKotlinSourceSets = [[src/main/java,src/main/kotlin,src/debug/java,src/debug/kotlin]]
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
            defaultSourceSet = [src/main/java,src/main/kotlin,src/debug/java,src/debug/kotlin]
            defaultSourceSetName = debug
            disambiguatedName = debug
            extras = [org.jetbrains.kotlin.gradle.utils.StoredPropertyStorage=org.jetbrains.kotlin.gradle.utils.StoredPropertyStorage@<hash-code>,org.jetbrains.kotlin.gradle.plugin.hierarchy.KotlinSourceSetTreeClassifier=property(org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree, fixed(class org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree, main))]
            getAttributes = org.jetbrains.kotlin.gradle.plugin.mpp.HierarchyAttributeContainer@<hash-code>
            getName = debug
            implementationConfigurationName = debugCompilationImplementation
            kotlinOptions = org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.factory.KotlinJvmCompilerOptionsFactory${"$"}create${"$"}kotlinOptions$1@<hash-code>
            kotlinSourceSets = [[src/main/java,src/main/kotlin,src/debug/java,src/debug/kotlin]]
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
        // Check KotlinCompilation 'debugUnitTest' too as it is slightly different from
        // KotlinCompilation `debug`: The kotlinSourceSets directory names do not
        // contain the Kotlin compilation name.
        result.assertOutputContains(
            """
            Details of KotlinCompilation 'debugUnitTest':
            allAssociatedCompilations = []
            allKotlinSourceSets = [[src/test/java,src/test/kotlin,src/testDebug/java,src/testDebug/kotlin]]
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
            defaultSourceSet = [src/test/java,src/test/kotlin,src/testDebug/java,src/testDebug/kotlin]
            defaultSourceSetName = debugUnitTest
            disambiguatedName = debugUnitTest
            extras = [org.jetbrains.kotlin.gradle.utils.StoredPropertyStorage=org.jetbrains.kotlin.gradle.utils.StoredPropertyStorage@<hash-code>,org.jetbrains.kotlin.gradle.plugin.hierarchy.KotlinSourceSetTreeClassifier=property(org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree, fixed(class org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree, test))]
            getAttributes = org.jetbrains.kotlin.gradle.plugin.mpp.HierarchyAttributeContainer@<hash-code>
            getName = debugUnitTest
            implementationConfigurationName = debugUnitTestCompilationImplementation
            kotlinOptions = org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.factory.KotlinJvmCompilerOptionsFactory${"$"}create${"$"}kotlinOptions$1@<hash-code>
            kotlinSourceSets = [[src/test/java,src/test/kotlin,src/testDebug/java,src/testDebug/kotlin]]
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
        // Check KotlinCompilation 'debugScreenshotTest' too as it is slightly different from
        // KotlinCompilation 'debug': It is supported only by built-in Kotlin, not the
        // `kotlin-android` plugin.
        result.assertOutputContains(
            """
            Details of KotlinCompilation 'debugScreenshotTest':
            allAssociatedCompilations = []
            allKotlinSourceSets = [[src/screenshotTest/java,src/screenshotTest/kotlin,src/screenshotTestDebug/java,src/screenshotTestDebug/kotlin]]
            apiConfigurationName = debugScreenshotTestCompilationApi
            associateWith = []
            associatedCompilations = []
            compilationName = debugScreenshotTest
            compileAllTaskName = debugScreenshotTestClasses
            compileDependencyConfigurationName = debugScreenshotTestCompileClasspath
            compileDependencyFiles = <can't resolve at this point as it is too early>
            compileKotlinTask = task ':app:compileDebugScreenshotTestKotlin'
            compileKotlinTaskName = compileDebugScreenshotTestKotlin
            compileKotlinTaskProvider = provider(task 'compileDebugScreenshotTestKotlin', class org.jetbrains.kotlin.gradle.tasks.KotlinCompile)
            compileOnlyConfigurationName = debugScreenshotTestCompilationCompileOnly
            compileTaskProvider = provider(task 'compileDebugScreenshotTestKotlin', class org.jetbrains.kotlin.gradle.tasks.KotlinCompile)
            compilerOptions = org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.factory.KotlinJvmCompilerOptionsFactory${"$"}create${"$"}compilerOptions$1@<hash-code>
            defaultSourceSet = [src/screenshotTest/java,src/screenshotTest/kotlin,src/screenshotTestDebug/java,src/screenshotTestDebug/kotlin]
            defaultSourceSetName = debugScreenshotTest
            disambiguatedName = debugScreenshotTest
            extras = [org.jetbrains.kotlin.gradle.utils.StoredPropertyStorage=org.jetbrains.kotlin.gradle.utils.StoredPropertyStorage@<hash-code>,org.jetbrains.kotlin.gradle.plugin.hierarchy.KotlinSourceSetTreeClassifier=None]
            getAttributes = org.jetbrains.kotlin.gradle.plugin.mpp.HierarchyAttributeContainer@<hash-code>
            getName = debugScreenshotTest
            implementationConfigurationName = debugScreenshotTestCompilationImplementation
            kotlinOptions = org.jetbrains.kotlin.gradle.plugin.mpp.compilationImpl.factory.KotlinJvmCompilerOptionsFactory${"$"}create${"$"}kotlinOptions$1@<hash-code>
            kotlinSourceSets = [[src/screenshotTest/java,src/screenshotTest/kotlin,src/screenshotTestDebug/java,src/screenshotTestDebug/kotlin]]
            output = org.jetbrains.kotlin.gradle.plugin.mpp.DefaultKotlinCompilationOutput@<hash-code>
            platformType = androidJvm
            project = project ':app'
            runtimeDependencyConfigurationName = debugScreenshotTestRuntimeClasspath
            runtimeDependencyFiles = <can't resolve at this point as it is too early>
            runtimeOnlyConfigurationName = debugScreenshotTestCompilationRuntimeOnly
            target = target  (androidJvm)
            toString = compilation 'debugScreenshotTest' (target  (androidJvm))
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
