/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.testing.screenshot

import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.LoggingLevel
import com.android.build.gradle.integration.common.fixture.ProfileCapturer
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.integration.common.truth.forEachLine
import com.android.build.gradle.internal.TaskManager
import com.android.build.gradle.options.BooleanOption
import com.android.compose.screenshot.gradle.ScreenshotTestOptions
import com.android.testutils.TestUtils
import com.android.testutils.truth.PathSubject.assertThat
import com.android.tools.build.gradle.internal.profile.GradleTaskExecutionType
import com.android.utils.usLocaleCapitalize
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.gradle.api.tasks.testing.TestDescriptor
import org.gradle.api.tasks.testing.TestListener
import org.gradle.api.tasks.testing.TestResult
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.UUID
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

class ScreenshotTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withProfileOutput()
        .from {
            androidApplication {
                setupProject()
            }
            androidLibrary {
                setupProject()
            }
            // All of these libraries will share the same class loader.
            // See b/340362066 for more details.
            repeat(2) {
                androidLibrary(":lib2_$it") {
                    setupProject(addEmptyJarToClassPath = false)
                }
            }

            gradleProperties {
                add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
            }
        }

    @JvmField
    @Rule
    val temporaryFolder = TemporaryFolder()

    private fun AndroidProjectDefinition<out CommonExtension<*,*,*,*,*,*>>.setupProject(addEmptyJarToClassPath: Boolean = true) {
        applyPlugin(PluginType.KOTLIN_ANDROID, TestUtils.KOTLIN_VERSION_FOR_COMPOSE_TESTS)
        applyPlugin(
            PluginType.Custom(
                id = "com.android.compose.screenshot",
                version = "+",
                artifact = "com.android.compose.screenshot:screenshot-test-gradle-plugin",
                hasMarker = false,
            )
        )

        if (addEmptyJarToClassPath) {
            val customJarName = UUID.randomUUID().toString() + ".jar"
            buildscript {
                classpath(localJar(customJarName) {
                    addEmptyClasses("RandomClass_${UUID.randomUUID()}")
                })
            }
        }
        android {
            defaultConfig {
                minSdk = 24
                testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
            }

            buildFeatures {
                compose = true
            }
            composeOptions {
                useLiveLiterals = false
                kotlinCompilerExtensionVersion = TestUtils.COMPOSE_COMPILER_FOR_TESTS
            }
            experimentalProperties["android.experimental.enableScreenshotTest"] = true
        }
        dependencies {
            testImplementation("junit:junit:4.13.2")
            implementation("androidx.compose.ui:ui-tooling:${TaskManager.COMPOSE_UI_VERSION}")
            implementation("androidx.compose.ui:ui-tooling-preview:${TaskManager.COMPOSE_UI_VERSION}")
            implementation("androidx.compose.material:material:${TaskManager.COMPOSE_UI_VERSION}")
        }
        kotlin {
            jvmToolchain(17)
        }
        pluginCallbacks += ScreenshotCallback::class.java

        files {
            add(
                "src/main/java/com/Example.kt",
                //language=kotlin
                """
                    package pkg.name

                    import androidx.compose.material.Text
                    import androidx.compose.runtime.Composable

                    @Composable
                    fun SimpleComposable(text: String = "Hello World") {
                        Text(text)
                    }
                """.trimIndent()
            )
            add(
                "src/main/java/com/ParameterProviders.kt",
                //language=kotlin
                """
                    package pkg.name

                    import androidx.compose.ui.tooling.preview.PreviewParameterProvider

                    class SimplePreviewParameterProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf(
                            "Primary text", "Secondary text"
                        )
                    }
                """.trimIndent()
            )
            add(
                "src/screenshotTest/java/com/AnotherPreviewParameterProvider.kt",
                //language=kotlin
                """
                    package pkg.name

                    import androidx.compose.ui.tooling.preview.PreviewParameterProvider

                    class AnotherPreviewParameterProvider : PreviewParameterProvider<String> {
                        override val values = sequenceOf(
                            "text 1", "text 2"
                        )
                    }
                """.trimIndent()
            )
            add(
                "src/screenshotTest/java/com/ExampleTest.kt",
                //language=kotlin
                """
                    package pkg.name

                    import androidx.compose.ui.tooling.preview.Preview
                    import androidx.compose.ui.tooling.preview.PreviewParameter
                    import androidx.compose.runtime.Composable

                    class ExampleTest {
                        @Preview(name = "simpleComposable", showBackground = true)
                        @Composable
                        fun simpleComposableTest() {
                            SimpleComposable()
                        }

                        @Preview(name = "simpleComposable", widthDp = 800, heightDp = 800)
                        @Composable
                        fun simpleComposableTest2() {
                            SimpleComposable()
                        }

                        @Preview(name = "with_Background", showBackground = true)
                        @Preview(name = "withoutBackground", showBackground = false)
                        @Composable
                        fun multiPreviewTest() {
                            SimpleComposable()
                        }

                        @Preview(name = "simplePreviewParameterProvider")
                        @Composable
                        fun parameterProviderTest(
                            @PreviewParameter(SimplePreviewParameterProvider::class) data: String
                        ) {
                           SimpleComposable(data)
                        }

                        @Preview(name = "invalid/File/Name")
                        @Composable
                        fun previewNameCannotBeUsedAsFileNameTest() {
                            SimpleComposable()
                        }
                    }
                """.trimIndent()
            )
            add(
                "src/screenshotTest/java/com/TopLevelPreviewTest.kt",
                //language=kotlin
                """
                    package pkg.name

                    import androidx.compose.ui.tooling.preview.Preview
                    import androidx.compose.ui.tooling.preview.PreviewParameter
                    import androidx.compose.runtime.Composable

                    @Preview(showBackground = true)
                    @Composable
                    fun simpleComposableTest_3() {
                        SimpleComposable()
                    }
                """.trimIndent()
            )
        }
    }

    class ScreenshotCallback: GenericCallback {
        override fun handleProject(project: Project) {
            println("Class loader for AGP API = " + com.android.build.api.variant.AndroidComponentsExtension::class.java.getClassLoader().hashCode())

            // Add test listener to log additional test results for easier debugging when tests failed.
            project.tasks.withType(org.gradle.api.tasks.testing.Test::class.java) {
                it.addTestListener(object: TestListener {
                    override fun beforeSuite(suite: TestDescriptor) {
                        println("Starting test suite: ${suite.name}")
                    }

                    override fun afterSuite(suite: TestDescriptor, result: TestResult) {
                        println("Finished test suite: ${suite.name} with result: ${result.resultType}")
                        result.exception?.printStackTrace()
                    }

                    override fun beforeTest(testDescriptor: TestDescriptor) {
                        println("Starting test: ${testDescriptor.name}")
                    }

                    override fun afterTest(testDescriptor: TestDescriptor, result: TestResult) {
                        println("Finished test: ${testDescriptor.name} with result: ${result.resultType}")
                        result.exception?.printStackTrace()
                    }
                })
            }
        }
    }

    // custom executor configuration for screenshotTesting (sst)
    private fun GradleBuild.sstExecutor(): GradleTaskExecutor =
        executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .with(BooleanOption.USE_ANDROID_X, true)
            .withLoggingLevel(LoggingLevel.LIFECYCLE)

    @Test
    fun runPreviewScreenshotTestWithThreshold() {
        val build = rule.build
        val appProject = build.androidApplication()

        updateReferenceImage()
        //update the preview - tests fail
        appProject.files.update("src/main/java/com/Example.kt")
            .searchAndReplace("Hello World", "Hello Worid")

        val result = build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")
        result.assertErrorContains("There were failing tests. See the results at: ")

        //set high threshold - tests pass
        appProject.reconfigure {
            android {
                testOptions {
                    // ScreenshotTestOptions is not yet part of the AGP API so use it via the
                    // extension mechanism
                    viaExtension("screenshotTests", ScreenshotTestOptions::class) {
                        imageDifferenceThreshold = 0.5f
                    }
                }
            }
        }

        build.sstExecutor().run(":app:validateDebugScreenshotTest")

        //reduce threshold - tests fail
        appProject.reconfigure {
            android {
                testOptions {
                    viaExtension("screenshotTests", ScreenshotTestOptions::class) {
                        imageDifferenceThreshold = 0.001f
                    }
                }
            }
        }

        val resultLowThreshold = build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")
        resultLowThreshold.assertErrorContains("There were failing tests. See the results at: ")
    }

    private fun updateReferenceImage(buildType: String = "debug", flavor: String = "", projectName: String = "app"): GradleBuildResult {
        val build = rule.build
        val variantName = if (flavor.isEmpty()) {
            buildType
        } else {
            flavor + buildType.usLocaleCapitalize()
        }
        val result = build.sstExecutor().expectFailure().run(
            ":$projectName:validate${variantName.usLocaleCapitalize()}ScreenshotTest")

        val previewDir = build.directory.resolve(
            "$projectName/build/outputs/screenshotTest-results/preview/$buildType/$flavor/rendered").toFile()
        val refDir = build.directory.resolve("$projectName/src/${variantName}ScreenshotTest/reference").toFile()

        assertTrue(
            "Failed to update reference images",
            previewDir.copyRecursively(refDir, overwrite = true))

        return result
    }

    private fun updateReferenceImageForAllProjects(variantName: String = "debug"): GradleBuildResult {
        val build = rule.build
        val result = build.sstExecutor().expectFailure().run(
            "validate${variantName.usLocaleCapitalize()}ScreenshotTest")

        for (projectName in listOf("app", "lib", "lib2_0", "lib2_1")) {
            val previewDir = build.directory.resolve(
                "$projectName/build/outputs/screenshotTest-results/preview/$variantName/rendered"
            ).toFile()
            val refDir =
                build.directory.resolve("$projectName/src/${variantName}ScreenshotTest/reference")
                    .toFile()

            assertTrue(
                "Failed to update reference images",
                previewDir.copyRecursively(refDir, overwrite = true)
            )
        }
        return result
    }

    @Test
    fun runPreviewScreenshotTest() {
        val build = rule.build
        val appProject = build.androidApplication()

        // Generate screenshots to be tested against
        updateReferenceImage()

        val exampleTestReferenceScreenshotDir = appProject.resolve("src/debugScreenshotTest/reference/pkg/name/ExampleTest")
        val topLevelTestReferenceScreenshotDir = appProject.resolve("src/debugScreenshotTest/reference/pkg/name/TopLevelPreviewTestKt")
        assertThat(exampleTestReferenceScreenshotDir.listDirectoryEntries().map { it.name }).containsExactly(
            "simpleComposableTest_simpleComposable_c5877f71_0.png",
            "simpleComposableTest2_simpleComposable_7362dd6b_0.png",
            "multiPreviewTest_with_Background_6d9364e2_0.png",
            "multiPreviewTest_withoutBackground_3619adf7_0.png",
            "parameterProviderTest_simplePreviewParameterProvider_893e015e_b983d6d8_1.png",
            "parameterProviderTest_simplePreviewParameterProvider_893e015e_b983d6d8_0.png",
            "previewNameCannotBeUsedAsFileNameTest_aa50de45_0.png",
        )
        assertThat(topLevelTestReferenceScreenshotDir.listDirectoryEntries().map { it.name }).containsExactly(
            "simpleComposableTest_3_748aa731_0.png"
        )

        // Validate previews matches screenshots
        build.sstExecutor().run(":app:validateDebugScreenshotTest")

        // Verify that HTML reports are generated and all tests pass
        val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
        val classHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.ExampleTest.html")
        val class2HtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.TopLevelPreviewTestKt.html")
        val packageHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.html")
        assertThat(indexHtmlReport).exists()
        assertThat(classHtmlReport).exists()
        val expectedOutput = listOf(
            """<h3 class="success">simpleComposableTest_simpleComposable</h3>""",
            """<h3 class="success">simpleComposableTest2_simpleComposable</h3>""",
            """<h3 class="success">multiPreviewTest_with_Background_{showBackground=true}</h3>""",
            """<h3 class="success">multiPreviewTest_withoutBackground_{showBackground=false}</h3>""",
            """{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
            """{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>""",
            """<h3 class="success">previewNameCannotBeUsedAsFileNameTest_invalid/File/Name</h3>""",
            """<h3 class="success">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
            """<h3 class="success">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>"""
        )
        var classHtmlReportText = classHtmlReport.readText()
        expectedOutput.forEach { assertThat(classHtmlReportText).contains(it) }
        assertThat(class2HtmlReport.readText()).contains("""<h3 class="success">simpleComposableTest_3</h3>""")
        assertThat(packageHtmlReport).exists()

        // Assert that no diff images were generated because screenshot matched the reference image
        val exampleTestDiffDir = appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/diffs/pkg/name/ExampleTest")
        val topLevelTestDiffDir = appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/diffs/pkg/name/TopLevelPreviewTestKt")
        assert(exampleTestDiffDir.listDirectoryEntries().isEmpty())
        assert(topLevelTestDiffDir.listDirectoryEntries().isEmpty())

        // Update previews to be different from the references
        appProject.files.apply {
            update("src/main/java/com/Example.kt")
                .searchAndReplace("Hello World", "HelloWorld ")
            update("src/main/java/com/ParameterProviders.kt")
                .searchAndReplace("Primary text", " Primarytext")
        }

        // Rerun validation task - modified tests should fail and diffs are generated
        build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")

        assertThat(indexHtmlReport).exists()
        assertThat(classHtmlReport).exists()
        val expectedOutputAfterChangingPreviews = listOf(
            "Failed tests",
            """<h3 class="failures">simpleComposableTest_simpleComposable</h3>""",
            """<h3 class="failures">simpleComposableTest2_simpleComposable</h3>""",
            """<h3 class="failures">multiPreviewTest_with_Background_{showBackground=true}</h3>""",
            """<h3 class="failures">multiPreviewTest_withoutBackground_{showBackground=false}</h3>""",
            """{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
            """{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>""",
            """<h3 class="failures">previewNameCannotBeUsedAsFileNameTest_invalid/File/Name</h3>""",
            """<h3 class="failures">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
            """<h3 class="success">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>"""
        )
        classHtmlReportText = classHtmlReport.readText()
        expectedOutputAfterChangingPreviews.forEach { assertThat(classHtmlReportText).contains(it) }
        assertThat(class2HtmlReport.readText()).contains("""<h3 class="failures">simpleComposableTest_3</h3>""")
        assertThat(packageHtmlReport).exists()

        assertThat(exampleTestDiffDir.listDirectoryEntries().map { it.name }).containsExactly(
            "simpleComposableTest_simpleComposable_c5877f71_0.png",
            "simpleComposableTest2_simpleComposable_7362dd6b_0.png",
            "multiPreviewTest_with_Background_6d9364e2_0.png",
            "multiPreviewTest_withoutBackground_3619adf7_0.png",
            "parameterProviderTest_simplePreviewParameterProvider_893e015e_b983d6d8_0.png",
            "previewNameCannotBeUsedAsFileNameTest_aa50de45_0.png",
        )
        assertThat(topLevelTestDiffDir.listDirectoryEntries().map { it.name }).containsExactly(
            "simpleComposableTest_3_748aa731_0.png"
        )
    }

    @Test
    fun runPreviewScreenshotTestWithMultiModuleProject() {
        val build = rule.build {
            useOldPluginStyleForSeparateClassloaders = true
        }
        // Generate screenshots to be tested against
        verifyClassLoaderSetup(updateReferenceImageForAllProjects())

        // Validate previews matches screenshots
        build.sstExecutor().run("validateDebugScreenshotTest")
    }

    @Test
    fun runUpdateScreenshotTestWithMultiModuleProjectBySingleWorker() {
        // Generate screenshots to be tested against
        updateReferenceImageForAllProjects()

        // Set the max workers to 1 to let Gradle reuse the same worker daemon process for
        // running TestEngine more than once. See b/340362066 for more details.
        rule.build.sstExecutor().withArguments(listOf("--max-workers", "1")).run("validateDebugScreenshotTest")
    }

    private fun verifyClassLoaderSetup(result: GradleBuildResult) {
        val taskLogs = mutableSetOf<String>()
        result.stdout.forEachLine {
            if (it.startsWith("Class loader for AGP API = ")) {
                taskLogs.add(it)
            }
        }
        assertThat(taskLogs)
                .named("Log lines that should contain different class loader hashes")
                .hasSize(3)
    }

    @Test
    fun analytics() {
        val build = rule.build
        val capturer = ProfileCapturer(build)

        val profiles = capturer.capture {
            updateReferenceImage()
        }

        profiles.mapNotNull { profile ->
            val spanList = profile.spanList
            val taskSpan = spanList.firstOrNull {
                it.task.type == GradleTaskExecutionType.PREVIEW_SCREENSHOT_VALIDATION_VALUE
            } ?: return@mapNotNull null
            taskSpan.durationInMs
        }.first { durationInMs ->
            durationInMs > 0L
        }
    }

    @Test
    fun runPreviewScreenshotTestWithNoSourceFiles() {
        val build = rule.build {
            androidApplication {
                // Delete test classes so that there are no source files in screenshotTest source set
                files {
                    remove("src/screenshotTest/java/com/ExampleTest.kt")
                    remove("src/screenshotTest/java/com/TopLevelPreviewTest.kt")
                    remove("src/screenshotTest/java/com/AnotherPreviewParameterProvider.kt")
                }
            }
        }
        val appProject = build.androidApplication()

        // Validation is skipped when there are no source files
        val result = build.sstExecutor().run(":app:validateDebugScreenshotTest")
        assertThat(result.skippedTasks).containsAtLeastElementsIn(
            listOf(":app:validateDebugScreenshotTest", ":app:debugScreenshotReport")
        )

        val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
        assertThat(indexHtmlReport).doesNotExist()
    }

    @Test
    fun runPreviewScreenshotTestWithSourceFilesAndNoPreviewsToTest() {
        val build = rule.build {
            androidApplication {
                // Comment out preview tests so that source files exist with no previews to test
                files {
                    update("src/screenshotTest/java/com/ExampleTest.kt")
                        .transform { """
                            /*
                            $it
                            */
                        """.trimIndent() }
                    update("src/screenshotTest/java/com/TopLevelPreviewTest.kt")
                        .transform { """
                            /*
                            $it
                            */
                        """.trimIndent() }
                }
            }
        }
        val appProject = build.androidApplication()

        // Gradle test tasks fail when there are source files but no tests are executed starting in Gradle 9.0
        build.sstExecutor()
            .expectFailure()
            .run(":app:validateDebugScreenshotTest")

        val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
        assertThat(indexHtmlReport).exists()
    }

    @Test
    fun runPreviewScreenshotTestsWithMissingUiToolingDep() {
        val uiToolingDep = "androidx.compose.ui:ui-tooling:${TaskManager.COMPOSE_UI_VERSION}"
        val build = rule.build {
            androidApplication {
                dependencies {
                    // Verify that no exception is thrown when ui-tooling is added as an screenshotTestImplementation dependency
                    remove("implementation", uiToolingDep)
                    screenshotTestImplementation(uiToolingDep)
                }
            }
        }

        updateReferenceImage()

        // Verify that exception is thrown when ui-tooling dep is missing
        build.androidApplication().reconfigure {
            dependencies {
                remove("screenshotTestImplementation", uiToolingDep)
            }
        }

        val result = build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")
        result.assertErrorContains("Missing required runtime dependency. Please add androidx.compose.ui:ui-tooling as a screenshotTestImplementation dependency.")
    }

    @Test
    fun runScreenshotTestWithEmptyPreview() {
        val build = rule.build {
            androidApplication {
                files.update("src/screenshotTest/java/com/TopLevelPreviewTest.kt")
                    .searchAndReplace("SimpleComposable()", "")
            }
        }
        updateReferenceImage()
        build.sstExecutor().run(":app:validateDebugScreenshotTest")
    }

    @Test
    fun runPreviewScreenshotTestsOnMultipleFlavors() {
        val build = rule.build {
            androidApplication {
                android {
                    flavorDimensions += "new"
                    productFlavors {
                        create("flavor1") {
                            it.dimension = "new"
                        }
                        create("flavor2") {
                            it.dimension = "new"
                        }
                    }
                }
                // Comment out the previews in ExampleTest to limit this test to running on the preview in TopLevelPreviewTest
                files.update("src/screenshotTest/java/com/ExampleTest.kt").transform {
                    """
                        /*
                        $it
                        */
                    """.trimIndent()
                }
            }
        }
        val appProject = build.androidApplication()

        updateReferenceImage("debug", "flavor1")
        updateReferenceImage("debug", "flavor2")

        // Verify that reference images are created for both flavors
        val flavor1ReferenceScreenshotDir = appProject.resolve("src/flavor1DebugScreenshotTest/reference/pkg/name/TopLevelPreviewTestKt")
        val flavor2ReferenceScreenshotDir = appProject.resolve("src/flavor2DebugScreenshotTest/reference/pkg/name/TopLevelPreviewTestKt")
        assertThat(flavor1ReferenceScreenshotDir.listDirectoryEntries().single().name)
            .isEqualTo("simpleComposableTest_3_748aa731_0.png")
        assertThat(flavor2ReferenceScreenshotDir.listDirectoryEntries().single().name)
            .isEqualTo("simpleComposableTest_3_748aa731_0.png")

        build.sstExecutor().run(":app:validateScreenshotTest")

        // Verify that HTML reports are generated for each flavor and all tests pass
        val flavor1IndexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor1/index.html")
        val flavor2IndexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor2/index.html")
        val flavor1ClassHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor1/pkg.name.TopLevelPreviewTestKt.html")
        val flavor2ClassHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor2/pkg.name.TopLevelPreviewTestKt.html")
        val flavor1PackageHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor1/pkg.name.html")
        val flavor2PackageHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/flavor2/pkg.name.html")
        assertThat(flavor1IndexHtmlReport).exists()
        assertThat(flavor2IndexHtmlReport).exists()
        assertThat(flavor1ClassHtmlReport).exists()
        assertThat(flavor2ClassHtmlReport).exists()
        val expectedOutput = listOf(
            """<h3 class="success">simpleComposableTest_3</h3>""",
        )
        expectedOutput.forEach {
            assertThat(flavor1ClassHtmlReport.readText()).contains(it)
            assertThat(flavor2ClassHtmlReport.readText()).contains(it)
        }
        assertThat(flavor1PackageHtmlReport).exists()
        assertThat(flavor2PackageHtmlReport).exists()

        // Assert that no diff images were generated because screenshots matched the reference images
        val diffDir1 = appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/flavor1/diffs/pkg/name/TopLevelPreviewTestKt")
        val diffDir2 = appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/flavor2/diffs/pkg/name/TopLevelPreviewTestKt")
        assert(diffDir1.listDirectoryEntries().isEmpty())
        assert(diffDir2.listDirectoryEntries().isEmpty())
    }

    @Test
    fun runPreviewScreenshotTestWithFilter() {
        val build = rule.build {
            androidApplication {
                // cannot set filter using the conventional command ./gradlew validateDebugScreenshotTest --tests "Pattern". https://github.com/gradle/gradle/issues/1228
                pluginCallbacks += FilterSetupCallback::class.java
            }
        }
        val appProject = build.androidApplication()

        // Generate screenshots to be tested against
        updateReferenceImage()
        val exampleTestReferenceScreenshotDir = appProject.resolve("src/debugScreenshotTest/reference/pkg/name/ExampleTest")
        val topLevelTestReferenceScreenshotDir = appProject.resolve("src/debugScreenshotTest/reference/pkg/name/TopLevelPreviewTestKt")
        assertThat(exampleTestReferenceScreenshotDir.listDirectoryEntries().map { it.name }).containsExactly(
            "simpleComposableTest_simpleComposable_c5877f71_0.png",
            "simpleComposableTest2_simpleComposable_7362dd6b_0.png",
        )
        assertThat(topLevelTestReferenceScreenshotDir.listDirectoryEntries().map { it.name }).containsExactly(
            "simpleComposableTest_3_748aa731_0.png"
        )

        // Validate previews matches screenshots
        build.sstExecutor().run(":app:validateDebugScreenshotTest")

        // Verify that HTML reports are generated and all tests pass
        val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
        val classHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.ExampleTest.html")
        val class2HtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.TopLevelPreviewTestKt.html")
        val packageHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.html")
        assertThat(indexHtmlReport).exists()
        assertThat(classHtmlReport).exists()
        val expectedOutput = listOf(
            """<h3 class="success">simpleComposableTest_simpleComposable</h3>""",
            """<h3 class="success">simpleComposableTest2_simpleComposable</h3>"""
        )
        val unExpectedOutput = listOf(
            """<h3 class="success">multiPreviewTest_with_Background_{showBackground=true}</h3>""",
            """<h3 class="success">multiPreviewTest_withoutBackground_{showBackground=false}</h3>""",
            """{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
            """{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>""",
            """<h3 class="success">previewNameCannotBeUsedAsFileNameTest_invalid/File/Name</h3>""",
            """<h3 class="success">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
            """<h3 class="success">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>"""
        )
        var classHtmlReportText = classHtmlReport.readText()
        expectedOutput.forEach { assertThat(classHtmlReportText).contains(it) }
        unExpectedOutput.forEach { assertThat(classHtmlReportText).doesNotContain(it) }
        assertThat(class2HtmlReport.readText()).contains("""<h3 class="success">simpleComposableTest_3</h3>""")
        assertThat(packageHtmlReport).exists()

        // Assert that no diff images were generated because screenshot matched the reference image
        val exampleTestDiffDir = appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/diffs/pkg/name/ExampleTest")
        val topLevelTestDiffDir = appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/diffs/pkg/name/TopLevelPreviewTestKt")
        assert(exampleTestDiffDir.listDirectoryEntries().isEmpty())
        assert(topLevelTestDiffDir.listDirectoryEntries().isEmpty())

        // Update previews to be different from the references
        appProject.files.apply {
            update("src/main/java/com/Example.kt")
                .searchAndReplace("Hello World", "HelloWorld ")
            update("src/main/java/com/ParameterProviders.kt")
                .searchAndReplace("Primary text", " Primarytext")
        }

        // Rerun validation task - modified tests should fail and diffs are generated
        build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")

        assertThat(exampleTestDiffDir.listDirectoryEntries().map { it.name }).containsExactly(
            "simpleComposableTest_simpleComposable_c5877f71_0.png",
            "simpleComposableTest2_simpleComposable_7362dd6b_0.png",
        )
    }

    class FilterSetupCallback: GenericCallback {
        override fun handleProject(project: Project) {
            project.afterEvaluate {
                project.tasks.named("validateDebugScreenshotTest", org.gradle.api.tasks.testing.Test::class.java) {
                    it.setTestNameIncludePatterns(listOf("*simpleComposableTest*"))
                }
            }
        }
    }
}
