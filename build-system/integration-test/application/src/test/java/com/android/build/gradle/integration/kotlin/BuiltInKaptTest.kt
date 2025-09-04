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
import com.android.build.gradle.integration.common.fixture.GradleProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.GradleTestProject.ApkType.Companion.ANDROIDTEST_DEBUG
import com.android.build.gradle.integration.common.fixture.GradleTestProject.ApkType.Companion.DEBUG
import com.android.build.gradle.integration.common.fixture.app.AnnotationProcessorLib
import com.android.build.gradle.integration.common.fixture.app.HelloWorldApp
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.internal.TaskManager.Companion.COMPOSE_UI_VERSION
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.internal.scope.getOutputDir
import com.android.build.gradle.internal.utils.ANDROID_BUILT_IN_KAPT_PLUGIN_ID
import com.android.build.gradle.internal.utils.ANDROID_BUILT_IN_KOTLIN_PLUGIN_ID
import com.android.build.gradle.internal.utils.COMPOSE_COMPILER_PLUGIN_ID
import com.android.build.gradle.internal.utils.KOTLIN_KAPT_PLUGIN_ID
import com.android.build.gradle.options.BooleanOption
import com.android.builder.model.SyncIssue
import com.android.testutils.truth.PathSubject
import com.android.utils.appendCapitalized
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class BuiltInKaptTest {

    @Rule
    @JvmField
    val project: GradleTestProject =
        GradleTestProject.builder().fromTestApp(
            MultiModuleTestProject(
                mapOf<String, GradleProject>(
                    ":app" to HelloWorldApp.forPlugin("com.android.application"),
                    ":lib" to AnnotationProcessorLib.createLibrary(),
                    ":lib-compiler" to AnnotationProcessorLib.createCompiler()
                )
            )
        ).withBuiltInKotlinSupport(true)
            .withKotlinGradlePlugin(true)
            .withComposeCompilerGradlePlugin(true)
            .create()

    @Before
    fun setUp() {
        val app = project.getSubproject(":app")
        TestFileUtils.searchAndReplace(
            app.buildFile,
            "apply plugin: 'com.android.application'",
            """
                apply plugin: 'com.android.application'
                apply plugin: '$ANDROID_BUILT_IN_KOTLIN_PLUGIN_ID'
                apply plugin: '$ANDROID_BUILT_IN_KAPT_PLUGIN_ID'
                """.trimIndent(),
        )
        app.buildFile.appendText(
            """
                dependencies {
                    api project(':lib')
                    kapt project(':lib-compiler')
                    kaptAndroidTest project(':lib-compiler')
                    kaptTest project(':lib-compiler')
                }
                """.trimIndent()
        )
        with(app.mainSrcDir.resolve("com/example/Foo.kt")) {
            parentFile.mkdirs()

            writeText(
                """
                    package com.example

                    import com.example.annotation.ProvideString

                    @ProvideString
                    class Foo
                    """.trimIndent()
            )
        }
        with(app.mainSrcDir.resolve("com/example/JavaFoo.java")) {
            parentFile.mkdirs()

            writeText(
                """
                    package com.example;

                    import com.example.annotation.ProvideString;

                    @ProvideString
                    class JavaFoo {}
                    """.trimIndent()
            )
        }
        with(app.projectDir.resolve("src/androidTest/java/com/example/AndroidTestFoo.kt")) {
            parentFile.mkdirs()

            writeText(
                """
                    package com.example

                    import com.example.annotation.ProvideString

                    @ProvideString
                    class AndroidTestFoo
                    """.trimIndent()
            )
        }
        with(app.projectDir.resolve("src/test/java/com/example/TestFoo.kt")) {
            parentFile.mkdirs()

            writeText(
                """
                    package com.example

                    import com.example.annotation.ProvideString

                    @ProvideString
                    class TestFoo
                    """.trimIndent()
            )
        }
    }

    @Test
    fun testAnnotationProcessing() {
        project.executor()
            .run(
                "app:assembleDebug",
                "app:assembleDebugAndroidTest",
                "app:compileDebugUnitTestJavaWithJavac"
            )
        val app = project.getSubproject(":app")
        app.getApk(DEBUG).use { apk ->
            assertThat(apk).hasClass("Lcom/example/FooStringValue;")
            assertThat(apk).hasClass("Lcom/example/Foo\$\$InnerClass;")
            assertThat(apk).hasClass("Lcom/example/JavaFooStringValue;")
            assertThat(apk).hasClass("Lcom/example/JavaFoo\$\$InnerClass;")
        }
        app.getApk(ANDROIDTEST_DEBUG).use { apk ->
            assertThat(apk).hasClass("Lcom/example/AndroidTestFooStringValue;")
            assertThat(apk).hasClass("Lcom/example/AndroidTestFoo\$\$InnerClass;")
        }
        val kaptGeneratedTestDir =
            app.buildDir.resolve("generated/source/kapt/test/debug/com/example")
        PathSubject.assertThat(kaptGeneratedTestDir.resolve("TestFooStringValue.java")).exists()
        PathSubject.assertThat(kaptGeneratedTestDir.resolve("TestFoo\$\$InnerClass.java")).exists()
    }

    @Test
    fun testWithAnnotationProcessorConfiguration() {
        TestFileUtils.searchAndReplace(
            project.getSubproject(":app").buildFile,
            "kapt project(':lib-compiler')",
            "annotationProcessor project(':lib-compiler')"
        )
        TestFileUtils.searchAndReplace(
            project.getSubproject(":app").buildFile,
            "kaptAndroidTest project(':lib-compiler')",
            "androidTestAnnotationProcessor project(':lib-compiler')"
        )
        TestFileUtils.searchAndReplace(
            project.getSubproject(":app").buildFile,
            "kaptTest project(':lib-compiler')",
            "testAnnotationProcessor project(':lib-compiler')"
        )

        project.executor()
            .run(
                "app:assembleDebug",
                "app:assembleDebugAndroidTest",
                "app:compileDebugUnitTestJavaWithJavac"
            )
        val app = project.getSubproject(":app")
        app.getApk(DEBUG).use { apk ->
            assertThat(apk).hasClass("Lcom/example/FooStringValue;")
            assertThat(apk).hasClass("Lcom/example/Foo\$\$InnerClass;")
            assertThat(apk).hasClass("Lcom/example/JavaFooStringValue;")
            assertThat(apk).hasClass("Lcom/example/JavaFoo\$\$InnerClass;")
        }
        app.getApk(ANDROIDTEST_DEBUG).use { apk ->
            assertThat(apk).hasClass("Lcom/example/AndroidTestFooStringValue;")
            assertThat(apk).hasClass("Lcom/example/AndroidTestFoo\$\$InnerClass;")
        }
        val kaptGeneratedTestDir =
            app.buildDir.resolve("generated/source/kapt/test/debug/com/example")
        PathSubject.assertThat(kaptGeneratedTestDir.resolve("TestFooStringValue.java")).exists()
        PathSubject.assertThat(kaptGeneratedTestDir.resolve("TestFoo\$\$InnerClass.java")).exists()
    }

    @Test
    fun testGeneratedSourcesModel() {
        val appModel =
            project.modelV2()
                .ignoreSyncIssues(SyncIssue.SEVERITY_WARNING)
                .fetchModels()
                .container
                .getProject(":app")
        appModel.androidProject!!.variants.forEach { variant ->
            val kaptTaskName = "kapt".appendCapitalized(variant.name, "kotlin")
            val kaptGeneratedClasses =
                InternalArtifactType.BUILT_IN_KAPT_CLASSES_DIR
                    .getOutputDir(project.getSubproject(":app").buildDir)
                    .resolve("${variant.name}/$kaptTaskName")
            val kaptGeneratedJava =
                project.getSubproject(":app")
                    .buildDir
                    .resolve("generated/source/kapt/${variant.name}")
            val kaptGeneratedKotlin =
                project.getSubproject(":app")
                    .buildDir
                    .resolve("generated/source/kaptKotlin/${variant.name}")

            assertThat(variant.mainArtifact.classesFolders).contains(kaptGeneratedClasses)
            assertThat(variant.mainArtifact.generatedClassPaths.values).contains(
                kaptGeneratedClasses
            )
            assertThat(variant.mainArtifact.generatedSourceFolders).containsAtLeast(
                kaptGeneratedJava,
                kaptGeneratedKotlin
            )

            project.executor().run(":app:$kaptTaskName")
            PathSubject.assertThat(kaptGeneratedClasses).exists()
            PathSubject.assertThat(kaptGeneratedJava).exists()
            PathSubject.assertThat(kaptGeneratedKotlin).exists()
        }
    }

    @Test
    fun `fail when built-in Kotlin plugin is applied before kotlin-kapt plugin`() {
        val app = project.getSubproject(":app")
        TestFileUtils.searchAndReplace(
            app.buildFile,
            "apply plugin: '$ANDROID_BUILT_IN_KAPT_PLUGIN_ID'",
            """
            apply plugin: '$ANDROID_BUILT_IN_KOTLIN_PLUGIN_ID'
            apply plugin: '$KOTLIN_KAPT_PLUGIN_ID'
            """.trimIndent(),
        )

        val result = app.executor().expectFailure().run(":app:assembleDebug")
        result.assertErrorContains(
            "The 'org.jetbrains.kotlin.kapt' plugin is not compatible with built-in Kotlin support."
        )
    }

    @Test
    fun `fail when built-in Kotlin plugin is applied after kotlin-kapt plugin`() {
        val app = project.getSubproject(":app")
        TestFileUtils.searchAndReplace(
            app.buildFile,
            "apply plugin: 'com.android.application'",
            """
            apply plugin: '$KOTLIN_KAPT_PLUGIN_ID'
            apply plugin: 'com.android.application'
            """.trimIndent(),
        )

        val result = app.executor().expectFailure().run(":app:assembleDebug")
        result.assertErrorContains(
            "The 'org.jetbrains.kotlin.kapt' plugin is not compatible with built-in Kotlin support."
        )
    }

    @Test
    fun `fail when built-in Kapt plugin is applied without built-in Kotlin plugin`() {
        val app = project.getSubproject(":app")
        TestFileUtils.searchAndReplace(
            app.buildFile,
            "apply plugin: '$ANDROID_BUILT_IN_KOTLIN_PLUGIN_ID'",
            ""
        )

        val result = app.executor().expectFailure().run(":app:assembleDebug")
        result.assertErrorContains(
            "The 'com.android.legacy-kapt' plugin requires the 'com.android.experimental.built-in-kotlin' plugin to be applied."
        )
    }

    /**
     * Test to ensure that the built-in KaptGenerateStubs task handles basic compose code
     */
    @Test
    fun testBuiltInKaptWithCompose() {
        val app = project.getSubproject(":app")
        // Add the Compose Compiler Gradle plugin
        TestFileUtils.searchAndReplace(
            app.buildFile,
            "apply plugin: '$ANDROID_BUILT_IN_KAPT_PLUGIN_ID'",
            """
                apply plugin: '$ANDROID_BUILT_IN_KAPT_PLUGIN_ID'
                apply plugin: '$COMPOSE_COMPILER_PLUGIN_ID'
                """.trimIndent(),
        )
        TestFileUtils.appendToFile(
            app.buildFile,
            """
                android {
                    defaultConfig {
                        minSdk = 24
                    }
                }

                dependencies {
                    implementation("androidx.compose.ui:ui-tooling:$COMPOSE_UI_VERSION")
                    implementation("androidx.compose.material:material:$COMPOSE_UI_VERSION")
                }
            """.trimIndent()
        )
        val composeSourceFile = app.mainSrcDir.resolve("com/example/Compose.kt")
        composeSourceFile.parentFile.mkdirs()
        TestFileUtils.appendToFile(
            composeSourceFile,
            """
                package com.example

                import androidx.compose.foundation.layout.Column
                import androidx.compose.material.Text
                import androidx.compose.runtime.Composable

                @Composable
                fun Compose() {
                    Column {
                        Text(text = "Hello World")
                    }
                }

            """.trimIndent()
        )

        project.executor()
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .with(BooleanOption.USE_ANDROID_X, true).run("app:assembleDebug")
    }

    @Test
    fun testKaptDsl() {
        val app = project.getSubproject(":app")
        // Enable caching of kapt task
        TestFileUtils.appendToFile(
            app.buildFile,
            // language=groovy
            """
                kapt {
                    useBuildCache = true
                }
                """.trimIndent()
        )
        TestFileUtils.appendToFile(project.gradlePropertiesFile, "org.gradle.caching=true")

        val executor =
            project.executor()
                .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
        // test for caching when useBuildCache = true
        executor.run("app:kaptDebugKotlin")
        assertThat(project.buildResult.didWorkTasks).contains(":app:kaptDebugKotlin")
        executor.run("clean", "app:kaptDebugKotlin")
        assertThat(project.buildResult.fromCacheTasks).contains(":app:kaptDebugKotlin")

        // test no caching when useBuildCache = false
        TestFileUtils.searchAndReplace(
            app.buildFile,
            "useBuildCache = true",
            "useBuildCache = false"
        )
        executor.run("app:kaptDebugKotlin")
        executor.run("clean", "app:kaptDebugKotlin")
        assertThat(project.buildResult.fromCacheTasks).doesNotContain(":app:kaptDebugKotlin")
    }
}
