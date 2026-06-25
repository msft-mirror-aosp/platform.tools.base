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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.ScannerSubject.Companion.assertThat
import com.android.build.gradle.options.BooleanOption
import com.android.compose.screenshot.gradle.ScreenshotTestOptions
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {

  @get:Rule
  val rule =
    GradleRule.configure().withProfileOutput().from {
      androidApplication { setupProject() }
      androidLibrary { setupProject() }
      // All of these libraries will share the same class loader.
      // See b/340362066 for more details.
      repeat(2) { androidLibrary(":lib2_$it") { setupProject(addEmptyJarToClassPath = false) } }

      gradleProperties { add(BooleanOption.ENABLE_SCREENSHOT_TEST, true) }
    }

  @Ignore("b/525640367")
  @Test
  fun runPreviewScreenshotTestWithThreshold() {
    val build = rule.build
    val appProject = build.androidApplication()

    build.updateReferenceImage()
    // update the preview - tests fail
    appProject.files.update("src/main/java/com/Example.kt").searchAndReplace("Hello World", "Hello Worid")

    val result = build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")
    result.assertErrorContains("There were failing tests. See the report at: ")

    // set high threshold - tests pass
    appProject.reconfigure {
      android { testOptions { viaExtension("screenshotTests", ScreenshotTestOptions::class) { imageDifferenceThreshold = 0.5f } } }
    }

    build.sstExecutor().run(":app:validateDebugScreenshotTest")

    // reduce threshold - tests fail
    appProject.reconfigure {
      android { testOptions { viaExtension("screenshotTests", ScreenshotTestOptions::class) { imageDifferenceThreshold = 0.001f } } }
    }

    val resultLowThreshold = build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")
    resultLowThreshold.assertErrorContains("There were failing tests. See the report at: ")
  }

  @Test
  fun runPreviewScreenshotTest() {
    val build = rule.build
    val appProject = build.androidApplication()

    // Generate screenshots to be tested against
    build.updateReferenceImage()

    val exampleTestReferenceScreenshotDir = appProject.resolve("src/screenshotTestDebug/reference/pkg/name/ExampleTest")
    val topLevelTestReferenceScreenshotDir = appProject.resolve("src/screenshotTestDebug/reference/pkg/name/TopLevelPreviewTestKt")
    assertThat(exampleTestReferenceScreenshotDir.listDirectoryEntries().map { it.name })
      .containsExactly(
        "simpleComposableTest_simpleComposable_c5877f71_0.png",
        "simpleComposableTest2_simpleComposable_7362dd6b_0.png",
        "multiPreviewTest_with_Background_6d9364e2_0.png",
        "multiPreviewTest_withoutBackground_3619adf7_0.png",
        "parameterProviderTest_simplePreviewParameterProvider_893e015e_b983d6d8_1.png",
        "parameterProviderTest_simplePreviewParameterProvider_893e015e_b983d6d8_0.png",
        "previewNameCannotBeUsedAsFileNameTest_aa50de45_0.png",
      )
    assertThat(topLevelTestReferenceScreenshotDir.listDirectoryEntries().map { it.name })
      .containsExactly("simpleComposableTest_3_748aa731_0.png")

    // Validate previews matches screenshots
    val result = build.sstExecutor().run(":app:validateDebugScreenshotTest")
    result.assertOutputDoesNotContain("Slow render action")

    // Verify that HTML reports are generated and all tests pass
    val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
    val classHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.ExampleTest.html")
    val class2HtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.TopLevelPreviewTestKt.html")
    val packageHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.html")
    assertThat(indexHtmlReport).exists()
    assertThat(classHtmlReport).exists()
    val expectedOutput =
      listOf(
        """<h3 class="success">simpleComposableTest_simpleComposable</h3>""",
        """<h3 class="success">simpleComposableTest2_simpleComposable</h3>""",
        """<h3 class="success">multiPreviewTest_with_Background_{showBackground=true}</h3>""",
        """<h3 class="success">multiPreviewTest_withoutBackground_{showBackground=false}</h3>""",
        """{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
        """{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>""",
        """<h3 class="success">previewNameCannotBeUsedAsFileNameTest_invalid/File/Name</h3>""",
        """<h3 class="success">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
        """<h3 class="success">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>""",
      )
    var classHtmlReportText = classHtmlReport.readText()
    expectedOutput.forEach { assertThat(classHtmlReportText).contains(it) }
    class2HtmlReport.readText().let {
      assertThat(it).contains("""<h3 class="success">simpleComposableTest_3</h3>""")
      assertThat(it).contains("simpleComposableTest_3_748aa731_0.png")
    }
    assertThat(packageHtmlReport).exists()

    // Assert that no diff images were generated because screenshot matched the reference image
    val exampleTestDiffDir = appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/diffs/pkg/name/ExampleTest")
    val topLevelTestDiffDir =
      appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/diffs/pkg/name/TopLevelPreviewTestKt")
    assert(exampleTestDiffDir.listDirectoryEntries().isEmpty())
    assert(topLevelTestDiffDir.listDirectoryEntries().isEmpty())

    // Update previews to be different from the references
    appProject.files.apply {
      update("src/main/java/com/Example.kt").searchAndReplace("Hello World", "HelloWorld ")
      update("src/main/java/com/ParameterProviders.kt").searchAndReplace("Primary text", " Primarytext")
    }

    // Rerun validation task - modified tests should fail and diffs are generated
    build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")

    assertThat(indexHtmlReport).exists()
    assertThat(classHtmlReport).exists()
    val expectedOutputAfterChangingPreviews =
      listOf(
        "Failed tests",
        """<h3 class="failures">simpleComposableTest_simpleComposable</h3>""",
        """<h3 class="failures">simpleComposableTest2_simpleComposable</h3>""",
        """<h3 class="failures">multiPreviewTest_with_Background_{showBackground=true}</h3>""",
        """<h3 class="failures">multiPreviewTest_withoutBackground_{showBackground=false}</h3>""",
        """{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
        """{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>""",
        """<h3 class="failures">previewNameCannotBeUsedAsFileNameTest_invalid/File/Name</h3>""",
        """<h3 class="failures">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
        """<h3 class="success">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>""",
      )
    classHtmlReportText = classHtmlReport.readText()
    expectedOutputAfterChangingPreviews.forEach { assertThat(classHtmlReportText).contains(it) }
    class2HtmlReport.readText().let {
      assertThat(it).contains("""<h3 class="failures">simpleComposableTest_3</h3>""")
      assertThat(it).contains("rendered/pkg/name/TopLevelPreviewTestKt/simpleComposableTest_3_748aa731_0.png")
      assertThat(it).contains("reference/pkg/name/TopLevelPreviewTestKt/simpleComposableTest_3_748aa731_0.png")
      assertThat(it).contains("diffs/pkg/name/TopLevelPreviewTestKt/simpleComposableTest_3_748aa731_0.png")
    }
    assertThat(packageHtmlReport).exists()

    assertThat(exampleTestDiffDir.listDirectoryEntries().map { it.name })
      .containsExactly(
        "simpleComposableTest_simpleComposable_c5877f71_0.png",
        "simpleComposableTest2_simpleComposable_7362dd6b_0.png",
        "multiPreviewTest_with_Background_6d9364e2_0.png",
        "multiPreviewTest_withoutBackground_3619adf7_0.png",
        "parameterProviderTest_simplePreviewParameterProvider_893e015e_b983d6d8_0.png",
        "previewNameCannotBeUsedAsFileNameTest_aa50de45_0.png",
      )
    assertThat(topLevelTestDiffDir.listDirectoryEntries().map { it.name }).containsExactly("simpleComposableTest_3_748aa731_0.png")
  }

  @Test
  fun runPreviewScreenshotTestWithFilter() {
    val build = rule.build { androidApplication { pluginCallbacks += FilterSetupCallback::class.java } }
    val appProject = build.androidApplication()

    // Generate screenshots to be tested against.
    build.updateReferenceImage()

    val exampleTestReferenceScreenshotDir = appProject.resolve("src/screenshotTestDebug/reference/pkg/name/ExampleTest")
    val topLevelTestReferenceScreenshotDir = appProject.resolve("src/screenshotTestDebug/reference/pkg/name/TopLevelPreviewTestKt")
    assertThat(exampleTestReferenceScreenshotDir.listDirectoryEntries().map { it.name })
      .containsExactly(
        "multiPreviewTest_with_Background_6d9364e2_0.png",
        "multiPreviewTest_withoutBackground_3619adf7_0.png",
        "parameterProviderTest_simplePreviewParameterProvider_893e015e_b983d6d8_0.png",
        "parameterProviderTest_simplePreviewParameterProvider_893e015e_b983d6d8_1.png",
        "previewNameCannotBeUsedAsFileNameTest_aa50de45_0.png",
        "simpleComposableTest2_simpleComposable_7362dd6b_0.png",
        "simpleComposableTest_simpleComposable_c5877f71_0.png",
      )
    assertThat(topLevelTestReferenceScreenshotDir.listDirectoryEntries().map { it.name })
      .containsExactly("simpleComposableTest_3_748aa731_0.png")

    // Validate previews matches screenshots
    build.sstExecutor().run(":app:validateDebugScreenshotTest")

    // Verify that HTML reports are generated and all tests pass
    val indexHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/index.html")
    val classHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.ExampleTest.html")
    val class2HtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.TopLevelPreviewTestKt.html")
    val packageHtmlReport = appProject.buildDir.resolve("reports/screenshotTest/preview/debug/pkg.name.html")
    assertThat(indexHtmlReport).exists()
    assertThat(classHtmlReport).exists()
    val expectedOutput =
      listOf(
        """<h3 class="success">simpleComposableTest_simpleComposable</h3>""",
        """<h3 class="success">simpleComposableTest2_simpleComposable</h3>""",
      )
    val unExpectedOutput =
      listOf(
        """<h3 class="success">multiPreviewTest_with_Background_{showBackground=true}</h3>""",
        """<h3 class="success">multiPreviewTest_withoutBackground_{showBackground=false}</h3>""",
        """{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
        """{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>""",
        """<h3 class="success">previewNameCannotBeUsedAsFileNameTest_invalid/File/Name</h3>""",
        """<h3 class="success">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_0</h3>""",
        """<h3 class="success">parameterProviderTest_simplePreviewParameterProvider_[{provider=pkg.name.SimplePreviewParameterProvider}]_1</h3>""",
      )
    var classHtmlReportText = classHtmlReport.readText()
    expectedOutput.forEach { assertThat(classHtmlReportText).contains(it) }
    unExpectedOutput.forEach { assertThat(classHtmlReportText).doesNotContain(it) }
    assertThat(class2HtmlReport.readText()).contains("""<h3 class="success">simpleComposableTest_3</h3>""")
    assertThat(packageHtmlReport).exists()

    // Assert that no diff images were generated because screenshot matched the reference image
    val exampleTestDiffDir = appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/diffs/pkg/name/ExampleTest")
    val topLevelTestDiffDir =
      appProject.buildDir.resolve("outputs/screenshotTest-results/preview/debug/diffs/pkg/name/TopLevelPreviewTestKt")
    assert(exampleTestDiffDir.listDirectoryEntries().isEmpty())
    assert(topLevelTestDiffDir.listDirectoryEntries().isEmpty())

    // Update previews to be different from the references
    appProject.files.apply {
      update("src/main/java/com/Example.kt").searchAndReplace("Hello World", "HelloWorld ")
      update("src/main/java/com/ParameterProviders.kt").searchAndReplace("Primary text", " Primarytext")
    }

    // Rerun validation task - modified tests should fail and diffs are generated
    build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")

    assertThat(exampleTestDiffDir.listDirectoryEntries().map { it.name })
      .containsExactly("simpleComposableTest_simpleComposable_c5877f71_0.png", "simpleComposableTest2_simpleComposable_7362dd6b_0.png")
  }

  @Test
  fun runPreviewScreenshotTestWithNoMatchingFilter() {
    val build = rule.build { androidApplication { pluginCallbacks += FilterMatchingNothingCallback::class.java } }
    val appProject = build.androidApplication()

    build.updateReferenceImage()

    // Validate previews - should pass because no tests are run and failOnNoMatchingTests is false
    build.sstExecutor().run(":app:validateDebugScreenshotTest")
  }

  @Test
  fun runPreviewScreenshotTestMissingReferences() {
    val build = rule.build
    val appProject = build.androidApplication()

    // Run validation without updating references - should fail
    val result = build.sstExecutor().expectFailure().run(":app:validateDebugScreenshotTest")

    result.assertErrorContains("There were failing tests")
  }
}

class FilterMatchingNothingCallback : com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback {
  override fun handleProject(project: org.gradle.api.Project) {
    project.afterEvaluate {
      project.tasks.named("validateDebugScreenshotTest", org.gradle.api.tasks.testing.Test::class.java) {
        it.setTestNameIncludePatterns(listOf("*NonExistent*"))
        it.filter.isFailOnNoMatchingTests = false
      }
    }
  }
}
