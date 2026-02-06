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

package com.android.build.gradle.integration.testing.suites

import com.android.build.api.dsl.AgpTestSuite
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test

class HostJarTestSuiteJavaCompilationTest {
  @get:Rule
  val rule =
    GradleRule.configure().from {
      gradleProperties { add(BooleanOption.TEST_SUITE_SUPPORT, true) }
      androidApplication {
        android {
          namespace = "com.example.test"
          testOptions.suites.create("first", AgpTestSuite::class.java) {
            it.useJunitEngine.apply { includeEngines.add("[engine:toy-junit-engine-for-tests]") }
            it.hostJar {}
            it.targetVariants.add("debug")
            it.targets.apply { create("t1") {} }
          }
        }
        files {
          add(
            "src/main/java/some/random/TestTarget.java",
            """
            package some.random;

            public class TestTarget {
                String someFunctionToBeTested(String value) {
                    return "Tested " + value;
                }
            }
            """
              .trimIndent(),
          )
          add(
            "src/first/java/some/random/Test1.java",
            """
            package some.random;

            public class Test1 {
                public void testSomething() {
                    TestTarget testTarget = new TestTarget();
                    testTarget.someFunctionToBeTested("some!");
                }
            }
            """
              .trimIndent(),
          )
        }
        dependencies { implementation("com.google.truth:truth:0.44") }
      }
    }

  @Test
  fun testCompilation() {
    val buildResult = rule.build.executor.run("app:compileFirstDebugJavaWithJavac")
    Truth.assertThat(buildResult.failedTasks).isEmpty()
  }
}
