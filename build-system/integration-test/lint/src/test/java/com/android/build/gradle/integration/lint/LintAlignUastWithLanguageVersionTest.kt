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

package com.android.build.gradle.integration.lint

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.project.GenericProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.KotlinMultiplatformDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.builder.kotlin.KotlinExtension
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.utils.disableBuiltInKotlin
import com.android.build.gradle.internal.dsl.ModulePropertyKey.OptionalBoolean
import com.android.build.gradle.internal.lint.AndroidLintAnalysisTask
import com.android.build.gradle.options.OptionalBooleanOption
import com.android.testutils.TestUtils
import org.gradle.api.Project
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

/** Integration test checking for alignment of language version and UAST used by lint. */
@RunWith(Parameterized::class)
class LintAlignUastWithLanguageVersionTest(private val useBuiltInKotlinSupport: Boolean) {

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "useBuiltInKotlinSupport_{0}")
        fun parameters() = listOf(true, false)
    }

    @get:Rule
    val rule = createGradleRule()

    private val kotlinLanguageVersion = TestUtils.KOTLIN_VERSION_FOR_TESTS.substringBeforeLast(".")

    /**
     * Test default behavior
     */
    @Test
    fun testDefaultBehavior() {
        val build = rule.build {
            addLanguageVersionsToProject(
                appExpectedLanguageVersion = kotlinLanguageVersion,
                libExpectedLanguageVersion = kotlinLanguageVersion,
                featureExpectedLanguageVersion = kotlinLanguageVersion,
                javaLibExpectedLanguageVersion = null,
                kotlinLibExpectedLanguageVersion = kotlinLanguageVersion,
                kmpAndroidLibExpectedLanguageVersion = kotlinLanguageVersion,
                kmpJvmLibExpectedLanguageVersion = kotlinLanguageVersion,
                appExpectedUseK2Uast = true,
                libExpectedUseK2Uast = true,
                featureExpectedUseK2Uast = true,
                javaLibExpectedUseK2Uast = false,
                kotlinLibExpectedUseK2Uast = true,
                kmpAndroidLibExpectedUseK2Uast = true,
                kmpJvmLibExpectedUseK2Uast = true
            )
        }

        build.executor.run("clean", "lint")
        val result = build.executor.run("clean", "lint")
        result.assertConfigurationCacheHit()
    }

    /**
     * Test kotlin language version 1.9.
     */
    @Test
    fun testOldLanguageVersion() {
        val build = rule.build {
            addLanguageVersionsToProject(
                appExpectedLanguageVersion = "1.9",
                libExpectedLanguageVersion = "1.9",
                featureExpectedLanguageVersion = "1.9",
                javaLibExpectedLanguageVersion = null,
                kotlinLibExpectedLanguageVersion = "1.9",
                kmpAndroidLibExpectedLanguageVersion = "1.9",
                kmpJvmLibExpectedLanguageVersion = "1.9",
                appExpectedUseK2Uast = false,
                libExpectedUseK2Uast = false,
                featureExpectedUseK2Uast = false,
                javaLibExpectedUseK2Uast = false,
                kotlinLibExpectedUseK2Uast = false,
                kmpAndroidLibExpectedUseK2Uast = false,
                kmpJvmLibExpectedUseK2Uast = false,
                sourceSetsLanguageVersion = "1.9"
            )
        }

        build.executor.run("clean", "lint")
    }

    /**
     * Test incrementing kotlin language version.
     */
    @Test
    fun testKotlinExperimentalTryNext() {
        // When updating the Kotlin version used for tests, be sure to also update this constant to
        // the next version.
        val nextLanguageVersion = "2.3"
        check(nextLanguageVersion != kotlinLanguageVersion) {
            "nextLanguageVersion must be higher than the current language version ($kotlinLanguageVersion)"
        }
        val build = rule.build {
            addLanguageVersionsToProject(
                appExpectedLanguageVersion = nextLanguageVersion,
                libExpectedLanguageVersion = nextLanguageVersion,
                featureExpectedLanguageVersion = nextLanguageVersion,
                javaLibExpectedLanguageVersion = null,
                kotlinLibExpectedLanguageVersion = nextLanguageVersion,
                kmpAndroidLibExpectedLanguageVersion = nextLanguageVersion,
                kmpJvmLibExpectedLanguageVersion = nextLanguageVersion,
                appExpectedUseK2Uast = true,
                libExpectedUseK2Uast = true,
                featureExpectedUseK2Uast = true,
                javaLibExpectedUseK2Uast = false,
                kotlinLibExpectedUseK2Uast = true,
                kmpAndroidLibExpectedUseK2Uast = true,
                kmpJvmLibExpectedUseK2Uast = true,
            )
        }

        build.executor
            .withArgument("-Pkotlin.experimental.tryNext=true")
            .run("clean", "lint")
    }

    /**
     * Test enabling K2 UAST for all modules
     */
    @Test
    fun testUseK2UastGlobally() {
        val build = rule.build {
            addLanguageVersionsToProject(
                appExpectedLanguageVersion = "1.9",
                libExpectedLanguageVersion = "1.9",
                featureExpectedLanguageVersion = "1.9",
                javaLibExpectedLanguageVersion = null,
                kotlinLibExpectedLanguageVersion = "1.9",
                kmpAndroidLibExpectedLanguageVersion = "1.9",
                kmpJvmLibExpectedLanguageVersion = "1.9",
                appExpectedUseK2Uast = true,
                libExpectedUseK2Uast = true,
                featureExpectedUseK2Uast = true,
                javaLibExpectedUseK2Uast = true,
                kotlinLibExpectedUseK2Uast = true,
                kmpAndroidLibExpectedUseK2Uast = true,
                kmpJvmLibExpectedUseK2Uast = true,
                sourceSetsLanguageVersion = "1.9"
            )
        }

        build.executor
            .with(OptionalBooleanOption.LINT_USE_K2_UAST, true)
            .run("clean", "lint")
    }

    /**
     * Test enabling K2 UAST for a single module (the app module)
     */
    @Test
    fun testUseK2UastInAppOnly() {
        val build = rule.build {
            addLanguageVersionsToProject(
                appExpectedLanguageVersion = "1.9",
                libExpectedLanguageVersion = "1.9",
                featureExpectedLanguageVersion = "1.9",
                javaLibExpectedLanguageVersion = null,
                kotlinLibExpectedLanguageVersion = "1.9",
                kmpAndroidLibExpectedLanguageVersion = "1.9",
                kmpJvmLibExpectedLanguageVersion = "1.9",
                appExpectedUseK2Uast = true,
                libExpectedUseK2Uast = false,
                featureExpectedUseK2Uast = false,
                javaLibExpectedUseK2Uast = false,
                kotlinLibExpectedUseK2Uast = false,
                kmpAndroidLibExpectedUseK2Uast = false,
                kmpJvmLibExpectedUseK2Uast = false,
                sourceSetsLanguageVersion = "1.9"
            )

            androidApplication(":app") {
                android {
                    experimentalProperties[OptionalBoolean.LINT_USE_K2_UAST.key] = true
                }
            }
        }

        build.executor.run("clean", "lint")
    }

    /**
     * Test enabling K2 UAST for a single module (the lib module)
     */
    @Test
    fun testUseK2UastInLibOnly() {
        val build = rule.build {
            addLanguageVersionsToProject(
                appExpectedLanguageVersion = "1.9",
                libExpectedLanguageVersion = "1.9",
                featureExpectedLanguageVersion = "1.9",
                javaLibExpectedLanguageVersion = null,
                kotlinLibExpectedLanguageVersion = "1.9",
                kmpAndroidLibExpectedLanguageVersion = "1.9",
                kmpJvmLibExpectedLanguageVersion = "1.9",
                appExpectedUseK2Uast = false,
                libExpectedUseK2Uast = true,
                featureExpectedUseK2Uast = false,
                javaLibExpectedUseK2Uast = false,
                kotlinLibExpectedUseK2Uast = false,
                kmpAndroidLibExpectedUseK2Uast = false,
                kmpJvmLibExpectedUseK2Uast = false,
                sourceSetsLanguageVersion = "1.9"
            )

            androidLibrary(":lib") {
                android {
                    experimentalProperties[OptionalBoolean.LINT_USE_K2_UAST.key] = true
                }
            }
        }

        build.executor.run("clean", "lint")
    }

    /**
     * Test disabling K2 UAST for all modules
     */
    @Test
    fun testDisableK2UastGlobally() {
        val build = rule.build {
            addLanguageVersionsToProject(
                appExpectedLanguageVersion = kotlinLanguageVersion,
                libExpectedLanguageVersion = kotlinLanguageVersion,
                featureExpectedLanguageVersion = kotlinLanguageVersion,
                javaLibExpectedLanguageVersion = null,
                kotlinLibExpectedLanguageVersion = kotlinLanguageVersion,
                kmpAndroidLibExpectedLanguageVersion = kotlinLanguageVersion,
                kmpJvmLibExpectedLanguageVersion = kotlinLanguageVersion,
                appExpectedUseK2Uast = false,
                libExpectedUseK2Uast = false,
                featureExpectedUseK2Uast = false,
                javaLibExpectedUseK2Uast = false,
                kotlinLibExpectedUseK2Uast = false,
                kmpAndroidLibExpectedUseK2Uast = false,
                kmpJvmLibExpectedUseK2Uast = false,
            )
        }

        build.executor
            .with(OptionalBooleanOption.LINT_USE_K2_UAST, false)
            .run("clean", "lint")
    }

    /**
     * Test disabling K2 UAST for a single module (the app module)
     */
    @Test
    fun testDisableK2UastInAppOnly() {
        val build = rule.build {
            addLanguageVersionsToProject(
                appExpectedLanguageVersion = kotlinLanguageVersion,
                libExpectedLanguageVersion = kotlinLanguageVersion,
                featureExpectedLanguageVersion = kotlinLanguageVersion,
                javaLibExpectedLanguageVersion = null,
                kotlinLibExpectedLanguageVersion = kotlinLanguageVersion,
                kmpAndroidLibExpectedLanguageVersion = kotlinLanguageVersion,
                kmpJvmLibExpectedLanguageVersion = kotlinLanguageVersion,
                appExpectedUseK2Uast = false,
                libExpectedUseK2Uast = true,
                featureExpectedUseK2Uast = true,
                javaLibExpectedUseK2Uast = false,
                kotlinLibExpectedUseK2Uast = true,
                kmpAndroidLibExpectedUseK2Uast = true,
                kmpJvmLibExpectedUseK2Uast = true,
            )

            androidApplication(":app") {
                android {
                    experimentalProperties[OptionalBoolean.LINT_USE_K2_UAST.key] = false
                }
            }
        }

        build.executor.run("clean", "lint")
    }

    /**
     * Test disabling K2 UAST for a single module (the lib module)
     */
    @Test
    fun testDisableK2UastInLibOnly() {
        val build = rule.build {
            addLanguageVersionsToProject(
                appExpectedLanguageVersion = kotlinLanguageVersion,
                libExpectedLanguageVersion = kotlinLanguageVersion,
                featureExpectedLanguageVersion = kotlinLanguageVersion,
                javaLibExpectedLanguageVersion = null,
                kotlinLibExpectedLanguageVersion = kotlinLanguageVersion,
                kmpAndroidLibExpectedLanguageVersion = kotlinLanguageVersion,
                kmpJvmLibExpectedLanguageVersion = kotlinLanguageVersion,
                appExpectedUseK2Uast = true,
                libExpectedUseK2Uast = false,
                featureExpectedUseK2Uast = true,
                javaLibExpectedUseK2Uast = false,
                kotlinLibExpectedUseK2Uast = true,
                kmpAndroidLibExpectedUseK2Uast = true,
                kmpJvmLibExpectedUseK2Uast = true,
            )

            androidLibrary(":lib") {
                android {
                    experimentalProperties[OptionalBoolean.LINT_USE_K2_UAST.key] = false
                }
            }

            disableBuiltInKotlin()
        }

        build.executor.run("clean", "lint")
    }

    /**
     * Creates a multi-module android project
     */
    private fun createGradleRule() = GradleRule.configure()
        .withGradleOptions {
            withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        }.from {
            val kotlinPlugin =
                if (useBuiltInKotlinSupport) {
                    PluginType.ANDROID_BUILT_IN_KOTLIN
                } else {
                    PluginType.KOTLIN_ANDROID
                }

            androidApplication(":app") {
                applyPlugin(kotlinPlugin)

                android {
                    dynamicFeatures += listOf(":feature")
                    lint {
                        checkDependencies = true
                        textOutput = File("lint-report.txt")
                        checkAllWarnings = true
                        checkTestSources = true
                    }
                }

                kotlin {
                    jvmToolchain(17)
                }

                dependencies {
                    implementation(project(":lib"))
                    implementation(project(":java-lib"))
                    implementation(project(":kotlin-lib"))
                    implementation(project(":kmp-android-lib"))
                    implementation(project(":kmp-jvm-lib"))
                    implementation("org.jetbrains.kotlin:kotlin-stdlib:${TestUtils.KOTLIN_VERSION_FOR_TESTS}")
                    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:${TestUtils.KOTLIN_VERSION_FOR_TESTS}")
                }

                files.add(
                    "src/main/kotlin/com/example/ExampleClass.kt",
                    // language=kotlin
                    """
                        package com.example

                        class ExampleClass
                    """.trimIndent()
                )
            }

            androidLibrary(":lib") {
                applyPlugin(kotlinPlugin)
                android {
                    lint {
                        checkAllWarnings = true
                        checkTestSources = true
                    }
                }

                dependencies {
                    implementation("org.jetbrains.kotlin:kotlin-stdlib:${TestUtils.KOTLIN_VERSION_FOR_TESTS}")
                    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:${TestUtils.KOTLIN_VERSION_FOR_TESTS}")
                }
            }

            androidFeature(":feature") {
                applyPlugin(kotlinPlugin)
                android {
                    lint {
                        checkAllWarnings = true
                        checkTestSources = true
                    }
                }

                dependencies {
                    implementation(project(":app"))
                    implementation("org.jetbrains.kotlin:kotlin-stdlib:${TestUtils.KOTLIN_VERSION_FOR_TESTS}")
                    androidTestImplementation("org.jetbrains.kotlin:kotlin-stdlib:${TestUtils.KOTLIN_VERSION_FOR_TESTS}")
                }
            }

            genericProject(":java-lib") {
                applyPlugin(PluginType.JAVA_LIBRARY)
                applyPlugin(PluginType.LINT) {
                    checkAllWarnings = true
                    checkTestSources = true
                }
            }

            genericProject(":kotlin-lib") {
                applyPlugin(PluginType.KOTLIN_JVM) { }
                applyPlugin(PluginType.LINT) {
                    checkAllWarnings = true
                    checkTestSources = true
                }
            }

            // kmpAndroidLib is a KMP library with only an android target (no jvm targets)
            androidKotlinMultiplatformLibrary(":kmp-android-lib") {
                applyPlugin(PluginType.LINT)
            }

            // kmpJvmLib is a KMP library with only a jvm target (no android target)
            kotlinMultiplatformLibrary(":kmp-jvm-lib") {
                applyPlugin(PluginType.LINT)

                kotlin {
                    jvm()
                }
            }

            disableBuiltInKotlin()
        }

    /**
     * Adds source set language versions and expected language versions to [project]
     */
    private fun GradleBuildDefinition.addLanguageVersionsToProject(
        appExpectedLanguageVersion: String?,
        libExpectedLanguageVersion: String?,
        featureExpectedLanguageVersion: String?,
        javaLibExpectedLanguageVersion: String?,
        kotlinLibExpectedLanguageVersion: String?,
        kmpAndroidLibExpectedLanguageVersion: String?,
        kmpJvmLibExpectedLanguageVersion: String?,
        appExpectedUseK2Uast: Boolean,
        libExpectedUseK2Uast: Boolean,
        featureExpectedUseK2Uast: Boolean,
        javaLibExpectedUseK2Uast: Boolean,
        kotlinLibExpectedUseK2Uast: Boolean,
        kmpAndroidLibExpectedUseK2Uast: Boolean,
        kmpJvmLibExpectedUseK2Uast: Boolean,
        sourceSetsLanguageVersion: String? = null
    ) {
        sourceSetsLanguageVersion?.let { version ->
            fun KotlinExtension.configureKotlinSourceSets() {
                sourceSets.all {
                    it.languageSettings {
                        languageVersion = version
                    }
                }
            }

            val kotlinSubProjectPaths =
                listOf(":app", ":lib", ":feature", ":kotlin-lib", ":kmp-android-lib", ":kmp-jvm-lib")
            for (subprojectPath in kotlinSubProjectPaths) {
                configure(subprojectPath) {
                    when (this) {
                        is AndroidProjectDefinition<*> -> {
                            kotlin {
                                configureKotlinSourceSets()
                            }
                        }
                        is KotlinMultiplatformDefinition -> {
                            kotlin.configureKotlinSourceSets()
                        }
                        is GenericProjectDefinition -> {
                            reconfigurePlugin(PluginType.KOTLIN_JVM) {
                                configureKotlinSourceSets()
                            }
                        }
                        else -> throw RuntimeException("unsupported project type: ${javaClass}")
                    }
                    // TODO(b/341765853): Support setting language version via source sets with built-in
                    //  Kotlin support.
                    if (useBuiltInKotlinSupport) {
                        pluginCallbacks += SourceSetsLanguageVersion::class.java
                    }

                }
            }

            if (useBuiltInKotlinSupport) {
                gradleProperties {
                    add(SourceSetsLanguageVersion.KEY, version)
                }
            }
        }

        // method to set up a project with its lint check callback
        fun applyLintCheckCallback(
            path: String,
            callbackClass: Class<out LintAnalyzeCheck>,
            useK2UastKey: String,
            useK2UastExpected: Boolean,
            languageVersionKey: String,
            languageVersionExpected: String?

        ) {
            configure(path) {
                pluginCallbacks += callbackClass
            }
            gradleProperties {
                add(useK2UastKey, useK2UastExpected.toString())
                languageVersionExpected?.let {
                    add(languageVersionKey, it)
                }
            }
        }

        applyLintCheckCallback(
            path = ":app",
            callbackClass = AppLintAnalyzeCheck::class.java,
            useK2UastKey = AppLintAnalyzeCheck.KEY_USE_K2_UAST,
            useK2UastExpected = appExpectedUseK2Uast,
            languageVersionKey = AppLintAnalyzeCheck.KEY_LANGUAGE_VERSION,
            languageVersionExpected = appExpectedLanguageVersion
        )

        applyLintCheckCallback(
            path = ":lib",
            callbackClass = LibLintAnalyzeCheck::class.java,
            useK2UastKey = LibLintAnalyzeCheck.KEY_USE_K2_UAST,
            useK2UastExpected = libExpectedUseK2Uast,
            languageVersionKey = LibLintAnalyzeCheck.KEY_LANGUAGE_VERSION,
            languageVersionExpected = libExpectedLanguageVersion
        )

        applyLintCheckCallback(
            path = ":feature",
            callbackClass = FeatureLintAnalyzeCheck::class.java,
            useK2UastKey = FeatureLintAnalyzeCheck.KEY_USE_K2_UAST,
            useK2UastExpected = featureExpectedUseK2Uast,
            languageVersionKey = FeatureLintAnalyzeCheck.KEY_LANGUAGE_VERSION,
            languageVersionExpected = featureExpectedLanguageVersion
        )

        applyLintCheckCallback(
            path = ":java-lib",
            callbackClass = JavaLibLintAnalyzeCheck::class.java,
            useK2UastKey = JavaLibLintAnalyzeCheck.KEY_USE_K2_UAST,
            useK2UastExpected = javaLibExpectedUseK2Uast,
            languageVersionKey = JavaLibLintAnalyzeCheck.KEY_LANGUAGE_VERSION,
            languageVersionExpected = javaLibExpectedLanguageVersion
        )

        applyLintCheckCallback(
            path = ":kotlin-lib",
            callbackClass = KotlinLibLintAnalyzeCheck::class.java,
            useK2UastKey = KotlinLibLintAnalyzeCheck.KEY_USE_K2_UAST,
            useK2UastExpected = kotlinLibExpectedUseK2Uast,
            languageVersionKey = KotlinLibLintAnalyzeCheck.KEY_LANGUAGE_VERSION,
            languageVersionExpected = kotlinLibExpectedLanguageVersion
        )

        applyLintCheckCallback(
            path = ":kmp-android-lib",
            callbackClass = KmpAndroidLibLintAnalyzeCheck::class.java,
            useK2UastKey = KmpAndroidLibLintAnalyzeCheck.KEY_USE_K2_UAST,
            useK2UastExpected = kmpAndroidLibExpectedUseK2Uast,
            languageVersionKey = KmpAndroidLibLintAnalyzeCheck.KEY_LANGUAGE_VERSION,
            languageVersionExpected = kmpAndroidLibExpectedLanguageVersion
        )

        applyLintCheckCallback(
            path = ":kmp-jvm-lib",
            callbackClass = KmpJvmLibLintAnalyzeCheck::class.java,
            useK2UastKey = KmpJvmLibLintAnalyzeCheck.KEY_USE_K2_UAST,
            useK2UastExpected = kmpJvmLibExpectedUseK2Uast,
            languageVersionKey = KmpJvmLibLintAnalyzeCheck.KEY_LANGUAGE_VERSION,
            languageVersionExpected = kmpJvmLibExpectedLanguageVersion
        )
    }
}

