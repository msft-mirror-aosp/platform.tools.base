/*
 * Copyright (C) 2026 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.internal.TaskManager
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.Rule
import org.junit.Test

/** Tests for screenshot test suites declared through `testOptions.screenshotTests`. */
class ScreenshotTestSuiteTest {

  @get:Rule
  val rule =
    GradleRule.configure().withProfileOutput().from {
      androidApplication { setupScreenshotTestSuiteProject() }
      androidLibrary { setupScreenshotTestSuiteProject() }
      // These two libraries have no module specific jar on their buildscript class path, so they share the same class loader.
      // See b/340362066 for more details.
      repeat(2) {
        androidLibrary(":lib2_$it") {
          setupScreenshotTestSuiteProject(addEmptyJarToClassPath = false)
        }
      }

      gradleProperties {
        add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
        add(BooleanOption.TEST_SUITE_SUPPORT, true)
      }
    }

  @Test
  fun runPreviewScreenshotTest() {
    val build = rule.build
    val appProject = build.androidApplication()
    val verifier = createVerifier(appProject)

    val indexHtmlReport = getTestSuiteHtmlReportIndexFile(appProject)
    val classHtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.ExampleTest")
    val class2HtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.TopLevelPreviewTestKt")
    val testNames =
      listOf(
        "simpleComposableTest",
        "simpleComposableTest2",
        "previewNameCannotBeUsedAsFileNameTest",
      )

    verifier.verifyPreviewScreenshotTest(
      appProject = appProject,
      updateTask = { build.updateReferenceImageWithTestSuite() },
      validateTask = { build.validateScreenshotTestWithTestSuite() },
      validateTaskExpectingFailure = {
        build.validateScreenshotTestWithTestSuite(expectFailure = true)
      },
      assertPassingHtmlReports = {
        assertThat(indexHtmlReport).exists()
        assertThat(classHtmlReport).exists()
        val classHtmlReportText = classHtmlReport.readText()
        testNames.forEach { assertThat(classHtmlReportText).contains(it) }
        class2HtmlReport.readText().let {
          assertThat(it).contains("simpleComposableTest_3")
        }
      },
      assertFailingHtmlReports = {
        assertThat(indexHtmlReport).exists()
        assertThat(classHtmlReport).exists()
        val classHtmlReportText = classHtmlReport.readText()
        testNames.forEach { assertThat(classHtmlReportText).contains(it) }
        class2HtmlReport.readText().let {
          assertThat(it).contains("simpleComposableTest_3")
        }
      },
    )
  }

  @Test
  fun runPreviewScreenshotTestWithFilter() {
    val build = rule.build {
      androidApplication { pluginCallbacks += FilterSetupWithTestSuiteCallback::class.java }
    }
    val appProject = build.androidApplication()
    val verifier = createVerifier(appProject)

    val indexHtmlReport = getTestSuiteHtmlReportIndexFile(appProject)
    val classHtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.ExampleTest")
    val class2HtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.TopLevelPreviewTestKt")

    verifier.verifyPreviewScreenshotTestWithFilter(
      appProject = appProject,
      updateTask = { build.updateReferenceImageWithTestSuite() },
      validateTask = { build.validateScreenshotTestWithTestSuite() },
      validateTaskExpectingFailure = {
        build.validateScreenshotTestWithTestSuite(expectFailure = true)
      },
      assertFilteredHtmlReports = {
        assertThat(indexHtmlReport).exists()
        assertThat(classHtmlReport).exists()
        assertClassHtmlReportContents(classHtmlReport, class2HtmlReport)
      },
    )
  }

  @Test
  fun runPreviewScreenshotTestWithNoMatchingFilter() {
    val build = rule.build {
      androidApplication {
        pluginCallbacks += FilterMatchingNothingWithTestSuiteCallback::class.java
      }
    }
    val appProject = build.androidApplication()
    val verifier = createVerifier(appProject)

    verifier.verifyPreviewScreenshotTestWithNoMatchingFilter(
      updateTask = { build.updateReferenceImageWithTestSuite() },
      validateTask = { build.validateScreenshotTestWithTestSuite() },
    )
  }

