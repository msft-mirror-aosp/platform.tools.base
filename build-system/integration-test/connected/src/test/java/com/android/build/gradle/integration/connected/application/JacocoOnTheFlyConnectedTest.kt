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

package com.android.build.gradle.integration.connected.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class JacocoOnTheFlyConnectedTest(val runWithBuiltInPlatform: Boolean) {

  companion object {
    @ClassRule @JvmField val emulator: ExternalResource = getEmulator()

    @JvmStatic
    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }

  @get:Rule
  val rule =
    GradleRule.configure().from {
      // Library module to verify multi-package exclusion filtering
      androidLibrary(":libModule") {
        android {
          namespace = "com.example.libmodule"
          enableKotlin = false
        }
        files.add(
          "src/main/java/com/example/libmodule/LibClass.java",
          """
          package com.example.libmodule;
          public class LibClass {
              public static int add(int a, int b) { return a + b; }
          }
          """
            .trimIndent(),
        )
      }

      androidApplication(":app") {
        android {
          namespace = "com.example.helloworld"
          experimentalProperties["android.experimental.testOptions.coverage.coverageType"] = "ON_THE_FLY"
          defaultConfig {
            minSdk = 24
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          buildTypes { named("debug") { it.enableAndroidTestCoverage = true } }
        }
        dependencies {
          implementation(project(":libModule"))
          androidTestImplementation("com.android.tools.test:coverage-agent:1.0.0")
          androidTestImplementation("androidx.test:core:1.4.0-alpha06")
          androidTestImplementation("androidx.test.ext:junit:1.1.3-alpha02")
          androidTestImplementation("androidx.test:monitor:1.4.0-alpha06")
          androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
          androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
        }
        files.add(
          "src/main/java/com/example/helloworld/AppHelper.java",
          """
          package com.example.helloworld;
          public class AppHelper {
              public static int getSomething() { return 42; }
          }
          """
            .trimIndent(),
        )
        files.add(
          "src/main/java/com/example/helloworld/ComplexParamClass.kt",
          """
          package com.example.helloworld

          annotation class Composable

          class ComplexParamClass {
              companion object {
                  @JvmStatic
                  fun process(a: String, b: Int, c: Double, d: Long): Double {
                      var result = b + c + d
                      if (a == "complex") {
                          try {
                              for (i in 0..10) {
                                  result += i
                                  if (result > 50.0) {
                                      result /= 2.0
                                  }
                              }
                          } catch (e: Exception) {
                              result -= 1.0
                          }
                      }
                      return result
                  }
              }

              @Composable
              fun MyComplexScreen(onBackClick: Runnable) {
                  onBackClick.run()
              }
          }
          """
            .trimIndent(),
        )
        files.add(
          "src/main/java/com/example/helloworld/ConstructorTestClass.kt",
          """
          package com.example.helloworld

          class ConstructorTestClass {
              var id: Int = 0
              var name: String = ""

              constructor() {
                  this.id = 100
                  this.name = "default"
              }

              constructor(id: Int, name: String) {
                  this.id = id
                  this.name = name
              }
          }
          """
            .trimIndent(),
        )
        files.add(
          "src/androidTest/java/com/example/helloworld/HelloWorldTest.java",
          """
          package com.example.helloworld;
          import org.junit.Test;
          import static org.junit.Assert.assertEquals;
          import static org.junit.Assert.assertTrue;

          public class HelloWorldTest {
              @Test
              public void testCoverage() {
                  assertEquals(42, AppHelper.getSomething());
                  assertEquals(5, com.example.libmodule.LibClass.add(2, 3));
                  assertTrue(ComplexParamClass.process("complex", 5, 100.5, 0L) > 0.0);
                  ComplexParamClass complexInstance = new ComplexParamClass();
                  complexInstance.MyComplexScreen(() -> {
                      System.out.println("Composable backclicked");
                  });

                  ConstructorTestClass c1 = new ConstructorTestClass();
                  assertEquals(100, c1.getId());
                  assertEquals("default", c1.getName());

                  ConstructorTestClass c2 = new ConstructorTestClass(42, "custom");
                  assertEquals(42, c2.getId());
                  assertEquals("custom", c2.getName());
              }
          }
          """
            .trimIndent(),
        )
      }
    }

  @Test
  fun testOnTheFlyConnectedCheck() {
    // 1. Run E2E coverage report generation on the emulator
    rule.build.executor
      .with(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform)
      .with(BooleanOption.ENABLE_ON_THE_FLY_CODE_COVERAGE, true)
      .with(BooleanOption.REPORT_AGGREGATION_SUPPORT, true)
      .run(":app:createDebugCoverageReport")

    // 2. Locate and verify pulled on-device .pb binary artifacts
    val coverageSearchDir =
      if (runWithBuiltInPlatform) {
          rule.build.directory.resolve("app/build/intermediates/test_suite_code_coverage")
        } else {
          rule.build.directory.resolve("app/build/outputs/code_coverage")
        }
        .toFile()

    val hitsFile = coverageSearchDir.walkTopDown().find { it.name == "coverage_hits.pb" }
    val metadataFile = coverageSearchDir.walkTopDown().find { it.name == "coverage_metadata.pb" }

    assertThat(hitsFile).named("coverage_hits.pb in ${coverageSearchDir.absolutePath}").isNotNull()
    assertThat(metadataFile).named("coverage_metadata.pb in ${coverageSearchDir.absolutePath}").isNotNull()
    assertThat(hitsFile?.exists()).isTrue()
    assertThat(metadataFile?.exists()).isTrue()

    // 3. Verify generated XML coverage report contents and package-level isolation
    val reportXml = rule.build.directory.resolve("app/build/reports/coverage/androidTest/debug/connected/report.xml").toFile()
    assertThat(reportXml.exists()).isTrue()

    val content = reportXml.readText()
    assertThat(content).contains("<package name=\"com/example/helloworld\">")
    assertThat(content).contains("<class name=\"com/example/helloworld/AppHelper\"")
    assertThat(content).contains("<class name=\"com/example/helloworld/ComplexParamClass\"")
    assertThat(content).contains("<class name=\"com/example/helloworld/ConstructorTestClass\"")
    assertThat(content).doesNotContain("<package name=\"com/example/libmodule\">")
  }
}
