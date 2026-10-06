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
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.CustomObjectInstance
import com.android.build.gradle.integration.common.fixture.project.builder.StringHandler
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.apk.Zip
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Paths
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import org.gradle.api.artifacts.dsl.DependencyCollector
import org.junit.Rule
import org.junit.Test

/** Multi-module tests for screenshot test suites declared through `testOptions.screenshotTests`. */
class ScreenshotMultiModuleTestSuiteTest {

  @get:Rule
  val rule =
    GradleRule.configure().withProfileOutput().from {
      androidApplication { setupScreenshotTestSuiteProject() }
      androidLibrary { setupScreenshotTestSuiteProject() }
      repeat(2) { androidLibrary(":lib2_$it") { setupScreenshotTestSuiteProject(addEmptyJarToClassPath = false) } }
      gradleProperties {
        add(BooleanOption.ENABLE_SCREENSHOT_TEST, true)
        add(BooleanOption.TEST_SUITE_SUPPORT, true)
      }
    }

  @Test
  fun runPreviewScreenshotTestWithMultiModuleProject() {
    val build = rule.build { useOldPluginStyleForSeparateClassloaders = true }
    verifyClassLoaderSetup(build.updateReferenceImageForAllProjectsWithTestSuite())
    build.validateScreenshotTestForAllProjectsWithTestSuite()
  }

  @Test
  fun runUpdateScreenshotTestWithMultiModuleProjectBySingleWorker() {
    val build = rule.build
    build.updateReferenceImageForAllProjectsWithTestSuite()
    build.sstExecutor().withArguments(listOf("--max-workers", "1")).run(getTestSuiteValidateTaskName(projectName = ""))
  }

  @Test
  fun runPreviewScreenshotTestsOnMultipleFlavors() {
    val build = rule.build {
      androidApplication {
        android {
          flavorDimensions += "new"
          productFlavors {
            create("flavor1") { it.dimension = "new" }
            create("flavor2") { it.dimension = "new" }
          }
          testOptions.screenshotTests.named("screenshotTest") { it.targetVariants.addAll(FLAVOR_VARIANTS) }
        }
        files.update("src/screenshotTest/java/com/ExampleTest.kt").transform {
          """
          /*
          $it
          */
          """
            .trimIndent()
        }
      }
    }
    val appProject = build.androidApplication()

    build.updateReferenceImageWithTestSuite("flavor1Debug")
    build.updateReferenceImageWithTestSuite("flavor2Debug")

    val flavor1ReferenceScreenshotDir = getTestSuiteReferenceDir(appProject, "pkg.name.TopLevelPreviewTestKt", variantName = "flavor1Debug")
    val flavor2ReferenceScreenshotDir = getTestSuiteReferenceDir(appProject, "pkg.name.TopLevelPreviewTestKt", variantName = "flavor2Debug")
    assertThat(flavor1ReferenceScreenshotDir.listDirectoryEntries().single().name).isEqualTo("simpleComposableTest_3_748aa731_0.png")
    assertThat(flavor2ReferenceScreenshotDir.listDirectoryEntries().single().name).isEqualTo("simpleComposableTest_3_748aa731_0.png")

    // Test suites have no task that validates all the variants, like validateScreenshotTest of the screenshot test plugin.
    FLAVOR_VARIANTS.forEach { build.validateScreenshotTestWithTestSuite(it) }

    FLAVOR_VARIANTS.forEach { variantName ->
      assertThat(getTestSuiteHtmlReportIndexFile(appProject, variantName = variantName)).exists()
      assertThat(getTestSuiteHtmlReportClassFile(appProject, "pkg.name.TopLevelPreviewTestKt", variantName = variantName)).exists()
      assertThat(getTestSuiteHtmlReportTestStatuses(appProject, "pkg.name.TopLevelPreviewTestKt", variantName = variantName))
        .containsExactly("simpleComposableTest_3", TEST_SUITE_REPORT_PASSED)
      assertThat(getTestSuiteHtmlReportPackageNames(appProject, variantName = variantName)).contains("pkg.name")

      val diffDir = getTestSuiteDiffDir(appProject, "pkg.name.TopLevelPreviewTestKt", variantName = variantName)
      assert(diffDir.listDirectoryEntries().isEmpty())
    }
  }

