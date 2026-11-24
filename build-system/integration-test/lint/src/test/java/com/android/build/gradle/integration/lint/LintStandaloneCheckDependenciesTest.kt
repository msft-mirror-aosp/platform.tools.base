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

package com.android.build.gradle.integration.lint

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Tests that the standalone lint plugin wires dependency lint models and partial results for checkDependencies. */
@RunWith(Parameterized::class)
class LintStandaloneCheckDependenciesTest(private val lintAnalysisPerComponent: Boolean) {

  companion object {
    @Parameterized.Parameters(name = "lintAnalysisPerComponent_{0}") @JvmStatic fun params() = listOf(true, false)
  }

  @get:Rule
  val rule = GradleRule.from {
    javaLibrary(":java-lib1") {
      applyPlugin(PluginType.LINT) {
        checkDependencies = false
        abortOnError = false
        textReport = true
        textOutput = projectDotFile("lint-results.txt")
        disable += "JavaPluginLanguageLevel"
      }
      dependencies {
        implementation(project(":java-lib2"))
        runtimeOnly(project(":java-lib3"))
        compileOnly(project(":java-lib4"))
      }
      files {
        add(
          "src/main/java/com/example/lib1/Lib1Class.java",
          // language=java
          """
          package com.example.lib1;

          public class Lib1Class {
              public Boolean getOne() {
                  return new Boolean(true);
              }
          }
          """
            .trimIndent(),
        )
      }
    }
    javaLibrary(":java-lib2") {
      applyPlugin(PluginType.LINT) { disable += "JavaPluginLanguageLevel" }
      files {
        add(
          "src/main/java/com/example/lib2/Lib2Class.java",
          // language=java
          """
          package com.example.lib2;

          public class Lib2Class {
              public Boolean getTwo() {
                  return new Boolean(true);
              }
          }
          """
            .trimIndent(),
        )
      }
    }
    javaLibrary(":java-lib3") {
      applyPlugin(PluginType.LINT) { disable += "JavaPluginLanguageLevel" }
      files {
        add(
          "src/main/java/com/example/lib3/Lib3Class.java",
          // language=java
          """
          package com.example.lib3;

          public class Lib3Class {
              public Boolean getThree() {
                  return new Boolean(true);
              }
          }
          """
            .trimIndent(),
        )
      }
    }
    javaLibrary(":java-lib4") {
      applyPlugin(PluginType.LINT) { disable += "JavaPluginLanguageLevel" }
      files {
        add(
          "src/main/java/com/example/lib4/Lib4Class.java",
          // language=java
          """
          package com.example.lib4;

          public class Lib4Class {
              public Boolean getFour() {
                  return new Boolean(true);
              }
          }
          """
            .trimIndent(),
        )
      }
    }
  }

  @Test
  fun testCheckDependenciesFalseAndTrue() {
    val build = rule.build
    val executor = build.executor.with(BooleanOption.LINT_ANALYSIS_PER_COMPONENT, lintAnalysisPerComponent)
    val expectedLib2AnalyzeTask = if (lintAnalysisPerComponent) ":java-lib2:lintAnalyzeJvmMain" else ":java-lib2:lintAnalyzeJvm"

    // With checkDependencies = false, dependency analysis still runs (for partial results) but dependency issues are not reported.
    executor.run(":java-lib1:lint").apply { assertTask(expectedLib2AnalyzeTask).didWork() }

    val lib1File = build.javaLibrary(":java-lib1").resolve("src/main/java/com/example/lib1/Lib1Class.java").toFile()
    val reportFile = build.javaLibrary(":java-lib1").resolve("lint-results.txt")
    assertThat(reportFile)
      .contentWithUnixLineSeparatorsIsExactly(
        """
        ${lib1File.absolutePath}:5: Warning: Use Boolean.valueOf(true) instead [UseValueOf]
                return new Boolean(true);
                       ~~~~~~~~~~~~~~~~~

           Explanation for issues of type "UseValueOf":
           You should not call the constructor for wrapper classes directly, such
           as`new Integer(42)`. Instead, call the valueOf factory method, such as
           Integer.valueOf(42). This will typically use less memory because common
           integers such as 0 and 1 will share a single instance.

        0 errors, 1 warning
        """
          .trimIndent()
      )

    // With checkDependencies = true, issues from implementation (:java-lib2), runtimeOnly (:java-lib3), and compileOnly (:java-lib4)
    // dependencies are reported.
    build.javaLibrary(":java-lib1").reconfigure {
      reconfigurePlugin(PluginType.LINT) { checkDependencies = true }
    }

    executor.run(":java-lib1:lint")
    // Re-run to verify configuration cache compatibility.
    executor.run(":java-lib1:lint").assertConfigurationCacheHit()

    val lib2File = build.javaLibrary(":java-lib2").resolve("src/main/java/com/example/lib2/Lib2Class.java").toFile()
    val lib3File = build.javaLibrary(":java-lib3").resolve("src/main/java/com/example/lib3/Lib3Class.java").toFile()
    val lib4File = build.javaLibrary(":java-lib4").resolve("src/main/java/com/example/lib4/Lib4Class.java").toFile()
    assertThat(reportFile)
      .contentWithUnixLineSeparatorsIsExactly(
        """
        ${lib1File.absolutePath}:5: Warning: Use Boolean.valueOf(true) instead [UseValueOf]
                return new Boolean(true);
                       ~~~~~~~~~~~~~~~~~
        ${lib2File.absolutePath}:5: Warning: Use Boolean.valueOf(true) instead [UseValueOf]
                return new Boolean(true);
                       ~~~~~~~~~~~~~~~~~
        ${lib3File.absolutePath}:5: Warning: Use Boolean.valueOf(true) instead [UseValueOf]
                return new Boolean(true);
                       ~~~~~~~~~~~~~~~~~
        ${lib4File.absolutePath}:5: Warning: Use Boolean.valueOf(true) instead [UseValueOf]
                return new Boolean(true);
                       ~~~~~~~~~~~~~~~~~

           Explanation for issues of type "UseValueOf":
           You should not call the constructor for wrapper classes directly, such
           as`new Integer(42)`. Instead, call the valueOf factory method, such as
           Integer.valueOf(42). This will typically use less memory because common
           integers such as 0 and 1 will share a single instance.

        0 errors, 4 warnings
        """
          .trimIndent()
      )
  }