  @Test
  fun runPreviewScreenshotTestMissingReferences() {
    val build = rule.build
    val appProject = build.androidApplication()
    val verifier = createVerifier(appProject)

    verifier.verifyPreviewScreenshotTestMissingReferences(
      validateTaskExpectingFailure = {
        build.validateScreenshotTestWithTestSuite(expectFailure = true)
      }
    )
  }

  @Test
  fun runPreviewScreenshotTestWithUseSourceReferenceDirectoriesFalse() {
    val build = rule.build {
      gradleProperties {
        add(BooleanOption.SCREENSHOT_TEST_USE_SOURCE_REFERENCE_DIRECTORIES, false)
      }
    }
    val appProject = build.androidApplication()

    // 1. Running validate before update when useSourceReferenceDirectories=false should fail fast
    val missingRefResult = build.validateScreenshotTestWithTestSuite(expectFailure = true)
    missingRefResult.assertErrorContains("Local reference screenshot directory")
    missingRefResult.assertErrorContains("does not exist or is empty")
    missingRefResult.assertErrorContains("run the screenshot update task for variant 'debug' first")
    missingRefResult.assertErrorContains("android.screenshotTest.useSourceReferenceDirectories=false")

    // 2. Running update writes reference images to build/outputs/screenshotTest-results/.../reference and not src/.../reference
    val updateResult = build.updateReferenceImageWithTestSuite()
    updateResult.assertTask(getTestSuiteUpdateTaskName()).didWork()

    val sourceRefDir = getTestSuiteReferenceDir(appProject, "pkg.name.ExampleTest")
    assertThat(sourceRefDir).doesNotExist()

    val localRefDir =
      appProject.buildDir.resolve("outputs/screenshotTest-results/screenshotTestDefaultDebug/reference/pkg/name/ExampleTest")
    assertThat(localRefDir.listDirectoryEntries().map { it.name }).containsExactlyElementsIn(EXPECTED_EXAMPLE_TEST_REFERENCE_IMAGES)

    // 3. Subsequent validate hits configuration cache and passes against local reference images
    val secondValidate = build.validateScreenshotTestWithTestSuite()
    secondValidate.assertTask(getTestSuiteValidateTaskName()).didWork()
    secondValidate.assertConfigurationCacheHit()
  }

  @Test
  fun runPreviewScreenshotTestInAllProjects() {
    val build = rule.build

    val updateResult = build.updateReferenceImageForAllProjectsWithTestSuite()

    ALL_MODULES.forEach { module ->
      updateResult.assertTask(getTestSuiteUpdateTaskName(projectName = module)).didWork()
      val referenceDir = getTestSuiteReferenceDir(build.subProject(":$module"), "pkg.name.ExampleTest")
      assertThat(referenceDir.listDirectoryEntries().map { it.name }).containsExactlyElementsIn(EXPECTED_EXAMPLE_TEST_REFERENCE_IMAGES)
    }

    val validateResult = build.validateScreenshotTestForAllProjectsWithTestSuite()

    ALL_MODULES.forEach { module -> validateResult.assertTask(getTestSuiteValidateTaskName(projectName = module)).didWork() }

    val layoutlibDataDirs = ALL_MODULES.map { getLayoutlibDataDir(build.subProject(":$it")) }
    assertThat(layoutlibDataDirs.toSet()).hasSize(1)
    layoutlibDataDirs.forEach { assertThat(it.resolve(FRAMEWORK_RES_JAR)).isFile() }
  }

  @Test
  fun runPreviewScreenshotTestOnReleaseVariant() {
    val build = rule.build
    val appProject = build.androidApplication()

    build.updateReferenceImageWithTestSuite(variantName = RELEASE)
    val referenceDir = getTestSuiteReferenceDir(appProject, "pkg.name.ExampleTest", variantName = RELEASE)
    assertThat(referenceDir.listDirectoryEntries().map { it.name }).containsExactlyElementsIn(EXPECTED_EXAMPLE_TEST_REFERENCE_IMAGES)

    val validateResult = build.validateScreenshotTestWithTestSuite(variantName = RELEASE)
    validateResult.assertTask(getTestSuiteValidateTaskName(variantName = RELEASE)).didWork()

    val releaseLayoutlibDataDir = getLayoutlibDataDir(appProject, variantName = RELEASE)
    assertThat(releaseLayoutlibDataDir.resolve(FRAMEWORK_RES_JAR)).isFile()
  }

