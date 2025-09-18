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

import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.LoggingLevel
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.usLocaleCapitalize
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

private const val TILES_VERSION = "1.4.0"
private const val PROTOLAYOUT_VERSION = "1.2.0"

class WearTileScreenshotTest {

    @get:Rule
    val rule = GradleRule.configure()
        .withProfileOutput()
        .from {
            androidApplication {
                applyPlugin(PluginType.KOTLIN_ANDROID)
                applyPlugin(
                    PluginType.Custom(
                        id = "com.android.compose.screenshot",
                        version = "+",
                        artifact = "com.android.compose.screenshot:screenshot-test-gradle-plugin",
                        hasMarker = false,
                    )
                )

                android {
                    defaultConfig {
                        minSdk = 26
                        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                    }
                    experimentalProperties["android.experimental.enableScreenshotTest"] = true
                }
                dependencies {
                    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:+")
                    testImplementation("junit:junit:4.13.2")
                    implementation("androidx.wear.tiles:tiles:$TILES_VERSION")
                    implementation("androidx.wear.tiles:tiles-material:$TILES_VERSION")
                    implementation("androidx.wear.tiles:tiles-tooling:$TILES_VERSION")
                    implementation("androidx.wear.tiles:tiles-tooling-preview:$TILES_VERSION")
                    implementation("androidx.wear.protolayout:protolayout-material:$PROTOLAYOUT_VERSION")
                }
                kotlin {
                    jvmToolchain(17)
                }

                files {
                    add(
                        "src/main/java/com/Example.kt",
                        //language=kotlin
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
                      """.trimIndent()
                    )
                    add(
                        "src/screenshotTest/java/com/ExampleTest.kt",
                        //language=kotlin
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
                      """.trimIndent()
                    )
                    add(
                        "src/screenshotTest/java/com/TopLevelPreviewTest.kt",
                        //language=kotlin
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
                      """.trimIndent()
                    )
                }
            }

            gradleProperties {
                add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
            }
        }

    // custom executor configuration for screenshotTesting (sst)
    private fun GradleBuild.sstExecutor(): GradleTaskExecutor =
        executor
            .withConfigurationCaching(BaseGradleExecutor.ConfigurationCaching.ON)
            .with(BooleanOption.USE_ANDROID_X, true)
            .withLoggingLevel(LoggingLevel.LIFECYCLE)

    private fun updateReferenceImage(
        buildType: String = "debug",
        flavor: String = "",
        projectName: String = "app") {
        val build = rule.build
        val variantName = if (flavor.isEmpty()) {
            buildType
        } else {
            flavor + buildType.usLocaleCapitalize()
        }
        build.sstExecutor().run(
            ":$projectName:update${variantName.usLocaleCapitalize()}ScreenshotTest")
    }

    @Test
    fun runPreviewScreenshotTest() {
        val build = rule.build
        val appProject = build.androidApplication()

        // Generate screenshots to be tested against
        updateReferenceImage()

        val exampleTestReferenceScreenshotDir = appProject.resolve("src/screenshotTestDebug/reference/pkg/name/ExampleTest")
        val topLevelTestReferenceScreenshotDir = appProject.resolve("src/screenshotTestDebug/reference/pkg/name/TopLevelPreviewTestKt")
        assertThat(exampleTestReferenceScreenshotDir.listDirectoryEntries().map { it.name }).containsExactly(
            "multiplePreviewsTest_small_dfcc4c35_0.png",
            "simpleTilePreview_simple tile_7cfb9daa_0.png",
            "simpleTilePreview2_simple tile 2_7c408cfe_0.png",
            "multiplePreviewsTest_large_f3ef1d95_0.png",
        )
        assertThat(topLevelTestReferenceScreenshotDir.listDirectoryEntries().map { it.name }).containsExactly(
            "simpleTilePreview3_0.png"
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
            """<h3 class="success">multiplePreviewsTest_large_{device=id:wearos_large_round}</h3>""",
            """<h3 class="success">multiplePreviewsTest_small_{device=id:wearos_small_round}</h3>""",
            """<h3 class="success">simpleTilePreview2_simple tile 2</h3>""",
            """<h3 class="success">simpleTilePreview_simple tile</h3>""",
        )
        var classHtmlReportText = classHtmlReport.readText()
        expectedOutput.forEach { assertThat(classHtmlReportText).contains(it) }
        assertThat(class2HtmlReport.readText()).contains("""<h3 class="success">simpleTilePreview3</h3>""")
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
        }

        // Rerun validation task - modified tests should fail and diffs are generated
        build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")

        assertThat(indexHtmlReport).exists()
        assertThat(classHtmlReport).exists()
        val expectedOutputAfterChangingPreviews = listOf(
            "Failed tests",
            """<h3 class="failures">multiplePreviewsTest_large_{device=id:wearos_large_round}</h3>""",
            """<h3 class="failures">multiplePreviewsTest_small_{device=id:wearos_small_round}</h3>""",
            """<h3 class="failures">simpleTilePreview2_simple tile 2</h3>""",
            """<h3 class="failures">simpleTilePreview_simple tile</h3>""",
        )
        classHtmlReportText = classHtmlReport.readText()
        expectedOutputAfterChangingPreviews.forEach { assertThat(classHtmlReportText).contains(it) }
        assertThat(class2HtmlReport.readText()).contains("""<h3 class="failures">simpleTilePreview3</h3>""")
        assertThat(packageHtmlReport).exists()

        assertThat(exampleTestDiffDir.listDirectoryEntries().map { it.name }).containsExactly(
            "multiplePreviewsTest_small_dfcc4c35_0.png",
            "simpleTilePreview_simple tile_7cfb9daa_0.png",
            "simpleTilePreview2_simple tile 2_7c408cfe_0.png",
            "multiplePreviewsTest_large_f3ef1d95_0.png",
        )
        assertThat(topLevelTestDiffDir.listDirectoryEntries().map { it.name }).containsExactly(
            "simpleTilePreview3_0.png"
        )
    }

    @Test
    fun runPreviewScreenshotTestsWithTilesToolingAsTestDep() {
        val tilesToolingDep = "androidx.wear.tiles:tiles-tooling:$TILES_VERSION"
        val build = rule.build {
            androidApplication {
                dependencies {
                    remove("implementation", tilesToolingDep)
                    screenshotTestImplementation(tilesToolingDep)
                }
            }
        }
        val appProject = build.androidApplication()

        // Generate screenshots to be tested against
        updateReferenceImage()

        // Validate previews matches screenshots
        build.sstExecutor().run(":app:validateDebugScreenshotTest")

        // Verify that HTML reports are generated and all tests pass
        val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
        val classHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.ExampleTest.html")
        assertThat(indexHtmlReport).exists()
        assertThat(classHtmlReport).exists()
        val expectedOutput = listOf(
            """<h3 class="success">multiplePreviewsTest_large_{device=id:wearos_large_round}</h3>""",
            """<h3 class="success">multiplePreviewsTest_small_{device=id:wearos_small_round}</h3>""",
            """<h3 class="success">simpleTilePreview2_simple tile 2</h3>""",
            """<h3 class="success">simpleTilePreview_simple tile</h3>""",
        )
        val classHtmlReportText = classHtmlReport.readText()
        expectedOutput.forEach { assertThat(classHtmlReportText).contains(it) }

        // Assert that no diff images were generated because screenshot matched the reference image
        val exampleTestDiffDir = appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/diffs/pkg/name/ExampleTest")
        assert(exampleTestDiffDir.listDirectoryEntries().isEmpty())
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

        val result = build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")
        result.assertErrorContains("Missing required runtime dependency. Please add androidx.wear.tiles:tiles-tooling as a screenshotTestImplementation dependency.")
    }

}
