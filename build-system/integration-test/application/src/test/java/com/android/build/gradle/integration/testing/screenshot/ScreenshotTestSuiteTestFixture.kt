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
import com.android.build.api.dsl.CommonExtension
import com.android.build.gradle.integration.common.fixture.BaseGradleExecutor
import com.android.build.gradle.integration.common.fixture.GradleBuildResult
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleProject
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.internal.TaskManager
import com.android.testutils.TestUtils
import com.android.utils.usLocaleCapitalize
import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import java.util.Properties
import java.util.UUID
import java.util.stream.Collectors
import kotlin.io.path.inputStream
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test as GradleTest

val EXPECTED_EXAMPLE_TEST_REFERENCE_IMAGES =
  listOf(
    "simpleComposableTest_simpleComposable_c5877f71_0.png",
    "simpleComposableTest2_simpleComposable_7362dd6b_0.png",
    "multiPreviewTest_with_Background_6d9364e2_0.png",
    "multiPreviewTest_withoutBackground_3619adf7_0.png",
    "parameterProviderTest_simplePreviewParameterProvider_893e015e_b983d6d8_1.png",
    "parameterProviderTest_simplePreviewParameterProvider_893e015e_b983d6d8_0.png",
    "previewNameCannotBeUsedAsFileNameTest_aa50de45_0.png",
  )

val EXPECTED_TOP_LEVEL_REFERENCE_IMAGES = listOf("simpleComposableTest_3_748aa731_0.png")

val EXPECTED_EXAMPLE_TEST_DIFF_IMAGES =
  listOf(
    "simpleComposableTest_simpleComposable_c5877f71_0.png",
    "simpleComposableTest2_simpleComposable_7362dd6b_0.png",
    "multiPreviewTest_with_Background_6d9364e2_0.png",
    "multiPreviewTest_withoutBackground_3619adf7_0.png",
    "parameterProviderTest_simplePreviewParameterProvider_893e015e_b983d6d8_0.png",
    "previewNameCannotBeUsedAsFileNameTest_aa50de45_0.png",
  )

fun modifyPreviewSourceFiles(appProject: GradleProject<*>) {
  appProject.files.apply {
    update("src/main/java/com/Example.kt").searchAndReplace("Hello World", "HelloWorld ")
    update("src/main/java/com/ParameterProviders.kt").searchAndReplace("Primary text", " Primarytext")
  }
}