class SourceSetsLanguageVersion: GenericCallback {
    companion object {
        const val KEY = "LintAlignUastWithLanguageVersionTest.SourceSetsLanguageVersion"
    }

    override fun handleProject(project: Project) {
        val version = project.providers.gradleProperty(KEY).orNull
            ?: throw RuntimeException("Missing key for SourceSetsLanguageVersion callback")

        project.tasks.withType(KotlinCompile::class.java).configureEach {
            it.compilerOptions.languageVersion.set(KotlinVersion.fromVersion(version))
        }
    }
}

/**
 *  Generic call back to validate projects. This is not to be used directly. Instead,
 *  each project extends this to provide the right task name, and key values
 */
abstract class LintAnalyzeCheck: GenericCallback {
    abstract val lintTaskName: String
    abstract val languageVersionKey: String
    abstract val useK2UastKey: String

    override fun handleProject(project: Project) {
        val expectedLanguageVersion = project.expectedLanguageVersion()
        val expectedUseK2Uast = project.expectedUseK2Uast()
        val projectPath = project.path

        project.afterEvaluate {
            project.tasks.named(lintTaskName) { genericTask ->
                val lintTask = genericTask as AndroidLintAnalysisTask

                lintTask.doLast {
                    if (lintTask.uastInputs.kotlinLanguageVersion != expectedLanguageVersion) {
                        throw RuntimeException("Unexpected $projectPath language version: " + lintTask.uastInputs.kotlinLanguageVersion)
                    }
                    if (lintTask.uastInputs.useK2Uast != expectedUseK2Uast) {
                        throw RuntimeException("Unexpected $projectPath useK2Uast: " + lintTask.uastInputs.useK2Uast)
                    }
                }
            }
        }
    }

