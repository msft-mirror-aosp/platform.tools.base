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

import com.android.build.api.dsl.LibraryExtension
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Regression test for b/483731086 to ensure that AndroidLintAnalysisTask is relocatable even when native libraries are present. */
class AndroidLintAnalysisTaskNativeRelocatabilityTest {

  @get:Rule val buildCacheDir = TemporaryFolder()

  @get:Rule val rule1 = GradleRule.from(folderName = "project1", logicalName = "project") {}

  @get:Rule val rule2 = GradleRule.from(folderName = "project2", logicalName = "project") {}

  private fun setupNative(project: AndroidProjectDefinition<LibraryExtension>) {
    project.files.add(
      "src/main/cpp/hello.cpp",
      """
      #include <jni.h>
      extern "C" JNIEXPORT jstring JNICALL
      Java_com_example_lib_Hello_stringFromJNI(JNIEnv* env, jobject /* this */) {
          return env->NewStringUTF("Hello from C++");
      }
      """
        .trimIndent(),
    )
    project.files.add(
      "src/main/cpp/CMakeLists.txt",
      """
      cmake_minimum_required(VERSION 3.4.1)
      add_library(hello SHARED hello.cpp)
      find_library(log-lib log)
      target_link_libraries(hello ${'$'}{log-lib})
      """
        .trimIndent(),
    )
    val cmakeFile = project.projectDotFile("src/main/cpp/CMakeLists.txt")
    project.android {
      namespace = "com.example.lib"
      defaultConfig {
        minSdk = 24
        externalNativeBuild { cmake { abiFilters.add("armeabi-v7a") } }
      }
      externalNativeBuild {
        cmake {
          path = cmakeFile
          version = "3.22.1"
        }
      }
    }
  }

  @Test
  fun testRelocatability() {
    val projectDef: GradleBuildDefinition.() -> Unit = {
      rootProject {
        group = "com.example"
        version = "1.0"
      }
      androidLibrary(":lib") {
        setupNative(this)
        group = "com.example"
        version = "1.0"
      }
      settings { enableLocalCache(buildCacheDir.root.toPath()) }
    }

    val build1 = rule1.build(projectDef)
    // First run seeds the cache
    build1.executor.withArgument("--build-cache").run(":lib:lintDebug", ":lib:lintAnalyzeDebugAndroidTest", ":lib:lintAnalyzeDebugUnitTest")

    val build2 = rule2.build(projectDef)
    // Second run in a different location
    val result =
      build2.executor
        .withArgument("--build-cache")
        .withArgument("-Dorg.gradle.caching.debug=true")
        .run(":lib:lintDebug", ":lib:lintAnalyzeDebugAndroidTest", ":lib:lintAnalyzeDebugUnitTest")

    try {
      result.assertTask(":lib:lintAnalyzeDebug").wasFromCache()
      result.assertTask(":lib:lintAnalyzeDebugAndroidTest").wasFromCache()
      result.assertTask(":lib:lintAnalyzeDebugUnitTest").wasFromCache()
    } catch (e: AssertionError) {
      @Suppress("DEPRECATION")
      throw RuntimeException("Task was NOT from cache on relocation! Full stdout:\n" + result.stdoutAsTextForDebug, e)
    }
  }
}