  @Test
  fun validationCanBeRepeatedWithoutRedoingTheLayoutlibExtraction() {
    val build = rule.build
    val appProject = build.androidApplication()
    val validateTaskName = getTestSuiteValidateTaskName()

    build.updateReferenceImageWithTestSuite()
    build.validateScreenshotTestWithTestSuite().assertTask(validateTaskName).didWork()

    val layoutlibDataDir = getLayoutlibDataDir(appProject)
    assertThat(layoutlibDataDir.resolve(FRAMEWORK_RES_JAR)).isFile()
    val extractedLayoutlib = snapshotDirectory(layoutlibDataDir)

    val secondRun = build.validateScreenshotTestWithTestSuite()

    secondRun.assertTask(validateTaskName).didWork()
    secondRun.assertConfigurationCacheHit()
    assertThat(getLayoutlibDataDir(appProject)).isEqualTo(layoutlibDataDir)
    assertThat(snapshotDirectory(layoutlibDataDir)).isEqualTo(extractedLayoutlib)
  }

  @Test
  fun runPreviewScreenshotTestWithParameterProviderDisplayName() {
    val build = rule.build {
      androidApplication {
        files {
          add(
            "src/main/java/com/DisplayNameParameterProviders.kt",
            """
            package pkg.name

            import androidx.compose.ui.tooling.preview.PreviewParameterProvider

            class CustomDisplayNameParameterProvider : PreviewParameterProvider<String> {
                override val values = sequenceOf(
                    "Primary text",
                    "Secondary text",
                    "Tertiary text",
                    "Quaternary text",
                    "Fifth text",
                    "Sixth text",
                    "Seventh text",
                    "Eighth text"
                )

                fun getDisplayName(index: Int): String? {
                    return when (index) {
                        0 -> "custom_first"
                        1 -> "custom name with spaces"
                        2 -> ""
                        3 -> null
                        4 -> "custom_emoji_🚀"
                        5 -> "custom\\backslash"
                        6 -> "duplicate_name"
                        7 -> "duplicate_name"
                        else -> null
                    }
                }
            }
            """
              .trimIndent(),
          )
          add(
            "src/screenshotTest/java/com/DisplayNameTest.kt",
            """
            package pkg.name

            import androidx.compose.ui.tooling.preview.Preview
            import androidx.compose.ui.tooling.preview.PreviewParameter
            import androidx.compose.runtime.Composable
            import com.android.tools.screenshot.PreviewTest

            class DisplayNameTest {
                @PreviewTest
                @Preview(name = "customProviderPreview")
                @Composable
                fun parameterProviderWithDisplayNameTest(
                    @PreviewParameter(CustomDisplayNameParameterProvider::class) data: String
                ) {
                    SimpleComposable(data)
                }
            }
            """
              .trimIndent(),
          )
        }
      }
    }
    val appProject = build.androidApplication()

    build.updateReferenceImageWithTestSuite()

    val referenceDir = getTestSuiteReferenceDir(appProject, "pkg.name.DisplayNameTest")
    val referenceFiles = referenceDir.listDirectoryEntries().map { it.name }
    assertThat(referenceFiles).hasSize(8)
    assertThat(referenceFiles.any { it.endsWith("_custom_first.png") }).isTrue()
    assertThat(referenceFiles.any { it.endsWith("_custom_name_with_spaces.png") }).isTrue()
    assertThat(referenceFiles.any { it.endsWith("_2.png") }).isTrue()
    assertThat(referenceFiles.any { it.endsWith("_3.png") }).isTrue()
    assertThat(referenceFiles.any { it.endsWith("_custom_emoji_🚀.png") }).isTrue()
    assertThat(referenceFiles.any { it.endsWith("_custom_backslash.png") }).isTrue()
    assertThat(referenceFiles.any { it.endsWith("_duplicate_name_6.png") }).isTrue()
    assertThat(referenceFiles.any { it.endsWith("_duplicate_name_7.png") }).isTrue()
    assertThat(referenceFiles.none { it.endsWith("_0.png") }).isTrue()
    assertThat(referenceFiles.none { it.endsWith("_1.png") }).isTrue()

    val validateResult = build.validateScreenshotTestWithTestSuite()
    validateResult.assertTask(getTestSuiteValidateTaskName()).didWork()

    val classHtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.DisplayNameTest")
    assertThat(classHtmlReport).exists()
    val classHtmlReportText = classHtmlReport.readText()
    assertThat(classHtmlReportText).contains("parameterProviderWithDisplayNameTest")

    val dataJs = getTestSuiteHtmlReportDir(appProject).resolve("data.js")
    assertThat(dataJs).exists()
    val dataJsText = dataJs.readText()
    assertThat(dataJsText).contains("custom_first")
    assertThat(dataJsText).contains("custom name with spaces")
    assertThat(dataJsText).contains("_[{provider\\u003dpkg.name.CustomDisplayNameParameterProvider}]_custom_first")
    assertThat(dataJsText).contains("_[{provider\\u003dpkg.name.CustomDisplayNameParameterProvider}]_custom name with spaces")
    assertThat(dataJsText).contains("_[{provider\\u003dpkg.name.CustomDisplayNameParameterProvider}]_2")
    assertThat(dataJsText).contains("_[{provider\\u003dpkg.name.CustomDisplayNameParameterProvider}]_3")
    assertThat(dataJsText.contains("custom_emoji_🚀") || dataJsText.contains("custom_emoji_\\ud83d\\ude80")).isTrue()
    assertThat(dataJsText.contains("custom\\backslash") || dataJsText.contains("custom\\\\backslash")).isTrue()
    assertThat(dataJsText).contains("_[{provider\\u003dpkg.name.CustomDisplayNameParameterProvider}]_duplicate_name_6")
    assertThat(dataJsText).contains("_[{provider\\u003dpkg.name.CustomDisplayNameParameterProvider}]_duplicate_name_7")
    assertThat(dataJsText).doesNotContain("_[{provider\\u003dpkg.name.CustomDisplayNameParameterProvider}]_0")
    assertThat(dataJsText).doesNotContain("_[{provider\\u003dpkg.name.CustomDisplayNameParameterProvider}]_1")

    appProject.files.update("src/main/java/com/DisplayNameParameterProviders.kt").searchAndReplace("Primary text", " Primarytext")

    build.validateScreenshotTestWithTestSuite(expectFailure = true)

    val diffDir = getTestSuiteDiffDir(appProject, "pkg.name.DisplayNameTest")
    val diffFiles = diffDir.listDirectoryEntries().map { it.name }
    assertThat(diffFiles.any { it.endsWith("_custom_first.png") }).isTrue()
    assertThat(diffFiles.none { it.endsWith("_custom_name_with_spaces.png") }).isTrue()
    assertThat(diffFiles.none { it.endsWith("_duplicate_name_6.png") }).isTrue()
    assertThat(diffFiles.none { it.endsWith("_duplicate_name_7.png") }).isTrue()
  }

