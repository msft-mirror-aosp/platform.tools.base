/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.build.gradle.integration.application

import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.GradleBuild
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class JacocoLibraryProjectTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidLibrary(":lib") {
        android {
          namespace = "com.example.helloworld"

          compileSdk { version = release(GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION) }

          defaultConfig { minSdk { version = release(24) } }

          dependencies { testImplementation("junit:junit:4.13.2") }

          files {
            add(
              "src/main/java/com/example/helloworld/HelloWorld.java",
              // language=java
              """
              package com.example.helloworld;

              import android.app.Activity;
              import android.os.Bundle;

              public class HelloWorld extends Activity {
                  @Override
                  public void onCreate(Bundle savedInstanceState) {
                      super.onCreate(savedInstanceState);
                  }
              }
              """
                .trimIndent(),
            )
            add(
              "src/test/java/example/MyTest.java",
              // language=java
              """
              package example;
              import org.junit.Test;

              public class MyTest {
                  @Test
                  public void foo() {
                      System.out.println(com.example.helloworld.HelloWorld.class);
                  }
              }
              """
                .trimIndent(),
            )
          }
        }
      }
    }

  class EnableCodeCoverageCallback : LibraryComponentCallback {
    override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
      androidComponents.beforeVariants(androidComponents.selector().withBuildType("debug")) {
        it.hostTests[com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE]?.enableCodeCoverage = true
      }
    }
  }

  @Test
  fun testUnitTestsWithJacocoPlugin() {
    val build = rule.build { androidLibrary(":lib") { android { buildTypes { named("debug") { it.enableUnitTestCoverage = true } } } } }
    verifyJacocoExecution(build)
  }

  @Test
  fun testUnitTestsWithJacocoThroughVariantApi() {
    val build = rule.build { androidLibrary(":lib") { pluginCallbacks += EnableCodeCoverageCallback::class.java } }
    verifyJacocoExecution(build)
  }

  private fun verifyJacocoExecution(build: GradleBuild) {
    val result = build.executor.run(":lib:createDebugUnitTestCoverageReport")

    result.assertOutputDoesNotContain("Cannot process instrumented class")

    val libProject = build.androidLibrary(":lib")
    val buildDir = libProject.buildDir.toFile()
    val coverageData = buildDir.walk().filter { it.extension == "exec" }.toList()
    assertThat(coverageData).hasSize(1)

    val coveragePackageFolder = FileUtils.join(buildDir, "reports", "coverage", "test", "debug", "com.example.helloworld")

    assertThat(coveragePackageFolder.exists()).isTrue()

    assertThat(coveragePackageFolder.listFiles()!!.map { it.name }).containsAtLeast("HelloWorld.html", "HelloWorld.java.html")
  }
}