  @Test
  fun runPreviewScreenshotTestWithCrossModuleResources() {
    val addCrossModuleTest: AndroidProjectDefinition<out CommonExtension>.() -> Unit = {
      // Like screenshotTestImplementation(project(":lib")) in the standalone test, :lib is a dependency of the screenshot test suite
      // only, so its resources are not resources of the tested application or library.
      android {
        testOptions.screenshotTests.named("screenshotTest") { it.dependencies { implementation.add(ProjectDependencyNotation(":lib")) } }
      }
      files {
        add(
          "src/screenshotTest/java/com/CrossModuleTest.kt",
          """
          package pkg.name

          import androidx.compose.ui.tooling.preview.Preview
          import androidx.compose.runtime.Composable
          import com.android.tools.screenshot.PreviewTest

          class CrossModuleTest {
              @PreviewTest
              @Preview(showBackground = true)
              @Composable
              fun crossModuleComposableTest() {
                  LibComposable()
              }
          }
          """
            .trimIndent(),
        )
      }
    }
    val build = rule.build {
      androidApplication(action = addCrossModuleTest)
      androidLibrary(":lib2_0", action = addCrossModuleTest)
      androidLibrary {
        files {
          add("src/main/res/values/strings.xml", "<resources><string name=\"lib_string\">Library String</string></resources>")
          add("src/main/res/values/colors.xml", "<resources><color name=\"lib_color\">#FF0000</color></resources>")
          add(
            "src/main/java/com/LibComposable.kt",
            """
            package pkg.name

            import androidx.compose.material.Text
            import androidx.compose.runtime.Composable
            import androidx.compose.ui.res.stringResource
            import androidx.compose.ui.res.colorResource
            import androidx.compose.ui.Modifier
            import androidx.compose.foundation.background
            import pkg.name.lib.R

            @Composable
            fun LibComposable() {
                Text(
                    text = stringResource(R.string.lib_string),
                    modifier = Modifier.background(colorResource(R.color.lib_color))
                )
            }
            """
              .trimIndent(),
          )
        }
      }
    }

    val libRStringClass = "pkg/name/lib/R\$string.class"
    for ((projectName, project) in listOf("app" to build.androidApplication(), "lib2_0" to build.androidLibrary(":lib2_0"))) {
      val updateResult = build.updateReferenceImageWithTestSuite(projectName = projectName)
      updateResult.assertOutputDoesNotContain("ScreenshotError")
      updateResult.assertErrorDoesNotContain("ScreenshotError")

      val result = build.validateScreenshotTestWithTestSuite(projectName = projectName)
      result.assertOutputDoesNotContain("ScreenshotError")

      // The R class of :lib comes from the R class linked for the test suite, which comes first. It is not in the R class of the tested
      // application or library, which does not depend on :lib.
      val rClassJars =
        getTestSuiteEngineInputs(project).getProperty(AgpTestSuiteInputParameters.R_CLASS_JARS.propertyName).split(File.pathSeparator)
      Zip(Paths.get(rClassJars.first())).use { assertThat(it.getEntry(libRStringClass)).isNotNull() }

      val classHtmlReport = getTestSuiteHtmlReportClassFile(project, "pkg.name.CrossModuleTest")
      assertThat(classHtmlReport).exists()
      assertThat(getTestSuiteHtmlReportTestStatuses(project, "pkg.name.CrossModuleTest"))
        .containsExactly("crossModuleComposableTest", TEST_SUITE_REPORT_PASSED)
    }

    val appRClassJar =
      build.androidApplication().buildDir.resolve("intermediates/compile_and_runtime_r_class_jar/debug/processDebugResources/R.jar")
    Zip(appRClassJar).use { assertThat(it.getEntry(libRStringClass)).isNull() }
  }

  /**
   * A dependency on the project at [path], to add to a [DependencyCollector] of a test suite, like `implementation(project(path))`.
   *
   * The test DSL records only the [CharSequence] variant of [DependencyCollector.add], so this is a [CharSequence] that is written to the
   * build file as an expression that creates a project dependency, in both Groovy and Kotlin. The expression can't be `project(path)`:
   * [com.android.build.api.dsl.AgpTestSuiteDependencies] does not extend Gradle's `Dependencies`, so in its scope `project(path)` is
   * `Project.project(path)`, which returns the project and not a dependency on it, and using a project as a dependency notation is
   * deprecated. The expression uses [org.gradle.api.artifacts.dsl.DependencyFactory.createProjectDependency] instead, as Gradle recommends.
   */
  private class ProjectDependencyNotation(private val path: String) : CharSequence by path, CustomObjectInstance {
    override fun toString(handler: StringHandler) = "project.dependencyFactory.createProjectDependency(${handler.quoteString(path)})"
  }

  companion object {
    private val FLAVOR_VARIANTS = listOf("flavor1Debug", "flavor2Debug")
  }
}