  @Test
  fun previewsCanReadTheSuiteAndModuleJavaResources() {
    val build = rule.build {
      androidLibrary {
        files {
          add("src/screenshotTest/resources/suite_label.txt", "Label from the suite's Java resources")
          add("src/main/resources/main_label.txt", "Label from the library's Java resources")
          add(
            "src/screenshotTest/java/com/SuiteJavaResourceTest.kt",
            """
            package pkg.name

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview
            import com.android.tools.screenshot.PreviewTest

            class SuiteJavaResourceTest {
                @PreviewTest
                @Preview(showBackground = true)
                @Composable
                fun suiteJavaResourceTest() {
                    SimpleComposable(readLabel("/suite_label.txt"))
                }

                @PreviewTest
                @Preview(showBackground = true)
                @Composable
                fun mainJavaResourceTest() {
                    SimpleComposable(readLabel("/main_label.txt"))
                }

                private fun readLabel(name: String): String =
                    checkNotNull(SuiteJavaResourceTest::class.java.getResourceAsStream(name)) {
                        "${'$'}name is not visible to the preview"
                    }.bufferedReader().use { it.readText() }
            }
            """
              .trimIndent(),
          )
        }
      }
    }
    val libProject = build.androidLibrary()

    build.updateReferenceImageWithTestSuite(projectName = "lib")

    val referenceDir = getTestSuiteReferenceDir(libProject, "pkg.name.SuiteJavaResourceTest")
    assertThat(referenceDir.listDirectoryEntries().map { it.name })
      .containsExactly("suiteJavaResourceTest_748aa731_0.png", "mainJavaResourceTest_748aa731_0.png")
    build.validateScreenshotTestWithTestSuite(projectName = "lib")
  }