    private fun Project.expectedUseK2Uast(): Boolean = providers.gradleProperty(useK2UastKey).orNull?.toBoolean()
        ?: throw RuntimeException("Missing value for $useK2UastKey")

    private fun Project.expectedLanguageVersion(): String? = providers.gradleProperty(languageVersionKey).orNull
}

class AppLintAnalyzeCheck: LintAnalyzeCheck() {
    companion object {
        const val KEY_USE_K2_UAST = "LintAlignUastWithLanguageVersionTest.AppLintAnalyzeCheck.expectedUseK2Uast"
        const val KEY_LANGUAGE_VERSION = "LintAlignUastWithLanguageVersionTest.AppLintAnalyzeCheck.expectedLanguageVersion"
    }

    override val lintTaskName: String
        get() = "lintAnalyzeDebug"
    override val languageVersionKey: String
        get() = KEY_LANGUAGE_VERSION
    override val useK2UastKey: String
        get() = KEY_USE_K2_UAST
}

class LibLintAnalyzeCheck: LintAnalyzeCheck() {
    companion object {
        const val KEY_USE_K2_UAST = "LintAlignUastWithLanguageVersionTest.LibLintAnalyzeCheck.expectedUseK2Uast"
        const val KEY_LANGUAGE_VERSION = "LintAlignUastWithLanguageVersionTest.LibLintAnalyzeCheck.expectedLanguageVersion"
    }

