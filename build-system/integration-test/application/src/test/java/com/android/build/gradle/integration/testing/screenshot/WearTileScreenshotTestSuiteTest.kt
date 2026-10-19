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

import com.android.build.api.dsl.AgpTestSuiteInputParameters
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.TestUtils
import com.android.testutils.apk.Zip
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.junit.Rule
import org.junit.Test

private const val TILES_VERSION = "1.4.0"
private const val PROTOLAYOUT_VERSION = "1.2.0"

/** Wear tile screenshot tests for screenshot test suites declared through `testOptions.screenshotTests`. */
class WearTileScreenshotTestSuiteTest {

  @get:Rule
  val rule =
    GradleRule.configure().withProfileOutput().from {
      gradleProperties {
        add("org.gradle.java.installations.auto-detect", "false")
        add("org.gradle.java.installations.paths", TestUtils.getJava21Jdk().toString().replace("\\", "/"))
        add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
        add(BooleanOption.TEST_SUITE_SUPPORT, true)
      }
      androidApplication {
        android {
          namespace = "pkg.name"
          compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          defaultConfig {
            minSdk = 26
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          testOptions.screenshotTests.create("screenshotTest") {
            it.engineVersion = "+"
            it.targetVariants.addAll(listOf("debug", "release"))
            it.dependencies {
              implementation.add("com.android.tools.screenshot:screenshot-validation-api:+")
            }
          }
        }
        dependencies {
          testImplementation("junit:junit:4.13.2")
          implementation("androidx.wear.tiles:tiles:$TILES_VERSION")
          implementation("androidx.wear.tiles:tiles-material:$TILES_VERSION")
          implementation("androidx.wear.tiles:tiles-tooling:$TILES_VERSION")
          implementation("androidx.wear.tiles:tiles-tooling-preview:$TILES_VERSION")
          implementation("androidx.wear.protolayout:protolayout:$PROTOLAYOUT_VERSION")
          implementation("androidx.wear.protolayout:protolayout-material:$PROTOLAYOUT_VERSION")
        }
        kotlin {
          // Required by LayoutLib (com/android/layoutlib/bridge/Bridge).
          jvmToolchain(21)
        }

        files {
          add(
            "src/main/java/com/Example.kt",
            """
            package pkg.name

            import android.content.Context
            import androidx.wear.protolayout.ColorBuilders.argb
            import androidx.wear.protolayout.LayoutElementBuilders
            import androidx.wear.protolayout.ResourceBuilders
            import androidx.wear.protolayout.TimelineBuilders
            import androidx.wear.protolayout.material.Colors
            import androidx.wear.protolayout.material.Text
            import androidx.wear.protolayout.material.Typography
            import androidx.wear.protolayout.material.layouts.PrimaryLayout
            import androidx.wear.tiles.RequestBuilders
            import androidx.wear.tiles.TileBuilders

            private const val RESOURCES_VERSION = "0"

            fun resources(): ResourceBuilders.Resources {
                return ResourceBuilders.Resources.Builder()
                    .setVersion(RESOURCES_VERSION)
                    .build()
            }

            fun tile(
                requestParams: RequestBuilders.TileRequest,
                context: Context,
            ): TileBuilders.Tile {
                val singleTileTimeline = TimelineBuilders.Timeline.Builder()
                    .addTimelineEntry(
                        TimelineBuilders.TimelineEntry.Builder()
                            .setLayout(
                                LayoutElementBuilders.Layout.Builder()
                                    .setRoot(tileLayout(requestParams, context))
                                    .build()
                            )
                            .build()
                    )
                    .build()

                return TileBuilders.Tile.Builder()
                    .setResourcesVersion(RESOURCES_VERSION)
                    .setTileTimeline(singleTileTimeline)
                    .build()
            }

            private fun tileLayout(
                requestParams: RequestBuilders.TileRequest,
                context: Context,
            ): LayoutElementBuilders.LayoutElement {
                return PrimaryLayout.Builder(requestParams.deviceConfiguration)
                    .setResponsiveContentInsetEnabled(true)
                    .setContent(
                        Text.Builder(context, "Hello World!")
                            .setColor(argb(Colors.DEFAULT.onSurface))
                            .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                            .build()
                    ).build()
            }
            """
              .trimIndent(),
          )
          add(
            "src/screenshotTest/java/com/ExampleTest.kt",
            """
            package pkg.name

            import android.content.Context
            import androidx.wear.tiles.tooling.preview.Preview
            import androidx.wear.tiles.tooling.preview.TilePreviewData
            import androidx.wear.tooling.preview.devices.WearDevices
            import com.android.tools.screenshot.PreviewTest

            class ExampleTest {
                @PreviewTest
                @Preview(name = "simple tile")
                fun simpleTilePreview(context: Context) = TilePreviewData({ resources() }) {
                    tile(it, context)
                }

                @PreviewTest
                @Preview(name = "simple tile 2", device = WearDevices.LARGE_ROUND)
                fun simpleTilePreview2(context: Context) = TilePreviewData({ resources() }) {
                    tile(it, context)
                }

                @PreviewTest
                @Preview(name = "small", device = WearDevices.SMALL_ROUND)
                @Preview(name = "large", device = WearDevices.LARGE_ROUND)
                fun multiplePreviewsTest(context: Context) = TilePreviewData({ resources() }) {
                    tile(it, context)
                }
            }
            """
              .trimIndent(),
          )
          add(
            "src/screenshotTest/java/com/TopLevelPreviewTest.kt",
            """
            package pkg.name

            import android.content.Context
            import androidx.wear.tiles.tooling.preview.Preview
            import androidx.wear.tiles.tooling.preview.TilePreviewData
            import androidx.wear.tooling.preview.devices.WearDevices
            import com.android.tools.screenshot.PreviewTest

            @PreviewTest
            @Preview
            fun simpleTilePreview3(context: Context) = TilePreviewData({ resources() }) {
                tile(it, context)
            }
            """
              .trimIndent(),
          )
        }
      }
    }

  @Test
  fun runPreviewScreenshotTest() {
    val build = rule.build
    val appProject = build.androidApplication()

    build.updateReferenceImageWithTestSuite()

    val exampleTestReferenceScreenshotDir = getTestSuiteReferenceDir(appProject, "pkg.name.ExampleTest")
    val topLevelTestReferenceScreenshotDir = getTestSuiteReferenceDir(appProject, "pkg.name.TopLevelPreviewTestKt")
    assertThat(exampleTestReferenceScreenshotDir.listDirectoryEntries().map { it.name })
      .containsExactly(
        "multiplePreviewsTest_small_dfcc4c35_0.png",
        "simpleTilePreview_simple tile_7cfb9daa_0.png",
        "simpleTilePreview2_simple tile 2_7c408cfe_0.png",
        "multiplePreviewsTest_large_f3ef1d95_0.png",
      )
    assertThat(topLevelTestReferenceScreenshotDir.listDirectoryEntries().map { it.name }).containsExactly("simpleTilePreview3_0.png")

    build.validateScreenshotTestWithTestSuite()

    // Verify that HTML reports are generated and all tests pass
    val indexHtmlReport = getTestSuiteHtmlReportIndexFile(appProject)
    val classHtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.ExampleTest")
    val class2HtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.TopLevelPreviewTestKt")
    assertThat(indexHtmlReport).exists()
    assertThat(classHtmlReport).exists()
    assertThat(getTestSuiteHtmlReportTestStatuses(appProject, "pkg.name.ExampleTest"))
      .containsExactlyEntriesIn(EXAMPLE_TEST_NAMES.associateWith { TEST_SUITE_REPORT_PASSED })
    assertThat(getTestSuiteHtmlReportTestStatuses(appProject, "pkg.name.TopLevelPreviewTestKt"))
      .containsExactly("simpleTilePreview3", TEST_SUITE_REPORT_PASSED)
    assertThat(getTestSuiteHtmlReportPackageNames(appProject)).contains("pkg.name")

    // Assert that no diff images were generated because screenshot matched the reference image
    val exampleTestDiffDir = getTestSuiteDiffDir(appProject, "pkg.name.ExampleTest")
    val topLevelTestDiffDir = getTestSuiteDiffDir(appProject, "pkg.name.TopLevelPreviewTestKt")
    assert(exampleTestDiffDir.listDirectoryEntries().isEmpty())
    assert(topLevelTestDiffDir.listDirectoryEntries().isEmpty())

    // Update previews to be different from the references
    appProject.files.apply { update("src/main/java/com/Example.kt").searchAndReplace("Hello World", "HelloWorld ") }

    // Rerun validation task - modified tests should fail and diffs are generated
    build.validateScreenshotTestWithTestSuite(expectFailure = true)

    assertThat(indexHtmlReport).exists()
    assertThat(classHtmlReport).exists()
    assertThat(class2HtmlReport).exists()
    assertThat(getTestSuiteHtmlReportTestStatuses(appProject, "pkg.name.ExampleTest"))
      .containsExactlyEntriesIn(EXAMPLE_TEST_NAMES.associateWith { TEST_SUITE_REPORT_FAILED })
    assertThat(getTestSuiteHtmlReportTestStatuses(appProject, "pkg.name.TopLevelPreviewTestKt"))
      .containsExactly("simpleTilePreview3", TEST_SUITE_REPORT_FAILED)
    assertThat(getTestSuiteHtmlReportPackageNames(appProject)).contains("pkg.name")

    assertThat(exampleTestDiffDir.listDirectoryEntries().map { it.name })
      .containsExactly(
        "multiplePreviewsTest_small_dfcc4c35_0.png",
        "simpleTilePreview_simple tile_7cfb9daa_0.png",
        "simpleTilePreview2_simple tile 2_7c408cfe_0.png",
        "multiplePreviewsTest_large_f3ef1d95_0.png",
      )
    assertThat(topLevelTestDiffDir.listDirectoryEntries().map { it.name }).containsExactly("simpleTilePreview3_0.png")
  }

  @Test
  fun runPreviewScreenshotTestsWithTilesToolingAsTestDep() {
    val tilesToolingDep = "androidx.wear.tiles:tiles-tooling:$TILES_VERSION"
    val build = rule.build
    val appProject = build.androidApplication()

    // Record the reference images with tiles-tooling in the main implementation, where its resources and the ones of its dependencies
    // are resources of the tested application.
    build.updateReferenceImageWithTestSuite()
    assertThat(getTestSuiteReferenceDir(appProject, "pkg.name.ExampleTest").listDirectoryEntries()).isNotEmpty()
    assertThat(getTestSuiteReferenceDir(appProject, "pkg.name.TopLevelPreviewTestKt").listDirectoryEntries()).isNotEmpty()

    // Move tiles-tooling to the screenshot test suite only. Its resources and the ones of its runtime only dependencies, like
    // protolayout-renderer, now have to come from the resources of the test suite.
    appProject.reconfigure {
      dependencies { remove("implementation", tilesToolingDep) }
      android { testOptions.screenshotTests.named("screenshotTest") { it.dependencies { implementation.add(tilesToolingDep) } } }
    }

    // Validate that the previews rendered with tiles-tooling as a test only dependency match the reference images
    build.validateScreenshotTestWithTestSuite()

    // The R class of protolayout-renderer, a runtime only dependency of tiles-tooling, comes from the R class linked for the test suite,
    // which comes first. It does not leak into the R class of the tested application.
    val rendererRStyleClass = "androidx/wear/protolayout/renderer/R\$style.class"
    val rClassJars =
      getTestSuiteEngineInputs(appProject).getProperty(AgpTestSuiteInputParameters.R_CLASS_JARS.propertyName).split(File.pathSeparator)
    val testSuiteRClassJar = Paths.get(rClassJars.first())
    assertThat(testSuiteRClassJar).exists()
    Zip(testSuiteRClassJar).use { assertThat(it.getEntry(rendererRStyleClass)).isNotNull() }
    val appRClassJar = appProject.buildDir.resolve("intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar")
    assertThat(appRClassJar).exists()
    Zip(appRClassJar).use { assertThat(it.getEntry(rendererRStyleClass)).isNull() }

    // Verify that HTML reports are generated and all tests pass
    val indexHtmlReport = getTestSuiteHtmlReportIndexFile(appProject)
    val classHtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.ExampleTest")
    val class2HtmlReport = getTestSuiteHtmlReportClassFile(appProject, "pkg.name.TopLevelPreviewTestKt")
    assertThat(indexHtmlReport).exists()
    assertThat(classHtmlReport).exists()
    assertThat(class2HtmlReport).exists()
    assertThat(getTestSuiteHtmlReportTestStatuses(appProject, "pkg.name.ExampleTest"))
      .containsExactlyEntriesIn(EXAMPLE_TEST_NAMES.associateWith { TEST_SUITE_REPORT_PASSED })
    assertThat(getTestSuiteHtmlReportTestStatuses(appProject, "pkg.name.TopLevelPreviewTestKt"))
      .containsExactly("simpleTilePreview3", TEST_SUITE_REPORT_PASSED)
    assertThat(getTestSuiteHtmlReportPackageNames(appProject)).contains("pkg.name")

    // Assert that no diff images were generated because screenshot matched the reference image
    val exampleTestDiffDir = getTestSuiteDiffDir(appProject, "pkg.name.ExampleTest")
    val topLevelTestDiffDir = getTestSuiteDiffDir(appProject, "pkg.name.TopLevelPreviewTestKt")
    assert(exampleTestDiffDir.listDirectoryEntries().isEmpty())
    assert(topLevelTestDiffDir.listDirectoryEntries().isEmpty())
  }

  @Test
  fun runPreviewScreenshotTestsWithMissingTilesToolingDep() {
    val tilesToolingDep = "androidx.wear.tiles:tiles-tooling:$TILES_VERSION"
    val build = rule.build {
      androidApplication {
        dependencies {
          // Verify that exception is thrown when tiles-tooling dep is missing
          remove("implementation", tilesToolingDep)
        }
      }
    }

    // Unlike the screenshot test plugin, test suites don't check for tiles-tooling before rendering, so rendering fails instead. The
    // rendering failures are in the output of the tests.
    val result = build.validateScreenshotTestWithTestSuite(expectFailure = true)
    result.assertOutputContains("Screenshot rendering failed:")
    result.assertOutputContains("Missing Classes: androidx.wear.tiles.tooling.TileServiceViewAdapter")
  }

  companion object {
    private val EXAMPLE_TEST_NAMES =
      listOf(
        "multiplePreviewsTest_large_{device=id:wearos_large_round}",
        "multiplePreviewsTest_small_{device=id:wearos_small_round}",
        "simpleTilePreview2_simple tile 2",
        "simpleTilePreview_simple tile",
      )
  }
}