class ScreenshotTestVerifier(
  private val referenceDirResolver: (String) -> Path,
  private val diffDirResolver: (String) -> Path,
) {
  fun assertReferenceScreenshotsGenerated() {
    val exampleDir = referenceDirResolver("pkg.name.ExampleTest")
    val topLevelDir = referenceDirResolver("pkg.name.TopLevelPreviewTestKt")
    assertThat(exampleDir.listDirectoryEntries().map { it.name }).containsExactlyElementsIn(EXPECTED_EXAMPLE_TEST_REFERENCE_IMAGES)
    assertThat(topLevelDir.listDirectoryEntries().map { it.name }).containsExactlyElementsIn(EXPECTED_TOP_LEVEL_REFERENCE_IMAGES)
  }

  fun assertNoDiffsGenerated() {
    val exampleDir = diffDirResolver("pkg.name.ExampleTest")
    val topLevelDir = diffDirResolver("pkg.name.TopLevelPreviewTestKt")
    assert(exampleDir.listDirectoryEntries().isEmpty())
    assert(topLevelDir.listDirectoryEntries().isEmpty())
  }

  fun assertDiffsGeneratedAfterModification() {
    val exampleDir = diffDirResolver("pkg.name.ExampleTest")
    val topLevelDir = diffDirResolver("pkg.name.TopLevelPreviewTestKt")
    assertThat(exampleDir.listDirectoryEntries().map { it.name }).containsExactlyElementsIn(EXPECTED_EXAMPLE_TEST_DIFF_IMAGES)
    assertThat(topLevelDir.listDirectoryEntries().map { it.name }).containsExactlyElementsIn(EXPECTED_TOP_LEVEL_REFERENCE_IMAGES)
  }

  fun verifyPreviewScreenshotTest(
    appProject: GradleProject<*>,
    updateTask: () -> GradleBuildResult,
    validateTask: () -> GradleBuildResult,
    validateTaskExpectingFailure: () -> GradleBuildResult,
    assertPassingHtmlReports: () -> Unit,
    assertFailingHtmlReports: () -> Unit,
  ) {
    // Generate screenshots to be tested against
    updateTask()
    assertReferenceScreenshotsGenerated()

    // Validate previews matches screenshots
    val result = validateTask()
    result.assertOutputDoesNotContain("Slow render action")

    // Verify that HTML reports are generated and all tests pass
    assertPassingHtmlReports()

    // Assert that no diff images were generated because screenshot matched the reference image
    assertNoDiffsGenerated()

    // Update previews to be different from the references
    modifyPreviewSourceFiles(appProject)

    // Rerun validation task - modified tests should fail and diffs are generated
    validateTaskExpectingFailure()

    // Verify HTML reports after failure
    assertFailingHtmlReports()

    assertDiffsGeneratedAfterModification()
  }

  fun verifyPreviewScreenshotTestWithFilter(
    appProject: GradleProject<*>,
    updateTask: () -> GradleBuildResult,
    validateTask: () -> GradleBuildResult,
    validateTaskExpectingFailure: () -> GradleBuildResult,
    assertFilteredHtmlReports: () -> Unit,
  ) {
    // Generate screenshots to be tested against.
    updateTask()
    assertReferenceScreenshotsGenerated()

    // Validate previews matches screenshots
    validateTask()

    // Verify that HTML reports are generated and all tests pass
    assertFilteredHtmlReports()

    // Assert that no diff images were generated because screenshot matched the reference image
    assertNoDiffsGenerated()

    // Update previews to be different from the references
    modifyPreviewSourceFiles(appProject)

    // Rerun validation task - modified tests should fail and diffs are generated
    validateTaskExpectingFailure()

    val exampleTestDiffDir = diffDirResolver("pkg.name.ExampleTest")
    assertThat(exampleTestDiffDir.listDirectoryEntries().map { it.name })
      .containsExactly(
        "simpleComposableTest_simpleComposable_c5877f71_0.png",
        "simpleComposableTest2_simpleComposable_7362dd6b_0.png",
      )
  }

  fun verifyPreviewScreenshotTestWithNoMatchingFilter(
    updateTask: () -> GradleBuildResult,
    validateTask: () -> GradleBuildResult,
  ) {
    updateTask()

    // Validate previews - should pass because no tests are run and failOnNoMatchingTests is
    // false
    validateTask()
  }

  fun verifyPreviewScreenshotTestMissingReferences(validateTaskExpectingFailure: () -> GradleBuildResult) {
    // Run validation without updating references - should fail
    val result = validateTaskExpectingFailure()
    result.assertErrorContains("There were failing tests")
  }
}

fun AndroidProjectDefinition<out CommonExtension>.setupScreenshotTestSuiteProject(
  addEmptyJarToClassPath: Boolean = true,
  suiteName: String = "screenshotTest",
  threshold: Float? = null,
  engineVersion: String = "+",
  targetVariants: List<String> = listOf("debug", "release"),
) {
  setupScreenshotTestSuiteProjectNoScreenshotTestSource(
    suiteName = suiteName,
    threshold = threshold,
    engineVersion = engineVersion,
    targetVariants = targetVariants,
  )

  if (addEmptyJarToClassPath) {
    val customJarName = UUID.randomUUID().toString() + ".jar"
    buildscript {
      classpath(localJar(customJarName) { addEmptyClasses("RandomClass_${UUID.randomUUID()}") })
    }
  }

  kotlin { jvmToolchain(17) }

  files {
    add(
      "src/screenshotTest/java/com/AnotherPreviewParameterProvider.kt",
      """
      package pkg.name

      import androidx.compose.ui.tooling.preview.PreviewParameterProvider

      class AnotherPreviewParameterProvider : PreviewParameterProvider<String> {
          override val values = sequenceOf(
              "text 1", "text 2"
          )
      }
      """
        .trimIndent(),
    )
    add(
      "src/screenshotTest/java/com/ExampleTest.kt",
      """
      package pkg.name

      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.tooling.preview.PreviewParameter
      import androidx.compose.runtime.Composable
      import com.android.tools.screenshot.PreviewTest

      class ExampleTest {
          @PreviewTest
          @Preview(name = "simpleComposable", showBackground = true)
          @Composable
          fun simpleComposableTest() {
              SimpleComposable()
          }

          @PreviewTest
          @Preview(name = "simpleComposable", widthDp = 800, heightDp = 800)
          @Composable
          fun simpleComposableTest2() {
              SimpleComposable()
          }

          @PreviewTest
          @Preview(name = "with_Background", showBackground = true)
          @Preview(name = "withoutBackground", showBackground = false)
          @Composable
          fun multiPreviewTest() {
              SimpleComposable()
          }

          @PreviewTest
          @Preview(name = "simplePreviewParameterProvider")
          @Composable
          fun parameterProviderTest(
              @PreviewParameter(SimplePreviewParameterProvider::class) data: String
          ) {
             SimpleComposable(data)
          }

          @PreviewTest
          @Preview(name = "invalid/File/Name")
          @Composable
          fun previewNameCannotBeUsedAsFileNameTest() {
              SimpleComposable()
          }
      }
      """
        .trimIndent(),
    )
    add(
      "src/screenshotTest/java/com/TopLevelPreviewTest.kt",
      """
      package pkg.name

      import androidx.compose.ui.tooling.preview.Preview
      import androidx.compose.ui.tooling.preview.PreviewParameter
      import androidx.compose.runtime.Composable
      import com.android.tools.screenshot.PreviewTest

      @PreviewTest
      @Preview(showBackground = true)
      @Composable
      fun simpleComposableTest_3() {
          SimpleComposable()
      }
      """
        .trimIndent(),
    )
  }
}