    override val lintTaskName: String
        get() = "lintAnalyzeDebug"
    override val languageVersionKey: String
        get() = KEY_LANGUAGE_VERSION
    override val useK2UastKey: String
        get() = KEY_USE_K2_UAST
}

class FeatureLintAnalyzeCheck: LintAnalyzeCheck() {
    companion object {
        const val KEY_USE_K2_UAST = "LintAlignUastWithLanguageVersionTest.FeatureLintAnalyzeCheck.expectedUseK2Uast"
        const val KEY_LANGUAGE_VERSION = "LintAlignUastWithLanguageVersionTest.FeatureLintAnalyzeCheck.expectedLanguageVersion"
    }

    override val lintTaskName: String
        get() = "lintAnalyzeDebug"
    override val languageVersionKey: String
        get() = KEY_LANGUAGE_VERSION
    override val useK2UastKey: String
        get() = KEY_USE_K2_UAST
}

class JavaLibLintAnalyzeCheck: LintAnalyzeCheck() {
    companion object {
        const val KEY_USE_K2_UAST = "LintAlignUastWithLanguageVersionTest.JavaLibLintAnalyzeCheck.expectedUseK2Uast"
        const val KEY_LANGUAGE_VERSION = "LintAlignUastWithLanguageVersionTest.JavaLibLintAnalyzeCheck.expectedLanguageVersion"
    }