  /**
   * Previews of a library that use what a design system or feature module typically has: a multipreview annotation, an `internal`
   * composable, a string and a drawable of the library's own R class, an asset of the library, and, from a dependency, a Java resource, a
   * multipreview annotation and a preview parameter provider. And previews of an application whose main code uses its own strings and the
   * strings of a library it depends on.
   */
  @Test
  fun previewsUseTheTestedModuleAndItsDependencies() {
    val build = rule.build {
      // A library with strings, Java resources and preview helpers: a UI module that features share.
      androidLibrary(":ui") {
        dependencies { implementation("androidx.compose.ui:ui-tooling-preview:${TaskManager.COMPOSE_UI_VERSION}") }
        kotlin { jvmToolchain(17) }
        files {
          add("src/main/res/values/strings.xml", "<resources><string name=\"feature_title\">Feature</string></resources>")
          add("src/main/resources/dependency_label.txt", "Label from a dependency's Java resources")
          add(
            "src/main/java/com/PreviewHelpers.kt",
            """
            package pkg.name.ui

            import androidx.compose.ui.tooling.preview.Preview
            import androidx.compose.ui.tooling.preview.PreviewParameterProvider

            @Preview(name = "phone", widthDp = 360, heightDp = 120, showBackground = true)
            @Preview(name = "tablet", widthDp = 840, heightDp = 120, showBackground = true)
            annotation class DevicePreviews

            class FeatureTitlesPreviewParameterProvider : PreviewParameterProvider<String> {
                override val values = sequenceOf("First title", "Second title")
            }
            """
              .trimIndent(),
          )
        }
      }
      androidLibrary {
        dependencies { implementation(project(":ui")) }
        files {
          add("src/main/res/values/strings.xml", "<resources><string name=\"lib_title\">Library</string></resources>")
          add("src/main/assets/lib_asset.txt", "Text from the library's assets")
          add(
            "src/main/res/drawable/lib_square.xml",
            """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp" android:height="24dp" android:viewportWidth="24" android:viewportHeight="24">
                <path android:fillColor="#FF0000" android:pathData="M0,0h24v24h-24z" />
            </vector>
            """
              .trimIndent(),
          )
          add(
            "src/main/java/com/LibComponents.kt",
            """
            package pkg.name.lib

            import android.content.res.Configuration
            import androidx.compose.foundation.Image
            import androidx.compose.material.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.res.painterResource
            import androidx.compose.ui.res.stringResource
            import androidx.compose.ui.tooling.preview.Preview

            @Preview(name = "light", showBackground = true)
            @Preview(name = "dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
            annotation class LibThemePreviews

            @Composable
            internal fun LibTitle() {
                Text(stringResource(R.string.lib_title))
            }

            @Composable
            fun LibIcon() {
                Image(painter = painterResource(R.drawable.lib_square), contentDescription = null)
            }
            """
              .trimIndent(),
          )
          add(
            "src/screenshotTest/java/com/LibraryModuleTest.kt",
            """
            package pkg.name

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.platform.LocalContext
            import androidx.compose.ui.tooling.preview.Preview
            import androidx.compose.ui.tooling.preview.PreviewParameter
            import com.android.tools.screenshot.PreviewTest
            import pkg.name.lib.LibIcon
            import pkg.name.lib.LibThemePreviews
            import pkg.name.lib.LibTitle
            import pkg.name.ui.DevicePreviews
            import pkg.name.ui.FeatureTitlesPreviewParameterProvider

            class LibraryModuleTest {
                @PreviewTest
                @LibThemePreviews
                @Composable
                fun internalComposableWithLibraryStringTest() {
                    LibTitle()
                }

                @PreviewTest
                @Preview(showBackground = true)
                @Composable
                fun libraryDrawableTest() {
                    LibIcon()
                }

                @PreviewTest
                @Preview(showBackground = true)
                @Composable
                fun dependencyJavaResourceTest() {
                    val label = checkNotNull(LibraryModuleTest::class.java.getResourceAsStream("/dependency_label.txt")) {
                        "dependency_label.txt is not visible to the preview"
                    }.bufferedReader().use { it.readText() }
                    SimpleComposable(label)
                }

                @PreviewTest
                @Preview(showBackground = true)
                @Composable
                fun libraryAssetTest() {
                    val text = LocalContext.current.assets.open("lib_asset.txt").bufferedReader().use { it.readText() }
                    SimpleComposable(text)
                }

                @PreviewTest
                @DevicePreviews
                @Composable
                fun dependencyMultipreviewTest() {
                    SimpleComposable("Multipreview of a dependency")
                }

                @PreviewTest
                @Preview(showBackground = true)
                @Composable
                fun dependencyPreviewParameterTest(@PreviewParameter(FeatureTitlesPreviewParameterProvider::class) title: String) {
                    SimpleComposable(title)
                }
            }
            """
              .trimIndent(),
          )
        }
      }
      androidApplication {
        dependencies { implementation(project(":ui")) }
        files {
          add("src/main/res/values/strings.xml", "<resources><string name=\"app_title\">App</string></resources>")
          add(
            "src/main/java/com/AppTitle.kt",
            """
            package pkg.name.app

            import androidx.compose.material.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.res.stringResource

            @Composable
            fun AppTitle() {
                Text(stringResource(R.string.app_title) + " / " + stringResource(pkg.name.ui.R.string.feature_title))
            }
            """
              .trimIndent(),
          )
          add(
            "src/screenshotTest/java/com/AppModuleTest.kt",
            """
            package pkg.name

            import androidx.compose.runtime.Composable
            import androidx.compose.ui.tooling.preview.Preview
            import com.android.tools.screenshot.PreviewTest
            import pkg.name.app.AppTitle

            class AppModuleTest {
                @PreviewTest
                @Preview(showBackground = true)
                @Composable
                fun appAndDependencyStringsTest() {
                    AppTitle()
                }
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    for ((projectName, project, testClass, imagesPerPreview) in
      listOf(
        PreviewsOfProject(
          "lib",
          build.androidLibrary(),
          "pkg.name.LibraryModuleTest",
          mapOf(
            "internalComposableWithLibraryStringTest" to 2,
            "libraryDrawableTest" to 1,
            "dependencyJavaResourceTest" to 1,
            "libraryAssetTest" to 1,
            "dependencyMultipreviewTest" to 2,
            "dependencyPreviewParameterTest" to 2,
          ),
        ),
        PreviewsOfProject("app", build.androidApplication(), "pkg.name.AppModuleTest", mapOf("appAndDependencyStringsTest" to 1)),
      )) {
      val updateResult = build.updateReferenceImageWithTestSuite(projectName = projectName)
      updateResult.assertOutputDoesNotContain("ScreenshotError")
      // A preview that fails to render has no reference image. Each multipreview and parameter value has its own.
      assertThat(
          getTestSuiteReferenceDir(project, testClass).listDirectoryEntries().groupingBy { it.name.substringBefore('_') }.eachCount()
        )
        .containsExactlyEntriesIn(imagesPerPreview)

      // Fails if any preview fails to render or doesn't match its reference image.
      build.validateScreenshotTestWithTestSuite(projectName = projectName).assertOutputDoesNotContain("ScreenshotError")
    }
  }

  private data class PreviewsOfProject(
    val projectName: String,
    val project: com.android.build.gradle.integration.common.fixture.project.GradleProject<*>,
    val testClass: String,
    /** The number of reference images of each preview function. */
    val imagesPerPreview: Map<String, Int>,
  )

  private fun createVerifier(appProject: com.android.build.gradle.integration.common.fixture.project.GradleProject<*>) =
    ScreenshotTestVerifier(
      referenceDirResolver = { getTestSuiteReferenceDir(appProject, it) },
      diffDirResolver = { getTestSuiteDiffDir(appProject, it) },
    )

  private fun assertClassHtmlReportContents(
    classHtmlReport: Path,
    class2HtmlReport: Path,
  ) {
    val classHtmlReportText = classHtmlReport.readText()
    assertThat(classHtmlReportText).contains("simpleComposableTest")
    assertThat(classHtmlReportText).contains("simpleComposableTest2")
    assertThat(classHtmlReportText).doesNotContain("multiPreviewTest")
    assertThat(class2HtmlReport.readText()).contains("simpleComposableTest_3")
  }

  companion object {
    private val ALL_MODULES = listOf("app", "lib", "lib2_0", "lib2_1")
    private const val FRAMEWORK_RES_JAR = "data/framework_res.jar"
    private const val RELEASE = "release"
  }
}