fun AndroidProjectDefinition<out CommonExtension>.setupProjectWithTestSuite(
  addEmptyJarToClassPath: Boolean = true,
  targetVariants: List<String> = listOf("debug", "release"),
) {
  setupScreenshotWithTestSuiteProject(
    addEmptyJarToClassPath = addEmptyJarToClassPath,
    targetVariants = targetVariants,
  )
}

fun AndroidProjectDefinition<out CommonExtension>.setupScreenshotTestSuiteProjectNoScreenshotTestSource(
  suiteName: String = "screenshotTest",
  threshold: Float? = null,
  engineVersion: String = "+",
  targetVariants: List<String> = listOf("debug", "release"),
) {
  applyPlugin(
    PluginType.Custom(
      id = com.android.build.gradle.internal.utils.COMPOSE_COMPILER_PLUGIN_ID,
      version = TestUtils.KOTLIN_VERSION_FOR_TESTS,
      artifact = "org.jetbrains.kotlin.plugin.compose:org.jetbrains.kotlin.plugin.compose.gradle.plugin",
      hasMarker = false,
    )
  )

  android {
    defaultConfig.apply {
      minSdk = 24
      testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures.apply { compose = true }
    composeOptions.kotlinCompilerExtensionVersion = TestUtils.COMPOSE_COMPILER_FOR_TESTS

    testOptions.screenshotTests.create(suiteName) {
      it.engineVersion = engineVersion
      if (threshold != null) {
        it.imageDifferenceThreshold = threshold
      }
      it.targetVariants.addAll(targetVariants)
      it.dependencies {
        implementation.add("com.android.tools.screenshot:screenshot-validation-api:+")
      }
    }
  }

  dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.compose.ui:ui-tooling:${TaskManager.COMPOSE_UI_VERSION}")
    implementation("androidx.compose.ui:ui-tooling-preview:${TaskManager.COMPOSE_UI_VERSION}")
    implementation("androidx.compose.material:material:${TaskManager.COMPOSE_UI_VERSION}")
  }
  kotlin { jvmToolchain(17) }
  pluginCallbacks += ScreenshotCallback::class.java

  files {
    add(
      "src/main/java/com/Example.kt",
      """
      package pkg.name

      import androidx.compose.material.Text
      import androidx.compose.runtime.Composable

      @Composable
      fun SimpleComposable(text: String = "Hello World") {
          Text(text)
      }
      """
        .trimIndent(),
    )
    add(
      "src/main/java/com/ParameterProviders.kt",
      """
      package pkg.name

      import androidx.compose.ui.tooling.preview.PreviewParameterProvider

      class SimplePreviewParameterProvider : PreviewParameterProvider<String> {
          override val values = sequenceOf(
              "Primary text", "Secondary text"
          )
      }
      """
        .trimIndent(),
    )
  }
}

