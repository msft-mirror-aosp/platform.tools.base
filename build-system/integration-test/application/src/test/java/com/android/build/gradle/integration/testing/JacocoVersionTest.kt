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

package com.android.build.gradle.integration.testing

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.build.gradle.internal.coverage.JacocoOptions
import com.android.build.gradle.options.StringOption
import com.android.utils.FileUtils
import com.google.common.truth.Truth
import java.io.File
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.testing.jacoco.plugins.JacocoPlugin
import org.gradle.testing.jacoco.tasks.JacocoReport
import org.junit.Rule
import org.junit.Test

class JacocoVersionTest {

  companion object {
    const val EXPECTED_JACOCO_VERSION_1 = "0.8.7"
    const val EXPECTED_JACOCO_VERSION_2 = "0.8.12"
    const val EXPECTED_JACOCO_VERSION_DEFAULT = JacocoOptions.DEFAULT_VERSION
  }

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication(":app") {
        android {
          namespace = "com.example.app"
          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }

          installation { timeOutInMs = 30000 }

          defaultConfig {
            minSdk { version = release(24) }
            targetSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }
          }

          buildTypes { named("debug") { it.enableUnitTestCoverage = true } }

          compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
          }
        }

        dependencies {
          testImplementation("junit:junit:4.13.2")
          testImplementation("org.mockito:mockito-core:5.12.0")
          testImplementation("org.jdeferred:jdeferred-android-aar:1.2.3")
          testImplementation("commons-logging:commons-logging:1.1.1")
        }

        files {
          add(
            "src/main/java/com/android/tests/Foo.java",
            // language=kotlin
            """
            package com.android.tests;

            public class Foo {
              public String foo() {
                return "production code";
              }
            }
            """
              .trimIndent(),
          )
          add(
            "src/main/java/com/android/tests/someKotlinCode.kt",
            // language=kotlin
            """
            package com.android.tests

            data class KotlinDataClass(val name: String = "kotlin data class")
            """
              .trimIndent(),
          )
          add(
            "src/test/java/com/android/tests/TestInKotlin.kt",
            // language=kotlin
            """
            package com.android.tests

            import org.junit.Test
            import org.junit.Assert.*

            class TestInKotlin {
                @Test
                fun passesInKotlin() {
                    // Use Java classes:
                    assertEquals("production code", Foo().foo())

                    // Use Kotlin classes:
                    assertEquals("kotlin data class", KotlinDataClass().name)
                }
            }
            """
              .trimIndent(),
          )
        }
        pluginCallbacks += JacocoReportTaskCallback::class.java
      }
    }

  open class JacocoReportTaskCallback : GenericCallback {
    override fun handleProject(project: Project) {
      project.tasks.register("jacocoTestReport", JacocoReport::class.java) { task ->
        task.dependsOn("testDebugUnitTest", "createDebugUnitTestCoverageReport")
        task.executionData.setFrom(
          project.files("${project.buildDir}/outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec")
        )
      }
    }
  }

  open class JacocoPluginExtensionCallback : GenericCallback {
    override fun handleProject(project: Project) {
      project.plugins.apply(JacocoPlugin::class.java)
      project.extensions.configure(org.gradle.testing.jacoco.plugins.JacocoPluginExtension::class.java) { it ->
        it.toolVersion = EXPECTED_JACOCO_VERSION_1
      }
    }
  }

  private fun verifyJacocoVersion(indexHtml: File, expectedJacocoVersion: String) {
    val indexHtmlString = indexHtml.readLines().joinToString("\n")
    Truth.assertThat(indexHtmlString).contains("JaCoCo</a> $expectedJacocoVersion")
  }

  @Test
  fun testDefaultJacocoVersionForUnitTest() {
    val build = rule.build
    build.executor.run(":app:jacocoTestReport")

    val appBuildDir = build.androidApplication(":app").buildDir.toFile()

    val generatedJacocoReport = FileUtils.join(appBuildDir, "reports", "jacoco", "jacocoTestReport", "html", "index.html")

    val generatedCoverageReport = FileUtils.join(appBuildDir, "reports", "coverage", "test", "debug", "index.html")

    verifyJacocoVersion(generatedJacocoReport, EXPECTED_JACOCO_VERSION_DEFAULT)
    verifyJacocoVersion(generatedCoverageReport, EXPECTED_JACOCO_VERSION_DEFAULT)
  }

  @Test
  fun testPluginExtensionJacocoVersionForUnitTest() {
    val build = rule.build { androidApplication { pluginCallbacks += JacocoPluginExtensionCallback::class.java } }
    build.executor.run(":app:jacocoTestReport")

    val appBuildDir = build.androidApplication(":app").buildDir.toFile()

    val generatedJacocoReport = FileUtils.join(appBuildDir, "reports", "jacoco", "jacocoTestReport", "html", "index.html")

    val generatedCoverageReport = FileUtils.join(appBuildDir, "reports", "coverage", "test", "debug", "index.html")

    verifyJacocoVersion(generatedJacocoReport, EXPECTED_JACOCO_VERSION_1)
    verifyJacocoVersion(generatedCoverageReport, EXPECTED_JACOCO_VERSION_1)
  }

  @Test
  fun testAndroidDslJacocoVersionForUnitTest() {
    val build =
      rule.build {
        androidApplication {
          android { testCoverage.jacocoVersion = EXPECTED_JACOCO_VERSION_2 }
          pluginCallbacks += JacocoPluginExtensionCallback::class.java
        }
      }
    build.executor.run(":app:jacocoTestReport")

    val appBuildDir = build.androidApplication(":app").buildDir.toFile()

    val generatedJacocoReport = FileUtils.join(appBuildDir, "reports", "jacoco", "jacocoTestReport", "html", "index.html")

    val generatedCoverageReport = FileUtils.join(appBuildDir, "reports", "coverage", "test", "debug", "index.html")

    verifyJacocoVersion(generatedJacocoReport, EXPECTED_JACOCO_VERSION_2)
    verifyJacocoVersion(generatedCoverageReport, EXPECTED_JACOCO_VERSION_2)
  }

  @Test
  fun testGradlePropertyJacocoVersionForUnitTest() {
    val build =
      rule.build {
        androidApplication { android { testCoverage.jacocoVersion = EXPECTED_JACOCO_VERSION_1 } }
        gradleProperties { add(StringOption.JACOCO_TOOL_VERSION, EXPECTED_JACOCO_VERSION_2) }
      }
    build.executor.run(":app:jacocoTestReport")

    val appBuildDir = build.androidApplication(":app").buildDir.toFile()

    val generatedJacocoReport = FileUtils.join(appBuildDir, "reports", "jacoco", "jacocoTestReport", "html", "index.html")

    val generatedCoverageReport = FileUtils.join(appBuildDir, "reports", "coverage", "test", "debug", "index.html")

    verifyJacocoVersion(generatedJacocoReport, EXPECTED_JACOCO_VERSION_2)
    verifyJacocoVersion(generatedCoverageReport, EXPECTED_JACOCO_VERSION_2)
  }
}