  @Test
  fun testCheckDependenciesLintVital() {
    val build = rule.build
    // Remove UseValueOf from :java-lib1 so only dependencies (:java-lib2, :java-lib3) have fatal issues.
    build.javaLibrary(":java-lib1").files.update("src/main/java/com/example/lib1/Lib1Class.java") {
      searchAndReplace("return new Boolean(true);", "return Boolean.TRUE;")
    }
    build.javaLibrary(":java-lib1").reconfigure {
      reconfigurePlugin(PluginType.LINT) {
        abortOnError = true
        fatal += "UseValueOf"
      }
    }
    build.javaLibrary(":java-lib2").reconfigure {
      reconfigurePlugin(PluginType.LINT) { fatal += "UseValueOf" }
    }
    build.javaLibrary(":java-lib3").reconfigure {
      reconfigurePlugin(PluginType.LINT) { fatal += "UseValueOf" }
    }
    build.javaLibrary(":java-lib4").reconfigure {
      reconfigurePlugin(PluginType.LINT) { fatal += "UseValueOf" }
    }

    val executor = build.executor.with(BooleanOption.LINT_ANALYSIS_PER_COMPONENT, lintAnalysisPerComponent)
    val expectedLib2VitalAnalyzeTask =
      if (lintAnalysisPerComponent) ":java-lib2:lintVitalAnalyzeJvmMain" else ":java-lib2:lintVitalAnalyzeJvm"

    // With checkDependencies = false, :java-lib1 has no fatal issue so lintVital succeeds.
    executor.run(":java-lib1:lintVital").apply { assertTask(expectedLib2VitalAnalyzeTask).didWork() }

    // With checkDependencies = true, dependency fatal issues cause :java-lib1:lintVital to fail.
    build.javaLibrary(":java-lib1").reconfigure {
      reconfigurePlugin(PluginType.LINT) { checkDependencies = true }
    }
    val lib2File = build.javaLibrary(":java-lib2").resolve("src/main/java/com/example/lib2/Lib2Class.java").toFile()
    val lib3File = build.javaLibrary(":java-lib3").resolve("src/main/java/com/example/lib3/Lib3Class.java").toFile()
    val lib4File = build.javaLibrary(":java-lib4").resolve("src/main/java/com/example/lib4/Lib4Class.java").toFile()
    executor.expectFailure().run(":java-lib1:lintVital").apply {
      assertErrorContains(
        """
        ${lib2File.absolutePath}:5: Error: Use Boolean.valueOf(true) instead [UseValueOf]
                return new Boolean(true);
                       ~~~~~~~~~~~~~~~~~
        ${lib3File.absolutePath}:5: Error: Use Boolean.valueOf(true) instead [UseValueOf]
                return new Boolean(true);
                       ~~~~~~~~~~~~~~~~~
        ${lib4File.absolutePath}:5: Error: Use Boolean.valueOf(true) instead [UseValueOf]
                return new Boolean(true);
                       ~~~~~~~~~~~~~~~~~

           Explanation for issues of type "UseValueOf":
           You should not call the constructor for wrapper classes directly, such
           as`new Integer(42)`. Instead, call the valueOf factory method, such as
           Integer.valueOf(42). This will typically use less memory because common
           integers such as 0 and 1 will share a single instance.

        3 errors
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun testCheckDependenciesUpdateLintBaseline() {
    val build = rule.build
    build.javaLibrary(":java-lib1").reconfigure {
      reconfigurePlugin(PluginType.LINT) {
        checkDependencies = true
        abortOnError = true
        error += "UseValueOf"
        baseline = projectDotFile("lint-baseline.xml")
      }
    }

    val executor = build.executor.with(BooleanOption.LINT_ANALYSIS_PER_COMPONENT, lintAnalysisPerComponent)
    executor.run(":java-lib1:updateLintBaseline")

    val baselineFile = build.javaLibrary(":java-lib1").resolve("lint-baseline.xml")
    assertThat(baselineFile).exists()
    assertThat(baselineFile).containsAllOf("Lib1Class.java", "Lib2Class.java", "Lib3Class.java", "Lib4Class.java")

    // With all own + dependency issues baselined, :java-lib1:lint now succeeds with no errors.
    executor.run(":java-lib1:lint")
  }
}
