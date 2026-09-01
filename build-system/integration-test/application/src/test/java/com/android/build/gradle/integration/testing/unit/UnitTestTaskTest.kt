/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.integration.testing.unit

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.readText
import org.junit.Rule
import org.junit.Test

class UnitTestTaskTest {

  @get:Rule
  val rule =
    GradleRule.configure().from {
      androidApplication(":app") {
        android { namespace = "com.example.app" }
        dependencies { testImplementation("junit:junit:4.13.2") }
        files.add(
          "src/test/java/com/example/FailingTest.java",
          """
          package com.example;
          import org.junit.Test;
          import static org.junit.Assert.fail;
          public class FailingTest {
              @Test
              public void failingTest() {
                  fail("This test is supposed to fail");
              }
          }
          """
            .trimIndent(),
        )
        files.add(
          "src/test/java/com/example/PassingTest.java",
          """
          package com.example;
          import org.junit.Test;
          import static org.junit.Assert.assertEquals;
          public class PassingTest {
              @Test
              public void passingTest() {
                  assertEquals(4, 2 + 2);
              }
          }
          """
            .trimIndent(),
        )
      }
      gradleProperties { add(BooleanOption.REPORT_AGGREGATION_SUPPORT, true) }
    }

  @Test
  fun testLegacyReportFormat() {
    val build = rule.build
    build.reconfigureGradleProperties { add(BooleanOption.REPORT_AGGREGATION_SUPPORT, false) }

    build.executor.expectFailure().run(":app:testDebugUnitTest")

    val reportDir = build.androidApplication().buildDir.resolve("reports/tests/testDebugUnitTest")
    assertThat(reportDir.resolve("index.html")).exists()
    assertThat(reportDir.resolve("js/report.js")).exists()
    assertThat(reportDir.resolve("css/base-style.css")).exists()
    assertThat(reportDir.resolve("data.js")).doesNotExist()
  }

  @Test
  fun testNewReportFormat() {
    val build = rule.build

    build.executor.expectFailure().run(":app:testDebugUnitTest")

    val reportDir = build.androidApplication().buildDir.resolve("reports/tests/testDebugUnitTest")
    assertThat(reportDir.resolve("index.html")).exists()
    assertThat(reportDir.resolve("script.js")).exists()
    assertThat(reportDir.resolve("styles.css")).exists()
    assertThat(reportDir.resolve("data.js")).exists()

    val dataJsContent = reportDir.resolve("data.js").readText()
    assertThat(dataJsContent).contains("const TEST_DATA_SOURCE = ")
    assertThat(dataJsContent).contains("\"projectName\":\":app\"")

    assertThat(reportDir.resolve("js/report.js")).doesNotExist()
  }

  @Test
  fun testUnitTestCachingWithReportAggregation() {
    val build = rule.build

    var result = build.executor.run(":app:testAllSuites")

    assertThat(result.didWorkTasks).contains(":app:testDebugUnitTest")

    result = build.executor.expectFailure().run(":app:testDebugUnitTest")

    assertThat(result.failedTasks).contains(":app:testDebugUnitTest")
  }
}
