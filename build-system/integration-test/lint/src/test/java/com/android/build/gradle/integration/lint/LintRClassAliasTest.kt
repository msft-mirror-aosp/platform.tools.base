/*
 * Copyright (C) 2021 The Android Open Source Project
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
import com.android.build.gradle.integration.common.runner.FilterableParameterized
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Regression test for Issue 188871862 */
@RunWith(FilterableParameterized::class)
class LintRClassAliasTest(private val lintReportAggregation: Boolean) {

  companion object {
    @JvmStatic @Parameterized.Parameters(name = "lintReportAggregation_{0}") fun parameters() = listOf(true, false)
  }

  private val app =
    MinimalSubProject.app("com.example.app")
      .appendToBuild(
        """
        android {
            lintOptions {
                abortOnError = false
                checkOnly 'UnusedResources'
                textOutput = file("lint-results.txt")
                checkDependencies = true
            }
        }
        """
          .trimIndent()
      )

  private val lib1 =
    MinimalSubProject.lib("com.example.lib1")
      .withFile(
        "src/main/java/com/example/lib1/Foo.kt",
        // language=kotlin
        """
        package com.example.lib1

        import com.example.lib2.R as Lib2R

        class Foo {
            fun test() {
                println(Lib2R.string.lib2)
            }
        }
        """
          .trimIndent(),
      )

  private val lib2 =
    MinimalSubProject.lib("com.example.lib2")
      .withFile(
        "src/main/res/values/strings.xml",
        // language=XML
        """
        <?xml version="1.0" encoding="utf-8"?>
        <resources>
            <string name="lib2">String from lib2</string>
        </resources>
        """
          .trimIndent(),
      )

  @get:Rule
  val project: GradleTestProject =
    GradleTestProject.builder()
      .fromTestApp(
        MultiModuleTestProject.builder()
          .subproject(":app", app)
          .subproject(":lib1", lib1)
          .subproject(":lib2", lib2)
          .dependency(app, lib1)
          .dependency(lib1, lib2)
          .build()
      )
      .create()

  @Before
  fun setUp() {
    // Set android.nonTransitiveRClass=true
    TestFileUtils.appendToFile(project.gradlePropertiesFile, "${BooleanOption.NON_TRANSITIVE_R_CLASS.propertyName}=true")
    TestFileUtils.appendToFile(project.gradlePropertiesFile, "${BooleanOption.LINT_REPORT_AGGREGATION.propertyName}=$lintReportAggregation")
  }

  @Test
  fun testNoUnusedResourcesWarning() {
    val lintTask = if (lintReportAggregation) ":app:lintAggregatedDebug" else ":app:lintDebug"
    project.executor().run(lintTask)
    val lintReportFile =
      if (lintReportAggregation) {
        project.getSubproject("app").file("build/reports/aggregated-lint-results-debug.txt")
      } else {
        project.getSubproject("app").file("lint-results.txt")
      }
    assertThat(lintReportFile).exists()
    assertThat(lintReportFile).doesNotContain("UnusedResources")

    // As a control, check that we *do* see a warning if we edit the code accordingly
    TestFileUtils.searchAndReplace(project.getSubproject(":lib1").file("src/main/java/com/example/lib1/Foo.kt"), "println", "// println")
    project.executor().run(lintTask)
    assertThat(lintReportFile).exists()
    assertThat(lintReportFile).contains("UnusedResources")
  }
}