fun AndroidProjectDefinition<out CommonExtension>.setupScreenshotWithTestSuiteProject(
  addEmptyJarToClassPath: Boolean = true,
  suiteName: String = "screenshotTest",
  threshold: Float? = null,
  engineVersion: String = "+",
  targetVariants: List<String> = listOf("debug", "release"),
) =
  setupScreenshotTestSuiteProject(
    addEmptyJarToClassPath,
    suiteName,
    threshold,
    engineVersion,
    targetVariants,
  )

fun AndroidProjectDefinition<out CommonExtension>.setupScreenshotWithTestSuiteProjectNoScreenshotTestSource(
  suiteName: String = "screenshotTest",
  threshold: Float? = null,
  engineVersion: String = "+",
  targetVariants: List<String> = listOf("debug", "release"),
) =
  setupScreenshotTestSuiteProjectNoScreenshotTestSource(
    suiteName,
    threshold,
    engineVersion,
    targetVariants,
  )

fun getTestSuiteValidateTaskName(
  variantName: String = "debug",
  projectName: String = "app",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
): String = getTestSuiteTaskName("test", variantName, projectName, suiteName, targetName)

fun getTestSuiteUpdateTaskName(
  variantName: String = "debug",
  projectName: String = "app",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
): String = getTestSuiteTaskName("update", variantName, projectName, suiteName, targetName)

private fun getTestSuiteTaskName(
  verb: String,
  variantName: String,
  projectName: String,
  suiteName: String,
  targetName: String,
): String {
  val projectPrefix = if (projectName.isNotEmpty()) ":$projectName:" else ""
  return "$projectPrefix$verb${variantName.usLocaleCapitalize()}${suiteName.usLocaleCapitalize()}${targetName.usLocaleCapitalize()}TestSuite"
}

fun GradleBuild.updateReferenceImageWithTestSuite(
  variantName: String = "debug",
  projectName: String = "app",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
  expectFailure: Boolean = false,
  cc: BaseGradleExecutor.ConfigurationCaching = BaseGradleExecutor.ConfigurationCaching.PROJECT_ISOLATION,
): GradleBuildResult {
  val taskName = getTestSuiteUpdateTaskName(variantName, projectName, suiteName, targetName)
  val executor = sstExecutor(cc).let { if (expectFailure) it.expectFailure() else it }
  return executor.run(taskName)
}

fun GradleBuild.updateReferenceImageForAllProjectsWithTestSuite(
  variantName: String = "debug",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
  expectFailure: Boolean = false,
  cc: BaseGradleExecutor.ConfigurationCaching = BaseGradleExecutor.ConfigurationCaching.PROJECT_ISOLATION,
): GradleBuildResult {
  val taskName = getTestSuiteUpdateTaskName(variantName, "", suiteName, targetName)
  val executor = sstExecutor(cc).let { if (expectFailure) it.expectFailure() else it }
  return executor.run(taskName)
}

fun GradleBuild.validateScreenshotTestWithTestSuite(
  variantName: String = "debug",
  projectName: String = "app",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
  expectFailure: Boolean = false,
  cc: BaseGradleExecutor.ConfigurationCaching = BaseGradleExecutor.ConfigurationCaching.PROJECT_ISOLATION,
): GradleBuildResult {
  val taskName = getTestSuiteValidateTaskName(variantName, projectName, suiteName, targetName)
  val executor = sstExecutor(cc).let { if (expectFailure) it.expectFailure() else it }
  return executor.run(taskName)
}

fun GradleBuild.validateScreenshotTestForAllProjectsWithTestSuite(
  variantName: String = "debug",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
  expectFailure: Boolean = false,
  cc: BaseGradleExecutor.ConfigurationCaching = BaseGradleExecutor.ConfigurationCaching.PROJECT_ISOLATION,
): GradleBuildResult {
  val taskName = getTestSuiteValidateTaskName(variantName, "", suiteName, targetName)
  val executor = sstExecutor(cc).let { if (expectFailure) it.expectFailure() else it }
  return executor.run(taskName)
}

fun getTestSuiteReferenceDir(
  project: GradleProject<*>,
  testClassFqcn: String,
  variantName: String = "debug",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
): Path {
  val classPath = testClassFqcn.replace('.', '/')
  return project.resolve("src/${suiteName}${targetName.usLocaleCapitalize()}${variantName.usLocaleCapitalize()}/reference/$classPath")
}