    override val lintTaskName: String
        get() = "lintAnalyzeJvmMain"
    override val languageVersionKey: String
        get() = KEY_LANGUAGE_VERSION
    override val useK2UastKey: String
        get() = KEY_USE_K2_UAST
}

class KotlinLibLintAnalyzeCheck: LintAnalyzeCheck() {
    companion object {
        const val KEY_USE_K2_UAST = "LintAlignUastWithLanguageVersionTest.KotlinLibLintAnalyzeCheck.expectedUseK2Uast"
        const val KEY_LANGUAGE_VERSION = "LintAlignUastWithLanguageVersionTest.KotlinLibLintAnalyzeCheck.expectedLanguageVersion"
    }

    override val lintTaskName: String
        get() = "lintAnalyzeJvmMain"
    override val languageVersionKey: String
        get() = KEY_LANGUAGE_VERSION
    override val useK2UastKey: String
        get() = KEY_USE_K2_UAST
}

class KmpAndroidLibLintAnalyzeCheck: LintAnalyzeCheck() {
    companion object {
        const val KEY_USE_K2_UAST = "LintAlignUastWithLanguageVersionTest.KmpAndroidLibLintAnalyzeCheck.expectedUseK2Uast"
        const val KEY_LANGUAGE_VERSION = "LintAlignUastWithLanguageVersionTest.KmpAndroidLibLintAnalyzeCheck.expectedLanguageVersion"
    }

    override val lintTaskName: String
        get() = "lintAnalyzeAndroidMain"
    override val languageVersionKey: String
        get() = KEY_LANGUAGE_VERSION
    override val useK2UastKey: String
        get() = KEY_USE_K2_UAST
}

class KmpJvmLibLintAnalyzeCheck: LintAnalyzeCheck() {
    companion object {
        const val KEY_USE_K2_UAST = "LintAlignUastWithLanguageVersionTest.KmpJvmLibLintAnalyzeCheck.expectedUseK2Uast"
        const val KEY_LANGUAGE_VERSION = "LintAlignUastWithLanguageVersionTest.KmpJvmLibLintAnalyzeCheck.expectedLanguageVersion"
    }

    override val lintTaskName: String
        get() = "lintAnalyzeJvmMain"
    override val languageVersionKey: String
        get() = KEY_LANGUAGE_VERSION
    override val useK2UastKey: String
        get() = KEY_USE_K2_UAST
}
