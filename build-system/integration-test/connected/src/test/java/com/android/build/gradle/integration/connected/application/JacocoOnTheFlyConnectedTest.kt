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

                  @JvmStatic
                  fun massiveTryCatchMethod(): Int {
                      var result = 0
                      try {
                          val a0 = 0; val a1 = 1; val a2 = 2; val a3 = 3
                          val a4 = 4; val a5 = 5; val a6 = 6; val a7 = 7
                          val a8 = 8; val a9 = 9; val a10 = 10; val a11 = 11
                          val a12 = 12; val a13 = 13; val a14 = 14; val a15 = 15
                          result = a0 + a1 + a2 + a3 + a4 + a5 + a6 + a7 + a8 + a9 + a10 + a11 + a12 + a13 + a14 + a15
                      } catch (e: Exception) {
                          result = -1
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

          class ComplexConstructorClass {
              var result: Int = 0
              var tag: String = ""

              constructor(a: Int, b: String) {
                  try {
                      val len = b.length
                      when (a) {
                          in 1..10 -> {
                              if (len > 5) {
                                  this.result = len * 2
                              } else {
                                  this.result = len
                              }
                          }
                          20, 30 -> {
                              this.result = a + len
                          }
                          else -> {
                              var sum = 0
                              for (i in 1..a) {
                                  sum += i
                              }
                              this.result = sum
                          }
                      }
                      this.tag = "InitBranch"
                  } catch (e: Exception) {
                      this.result = -1
                      this.tag = "Error"
                  }
              }

              constructor(x: Any) : this(
                  try {
                      x as Int
                  } catch (e: Exception) {
                      99
                  },
                  x.toString()
              ) {
                  this.tag = "DelegatedDelegate"
              }
          }
          """
            .trimIndent(),
        )
        files.add(
          "src/main/java/com/example/helloworld/ComplexBranchingKotlin.kt",
          """
          package com.example.helloworld

          import java.io.IOException

          class ComplexBranchingKotlin {
              fun whenBlock(key: Int): String {
                  return when (key) {
                      1 -> "one"
                      2 -> "two"
                      100 -> "hundred"
                      else -> "other"
                  }
              }

              fun complexLadder(score: Int, extraCredit: Boolean): String {
                  return if (score >= 90) {
                      if (extraCredit) "A+" else "A"
                  } else if (score >= 80) {
                      "B"
                  } else {
                      "F"
                  }
              }

              fun loopControl(limit: Int): Int {
                  var sum = 0
                  for (i in 1..limit) {
                      if (i % 2 == 0) {
                          continue
                      }
                      if (sum > 20) {
                          break
                      }
                      sum += i
                  }
                  return sum
              }

              fun tryCatchFinally(input: String): Int {
                  var result = 0
                  try {
                      if (input == "error") {
                          throw IOException("triggered")
                      }
                      result = input.length
                  } catch (e: IOException) {
                      result = -1
                  } finally {
                      result += 10
                  }
                  return result
              }
          }
          """
            .trimIndent(),
        )
        files.add(
          "src/main/java/com/example/helloworld/ComplexBranchingJava.java",
          """
          package com.example.helloworld;

          import java.io.IOException;

          public class ComplexBranchingJava {
              public String switchStatement(int key) {
                  switch (key) {
                      case 1: return "one";
                      case 2: return "two";
                      case 100: return "hundred";
                      default: return "other";
                  }
              }

              public String fallThroughSwitch(int key) {
                  String result = "";
                  switch (key) {
                      case 1: result += "one";
                      case 2: result += "two";
                      default: result += "default";
                  }
                  return result;
              }

              public String complexLadder(int score, boolean extraCredit) {
                  if (score >= 90) {
                      return extraCredit ? "A+" : "A";
                  } else if (score >= 80) {
                      return "B";
                  } else {
                      return "F";
                  }
              }

              public int loopControl(int limit) {
                  int sum = 0;
                  for (int i = 1; i <= limit; i++) {
                      if (i % 2 == 0) {
                          continue;
                      }
                      if (sum > 20) {
                          break;
                      }
                      sum += i;
                  }
                  return sum;
              }

              public int tryCatchFinally(String input) {
                  int result = 0;
                  try {
                      if ("error".equals(input)) {
                          throw new IOException("triggered");
                      }
                      result = input.length();
                  } catch (IOException e) {
                      result = -1;
                  } finally {
                      result += 10;
                  }
                  return result;
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

                  assertEquals(120, ComplexParamClass.massiveTryCatchMethod());

                  ComplexConstructorClass c3 = new ComplexConstructorClass(20, "test");
                  assertEquals(24, c3.getResult());
                  assertEquals("InitBranch", c3.getTag());

                  ComplexConstructorClass c4 = new ComplexConstructorClass(5, "testing");
                  assertEquals(14, c4.getResult());
                  assertEquals("InitBranch", c4.getTag());

                  ComplexConstructorClass c5 = new ComplexConstructorClass(15);
                  assertEquals(120, c5.getResult());
                  assertEquals("DelegatedDelegate", c5.getTag());

                  ComplexConstructorClass c6 = new ComplexConstructorClass("not_an_int");
                  assertEquals(4950, c6.getResult());
                  assertEquals("DelegatedDelegate", c6.getTag());

                  ComplexBranchingKotlin k = new ComplexBranchingKotlin();
                  ComplexBranchingJava j = new ComplexBranchingJava();

                  // 1. Switch & When
                  assertEquals("one", k.whenBlock(1));
                  assertEquals("two", k.whenBlock(2));
                  assertEquals("one", j.switchStatement(1));
                  assertEquals("two", j.switchStatement(2));

                  // 1a. Java Fall-Through Switch (runs all 3 case blocks due to no break)
                  assertEquals("onetwodefault", j.fallThroughSwitch(1));

                  // 2. Complex if-else-if nested ladder
                  assertEquals("F", k.complexLadder(50, false));
                  assertEquals("B", k.complexLadder(85, false));
                  assertEquals("F", j.complexLadder(50, false));
                  assertEquals("B", j.complexLadder(85, false));

                  // 3. Loops with continue & break
                  assertTrue(k.loopControl(5) > 0);
                  assertTrue(j.loopControl(5) > 0);

                  // 4. Try-catch-finally exceptional paths
                  assertEquals(15, k.tryCatchFinally("hello"));
                  assertEquals(15, j.tryCatchFinally("hello"));
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
    assertThat(content).contains("<class name=\"com/example/helloworld/ComplexConstructorClass\"")
    assertThat(content).contains("<class name=\"com/example/helloworld/ComplexBranchingKotlin\"")
    assertThat(content).contains("<class name=\"com/example/helloworld/ComplexBranchingJava\"")

    // Verify presence of our stress-test method names
    assertThat(content).contains("<method name=\"whenBlock\"")
    assertThat(content).contains("<method name=\"massiveTryCatchMethod\"")
    assertThat(content).contains("<method name=\"switchStatement\"")
    assertThat(content).contains("<method name=\"fallThroughSwitch\"")
    assertThat(content).contains("<method name=\"complexLadder\"")
    assertThat(content).contains("<method name=\"loopControl\"")
    assertThat(content).contains("<method name=\"tryCatchFinally\"")

    // Assert exact branch ratios are solved and recorded in the XML report:
    // a. loopControl:
    //    - Java: loop, continue, break. 5 covered, 1 missed (break true branch missed) -> missed="1" covered="5"
    //    - Kotlin: extra compiler checks. 7 covered, 1 missed -> missed="1" covered="7"
    assertThat(content).contains("<method name=\"loopControl\"")
    assertThat(content).contains("<counter type=\"BRANCH\" missed=\"1\" covered=\"5\"/>")
    assertThat(content).contains("<counter type=\"BRANCH\" missed=\"1\" covered=\"7\"/>")

    // b. tryCatchFinally: normal try + finally hit, catch missed. 1 covered, 1 missed -> missed="1" covered="1"
    assertThat(content).contains("<method name=\"tryCatchFinally\"")
    assertThat(content).contains("<counter type=\"BRANCH\" missed=\"1\" covered=\"1\"/>")

    // c. complexLadder: nested ladder paths. 3 covered, 3 missed -> missed="3" covered="3"
    assertThat(content).contains("<method name=\"complexLadder\"")
    assertThat(content).contains("<counter type=\"BRANCH\" missed=\"3\" covered=\"3\"/>")

    // d. fallThroughSwitch: entry on case 1 covered, case 2 and default missed (but blocks hit by fall-through). 2 covered, 1 missed ->
    // missed="1" covered="2"
    assertThat(content).contains("<method name=\"fallThroughSwitch\"")
    assertThat(content).contains("<counter type=\"BRANCH\" missed=\"1\" covered=\"2\"/>")

    // e. ComplexConstructorClass:
    //    - Primary constructor (ILjava/lang/String;)V: 13 covered, 3 missed branches
    //    - Secondary constructor (Ljava/lang/Object;)V: 13 covered, 0 missed instructions (completely covered)
    assertThat(content).contains("<class name=\"com/example/helloworld/ComplexConstructorClass\"")
    assertThat(content).contains("<method name=\"&lt;init&gt;\" desc=\"(ILjava/lang/String;)V\"")
    assertThat(content).contains("<counter type=\"BRANCH\" missed=\"3\" covered=\"13\"/>")
    assertThat(content).contains("<method name=\"&lt;init&gt;\" desc=\"(Ljava/lang/Object;)V\"")
    assertThat(content).contains("<counter type=\"INSTRUCTION\" missed=\"0\" covered=\"13\"/>")

    assertThat(content).doesNotContain("<package name=\"com/example/libmodule\">")
  }
}
