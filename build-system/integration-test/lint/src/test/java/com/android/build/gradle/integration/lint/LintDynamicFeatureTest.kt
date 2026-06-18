/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class LintDynamicFeatureTest(private val lintReportAggregation: Boolean) {

  companion object {
    @Parameterized.Parameters(name = "lintReportAggregation={0}") @JvmStatic fun params() = listOf(true, false)
  }

  @get:Rule val project: GradleTestProject = GradleTestProject.builder().fromTestProject("dynamicApp").create()

  private val app = MinimalSubProject.app("com.example.test.app").appendToBuild("android.dynamicFeatures = [':feature']")
  private val feature = MinimalSubProject.dynamicFeature("com.example.test.feature")
  private val lib1 = MinimalSubProject.lib("com.example.lib1")
  private val lib2 = MinimalSubProject.lib("com.example.lib2")

  @get:Rule
  val projectWithLibs: GradleTestProject =
    GradleTestProject.builder()
      .fromTestApp(
        MultiModuleTestProject.builder()
          .subproject(":app", app)
          .subproject(":feature", feature)
          .subproject(":lib1", lib1)
          .subproject(":lib2", lib2)
          .dependency(feature, app)
          .dependency(feature, lib1)
          .dependency("api", app, lib2)
          .build()
      )
      .create()

  private fun executor() = project.executor().with(BooleanOption.LINT_REPORT_AGGREGATION, lintReportAggregation)

  private fun executorWithLibs() = projectWithLibs.executor().with(BooleanOption.LINT_REPORT_AGGREGATION, lintReportAggregation)

  private fun getLintDebugTasks(projectPath: String): List<String> {
    return if (lintReportAggregation) {
      listOf("$projectPath:lintDebug", "$projectPath:lintAggregatedDebug")
    } else {
      listOf("$projectPath:lintDebug")
    }
  }

  private fun getReportTasks(projectPath: String, isApp: Boolean = false): List<String> {
    return if (lintReportAggregation) {
      if (isApp) {
        listOf("$projectPath:createLocalLintReportDebug", "$projectPath:createAggregatedLintReportDebug")
      } else {
        listOf("$projectPath:createLocalLintReportDebug")
      }
    } else {
      listOf("$projectPath:lintReportDebug")
    }
  }

  @Test
  fun testUnusedResourcesInFeatureModules() {
    executor().run("clean", "lint")

    val file = project.file("app/lint-results.txt")
    assertThat(file)
      .containsAllOf(
        "The resource R.string.unused_from_feature1 appears to be unused",
        "The resource R.string.unused_from_app appears to be unused",
      )

    assertThat(file).doesNotContain("The resource R.string.used_from_app appears to be unused")
    assertThat(file).doesNotContain("The resource R.string.used_from_feature1 appears to be unused")
    assertThat(file).doesNotContain("The resource R.string.used_from_feature2 appears to be unused")
  }

  // TODO(b/178810169) Running lint from an app module with checkDependencies true should also
  //  analyze all of the dynamic feature module dependencies.
  @Test
  fun testLintAnchorTaskWithDynamicFeatures() {
    for (subProject in listOf(":app", ":lib1", ":lib2")) {
      TestFileUtils.appendToFile(
        projectWithLibs.getSubproject(subProject).buildFile,
        """
        android {
            lintOptions {
                abortOnError = false
                textOutput = file("lint-results.txt")
            }
        }
        """
          .trimIndent(),
      )
    }

    // Add hard-coded resource to each module
    FileUtils.writeToFile(File(projectWithLibs.getSubproject(":app").mainResDir, "layout/app_layout.xml"), layout_text)
    FileUtils.writeToFile(File(projectWithLibs.getSubproject(":feature").mainResDir, "layout/feature_layout.xml"), layout_text)
    FileUtils.writeToFile(File(projectWithLibs.getSubproject(":lib1").mainResDir, "layout/lib1_layout.xml"), layout_text)
    FileUtils.writeToFile(File(projectWithLibs.getSubproject(":lib2").mainResDir, "layout/lib2_layout.xml"), layout_text)

    // Run twice to catch issues with configuration caching
    executorWithLibs().run("clean", "lint")
    executorWithLibs().run("clean", "lint")
    projectWithLibs.buildResult.assertConfigurationCacheHit()
    assertThat(projectWithLibs.buildResult.failedTasks).isEmpty()

    assertThat(projectWithLibs.buildResult.tasks).contains(":app:lint")
    assertThat(projectWithLibs.buildResult.tasks).containsAllIn(getLintDebugTasks(":app"))
    assertThat(projectWithLibs.buildResult.tasks).containsAllIn(getReportTasks(":app", isApp = true))

    assertThat(projectWithLibs.buildResult.tasks).contains(":lib1:lint")
    assertThat(projectWithLibs.buildResult.tasks).containsAllIn(getLintDebugTasks(":lib1"))
    assertThat(projectWithLibs.buildResult.tasks).containsAllIn(getReportTasks(":lib1"))

    assertThat(projectWithLibs.buildResult.tasks).contains(":lib2:lint")
    assertThat(projectWithLibs.buildResult.tasks).containsAllIn(getLintDebugTasks(":lib2"))
    assertThat(projectWithLibs.buildResult.tasks).containsAllIn(getReportTasks(":lib2"))

    // There should not be a lint reporting task or lint anchor task for the dynamic feature.
    assertThat(projectWithLibs.buildResult.tasks).doesNotContain(":feature:lint")
    assertThat(projectWithLibs.buildResult.tasks).doesNotContain(":feature:lintDebug")
    assertThat(projectWithLibs.buildResult.tasks).containsNoneIn(getReportTasks(":feature"))

    assertThat(projectWithLibs.file("app/lint-results.txt"))
      .containsAllOf("app_layout.xml:10: Warning: Hardcoded string", "feature_layout.xml:10: Warning: Hardcoded string")
    assertThat(projectWithLibs.file("lib1/lint-results.txt")).contains("lib1_layout.xml:10: Warning: Hardcoded string")
    assertThat(projectWithLibs.file("lib2/lint-results.txt")).contains("lib2_layout.xml:10: Warning: Hardcoded string")
  }

  @Test
  fun runLintFromApp() {
    // Run twice to catch issues with configuration caching
    executor().run(":app:clean", ":app:lint")
    executor().run(":app:clean", ":app:lint")
    project.buildResult.assertConfigurationCacheHit()
    assertThat(project.buildResult.failedTasks).isEmpty()

    assertThat(project.file("app/lint-results.txt"))
      .containsAllOf(
        "base_layout.xml:10: Warning: Hardcoded string",
        "feature_layout.xml:10: Warning: Hardcoded string",
        "feature2_layout.xml:10: Warning: Hardcoded string",
      )
  }

  // Regression test for b/190855628
  @Test
  fun testDynamicFeatureIssuesWhenLibrariesPresent() {
    TestFileUtils.appendToFile(
      projectWithLibs.getSubproject("app").buildFile,
      """
      android {
          lintOptions {
              checkDependencies = true
              abortOnError = false
              textOutput = file("lint-results.txt")
          }
      }
      """
        .trimIndent(),
    )
    FileUtils.writeToFile(File(projectWithLibs.getSubproject(":feature").mainResDir, "layout/feature_layout.xml"), layout_text)
    executorWithLibs().run(":app:clean", ":app:lint")
    assertThat(projectWithLibs.buildResult.failedTasks).isEmpty()

    assertThat(projectWithLibs.file("app/lint-results.txt")).contains("feature_layout.xml:10: Warning: Hardcoded string")
  }

  @Test
  fun testLintUpToDate() {
    val tasks = getLintDebugTasks(":app")
    executor().run(tasks)
    executor().run(tasks)
    assertThat(project.buildResult.upToDateTasks)
      .containsAtLeastElementsIn(
        getReportTasks(":app", isApp = true) + listOf(":app:lintAnalyzeDebug", ":feature1:lintAnalyzeDebug", ":feature2:lintAnalyzeDebug")
      )
  }

  @Test
  fun testLintWithIncrementalChanges() {
    val tasks = getLintDebugTasks(":app")
    executor().run(tasks)
    TestFileUtils.searchAndReplace(project.file("feature2/src/main/res/layout/feature2_layout.xml"), "\"Button\"", "\"AAAAAAAAAAA\"")
    executor().run(tasks)
    assertThat(project.buildResult.upToDateTasks).containsAtLeastElementsIn(listOf(":app:lintAnalyzeDebug", ":feature1:lintAnalyzeDebug"))
    assertThat(project.buildResult.didWorkTasks)
      .containsAtLeastElementsIn(getReportTasks(":app", isApp = true) + listOf(":feature2:lintAnalyzeDebug"))
  }

  @Test
  fun testLintVital() {
    // check that lintVital succeeds before change to feature manifest
    executor().run(":app:lintVitalRelease")
    ScannerSubject.assertThat(project.buildResult.stdout).contains("BUILD SUCCESSFUL")

    val featureManifest = project.getSubproject(":feature1").file("src/main/AndroidManifest.xml")
    TestFileUtils.searchAndReplace(featureManifest, "<application>", "<application android:debuggable=\"true\">")

    val failureMessage = executor().expectFailure().run(":app:lintVitalRelease").failureMessage
    assertThat(failureMessage).contains("fatal errors")
  }

  @Test
  fun testLintVitalUpToDate() {
    executor().run("lintVitalRelease")

    assertThat(project.buildResult.tasks).contains(":app:lintVitalRelease")
    assertThat(project.buildResult.tasks).contains(":app:lintVitalReportRelease")
    assertThat(project.buildResult.tasks).contains(":app:lintVitalAnalyzeRelease")
    assertThat(project.buildResult.tasks).contains(":feature1:lintVitalAnalyzeRelease")
    assertThat(project.buildResult.tasks).contains(":feature2:lintVitalAnalyzeRelease")
    assertThat(project.buildResult.tasks).doesNotContain(":feature1:lintVitalRelease")
    assertThat(project.buildResult.tasks).doesNotContain(":feature2:lintVitalRelease")

    executor().run("lintVitalRelease")

    assertThat(project.buildResult.upToDateTasks).contains(":app:lintVitalReportRelease")
    assertThat(project.buildResult.upToDateTasks).contains(":app:lintVitalAnalyzeRelease")
    assertThat(project.buildResult.upToDateTasks).contains(":feature1:lintVitalAnalyzeRelease")
    assertThat(project.buildResult.upToDateTasks).contains(":feature2:lintVitalAnalyzeRelease")
  }

  @Test
  fun testLintFixWithDynamicFeatures() {
    project.getSubproject(":app").buildFile.appendText("\nandroid.lintOptions.error 'SyntheticAccessor'\n")
    // TODO(b/178810169) should dynamic features inherit some lintOptions from base app?
    project.getSubproject(":feature1").buildFile.appendText("\nandroid.lintOptions.error 'SyntheticAccessor'\n")
    val appSourceFile = project.getSubproject(":app").file("src/main/java/com/example/foo/Foo.java")
    appSourceFile.parentFile.mkdirs()
    appSourceFile.writeText(
      """
      package com.example.foo;

      public class Foo {

          private void foo() {
              new InnerClass().bar();
          }

          static class InnerClass {
              private void bar() {}
          }
      }
      """
        .trimIndent()
    )
    val featureSourceFile = project.getSubproject(":feature1").file("src/main/java/com/example/bar/Bar.java")
    featureSourceFile.parentFile.mkdirs()
    featureSourceFile.writeText(
      """
      package com.example.bar;

      public class Bar {

          private void foo() {
              new InnerClass().bar();
          }

          static class InnerClass {
              private void bar() {}
          }
      }
      """
        .trimIndent()
    )

    executor()
      .expectFailure()
      .run(":app:lintFix")
      .assertErrorContains("Aborting build since sources were modified to apply quickfixes after compilation")

    // Make sure quickfixes worked too.
    // The original source files have this:
    //    ...
    //    private void bar() {}
    //    ...
    // After applying quickfixes, they contains this:
    //    ...
    //    void bar() {}
    //    ...
    assertThat(appSourceFile).doesNotContain("private void bar()")
    assertThat(appSourceFile).contains("void bar()")
    assertThat(featureSourceFile).doesNotContain("private void bar()")
    assertThat(featureSourceFile).contains("void bar()")
    executor().run("clean", "lintFix").assertOutputContains("BUILD SUCCESSFUL")
  }

  @Test
  fun testLintFixAnchorTaskWithDynamicFeatures() {
    executor().run("lintFix")

    assertThat(project.buildResult.tasks).contains(":app:lintFix")
    assertThat(project.buildResult.tasks).contains(":app:lintFixDebug")
    // There should not be lintFix tasks or lintFix anchor tasks for the dynamic features.
    assertThat(project.buildResult.tasks).doesNotContain(":feature1:lintFix")
    assertThat(project.buildResult.tasks).doesNotContain(":feature1:lintFixDebug")
    assertThat(project.buildResult.tasks).doesNotContain(":feature2:lintFix")
    assertThat(project.buildResult.tasks).doesNotContain(":feature2:lintFixDebug")
  }

  // Regression test for b/186772704 and b/187319075
  @Test
  fun testNoMisplacedOutputFiles() {
    TestFileUtils.appendToFile(project.getSubproject(":app").buildFile, "\n\nbuildDir = 'foo'\n\n")
    val tasks = listOf("app:clean") + getLintDebugTasks(":app")
    executor().run(tasks)
    val defaultAppBuildDir = File(project.getSubproject(":app").projectDir, "build")
    assertThat(!defaultAppBuildDir.exists() || defaultAppBuildDir.walk().none { it.isFile }).isTrue()
    val appBuildDir = File(project.getSubproject(":app").projectDir, "foo")
    assertThat(appBuildDir).exists()
    appBuildDir.listFiles()?.forEach { assertThat(it).isDirectory() }
  }
}

private val layout_text =
  """
  <?xml version="1.0" encoding="utf-8"?>
  <android.support.constraint.ConstraintLayout xmlns:app="http://schemas.android.com/apk/res-auto"
      android:layout_height="match_parent"
      xmlns:android="http://schemas.android.com/apk/res/android" android:layout_width="match_parent">

      <Button
          android:id="@+id/button"
          android:layout_width="wrap_content"
          android:layout_height="wrap_content"
          android:text="Button" />
  </android.support.constraint.ConstraintLayout>
  """
    .trimIndent()