fun getTestSuiteDiffDir(
  project: GradleProject<*>,
  testClassFqcn: String,
  variantName: String = "debug",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
): Path {
  val taskName = getTestSuiteValidateTaskName(variantName, "", suiteName, targetName)
  val classPath = testClassFqcn.replace('.', '/')
  return project.buildDir.resolve("intermediates/$variantName/$taskName/results/diffs/$classPath")
}

fun getTestSuiteRenderedDir(
  project: GradleProject<*>,
  testClassFqcn: String,
  variantName: String = "debug",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
): Path {
  val taskName = getTestSuiteValidateTaskName(variantName, "", suiteName, targetName)
  val classPath = testClassFqcn.replace('.', '/')
  return project.buildDir.resolve("intermediates/$variantName/$taskName/results/rendered/$classPath")
}

fun getTestSuiteHtmlReportDir(
  project: GradleProject<*>,
  variantName: String = "debug",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
): Path {
  val taskName = getTestSuiteValidateTaskName(variantName, "", suiteName, targetName)
  return project.buildDir.resolve("reports/tests/$taskName")
}

fun getTestSuiteHtmlReportIndexFile(
  project: GradleProject<*>,
  variantName: String = "debug",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
): Path {
  return getTestSuiteHtmlReportDir(project, variantName, suiteName, targetName).resolve("index.html")
}

fun getTestSuiteHtmlReportClassFile(
  project: GradleProject<*>,
  testClassFqcn: String,
  variantName: String = "debug",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
): Path {
  return getTestSuiteHtmlReportDir(project, variantName, suiteName, targetName).resolve("$testClassFqcn/index.html")
}

fun getTestSuiteXmlReportFile(
  project: GradleProject<*>,
  variantName: String = "debug",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
  testClassFqcn: String? = null,
): Path {
  val taskName = getTestSuiteValidateTaskName(variantName, "", suiteName, targetName)
  val xmlDir = project.buildDir.resolve("intermediates/$variantName/$taskName/results")
  val fileName = if (testClassFqcn != null) "TEST-$testClassFqcn.xml" else "TEST-preview-screenshot-test-engine.xml"
  return xmlDir.resolve(fileName)
}

fun getTestSuiteEngineInputs(
  project: GradleProject<*>,
  variantName: String = "debug",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
): Properties {
  val taskName = getTestSuiteValidateTaskName(variantName, "", suiteName, targetName)
  val inputsFile = project.buildDir.resolve("intermediates/$variantName/$taskName/junit_inputs.txt")
  return Properties().apply { inputsFile.inputStream().use { load(it) } }
}

fun getLayoutlibDataDir(
  project: GradleProject<*>,
  variantName: String = "debug",
  suiteName: String = "screenshotTest",
  targetName: String = "default",
): Path {
  val inputs = getTestSuiteEngineInputs(project, variantName, suiteName, targetName)
  val propertyName = AgpTestSuiteInputParameters.LAYOUTLIB_DATA_DIR.propertyName
  val value = inputs.getProperty(propertyName)
  assertThat(value).isNotNull()
  assertThat(value).isNotEmpty()
  return Paths.get(value)
}

fun snapshotDirectory(dir: Path): Map<String, Pair<Long, FileTime>> =
  Files.walk(dir).use { paths ->
    paths
      .filter { Files.isRegularFile(it) }
      .collect(Collectors.toList())
      .associate { dir.relativize(it).toString() to (Files.size(it) to Files.getLastModifiedTime(it)) }
  }

class FilterSetupWithTestSuiteCallback : GenericCallback {
  override fun handleProject(project: Project) {
    project.afterEvaluate {
      project.tasks.named(getTestSuiteValidateTaskName(projectName = ""), GradleTest::class.java) {
        it.setTestNameIncludePatterns(listOf("*simpleComposableTest*"))
      }
    }
  }
}

class FilterMatchingNothingWithTestSuiteCallback : GenericCallback {
  override fun handleProject(project: Project) {
    project.afterEvaluate {
      project.tasks.named(getTestSuiteValidateTaskName(projectName = ""), GradleTest::class.java) {
        it.setTestNameIncludePatterns(listOf("*NonExistent*"))
        it.filter.isFailOnNoMatchingTests = false
      }
    }
  }
}
