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

package com.android.build.gradle.integration.multiplatform.v2

import com.android.build.gradle.integration.common.fixture.DESUGAR_DEPENDENCY_VERSION
import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.truth.ScannerSubject
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.utils.FileUtils
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformAndroidDexingTest {
  @get:Rule val project = GradleTestProjectBuilder().fromTestProject("kotlinMultiplatform").create()

  @Before
  fun setUp() {
    TestFileUtils.appendToFile(
      project.getSubproject("kmpFirstLib").ktsBuildFile,
      """
                kotlin {
                    androidLibrary {
                        compilerOptions {
                            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
                        }
                        enableCoreLibraryDesugaring = true
                    }
                }
                dependencies {
                    add("coreLibraryDesugaring", "com.android.tools:desugar_jdk_libs:$DESUGAR_DEPENDENCY_VERSION")
                }
            """
        .trimIndent(),
    )

    TestFileUtils.addMethod(
      FileUtils.join(
        project.getSubproject("kmpFirstLib").projectDir,
        "src",
        "androidMain",
        "kotlin",
        "com",
        "example",
        "kmpfirstlib",
        "KmpAndroidActivity.kt",
      ),
      """
      fun getText(): String {
          val collection = java.util.Arrays.asList("first", "second", "third")
          val streamOfCollection = collection.stream()
          return streamOfCollection.findFirst().get()
      }
      """
        .trimIndent(),
    )
  }

  @Test
  fun testDesugaringFailureWhenMultidexFalse() {
    TestFileUtils.appendToFile(
      project.getSubproject("kmpFirstLib").ktsBuildFile,
      """
      kotlin.androidLibrary.compilations.withType(
          com.android.build.api.dsl.KotlinMultiplatformAndroidDeviceTestCompilation::class.java
      ) {
          multidex.enable = false
          multidex.mainDexKeepRules.files.add (
              File(project.projectDir, "dex-rules.pro")
          )
      }
      """
        .trimIndent(),
    )

    val result = project.executor().expectFailure().run(":kmpFirstLib:assembleDeviceTest")

    ScannerSubject.assertThat(result.stderr).contains("In order to use core library desugaring, please enable multidex.")
  }

  @Test
  fun testDesugaringForInstrumentedTestApk() {
    project
      .executor()
      .withFailOnWarning(false) // b/455891987
      .run(":kmpFirstLib:assembleDeviceTest")

    project.getSubproject("kmpFirstLib").assertApk(ApkSelector.NO_BUILD_TYPE.forTestSuite("androidTest")) {
      classes()
        .classDefinition("com/example/kmpfirstlib/KmpAndroidActivity")
        .invocationListForMethod("getText")
        .contains("Lj$/util/stream/Stream;->findFirst()Lj$/util/Optional;")
    }
  }

  @Test
  fun testLegacyMultiDexWithKeepRules() {
    TestFileUtils.appendToFile(
      project.getSubproject("kmpFirstLib").ktsBuildFile,
      """
      kotlin.androidLibrary.compilations.withType(
          com.android.build.api.dsl.KotlinMultiplatformAndroidDeviceTestCompilation::class.java
      ) {
          multidex.enable = true
          multidex.mainDexKeepRules.files.add (
              File(project.projectDir, "dex-rules.pro")
          )
      }
      """
        .trimIndent(),
    )
    TestFileUtils.appendToFile(
      project.getSubproject("androidLib").ktsBuildFile,
      """
      android.defaultConfig.minSdk = 20
      """
        .trimIndent(),
    )

    TestFileUtils.searchAndReplace(project.getSubproject("kmpSecondLib").ktsBuildFile, "minSdk = 22", "minSdk = 20")
    TestFileUtils.searchAndReplace(
      project.getSubproject("kmpFirstLib").ktsBuildFile,
      "minSdk = 22",
      """
      minSdk = 20
      """
        .trimIndent(),
    )

    FileUtils.writeToFile(
      FileUtils.join(project.getSubproject("kmpFirstLib").projectDir, "dex-rules.pro"),
      """
      -keep class com.example.kmpfirstlib.KmpAndroidActivity { *; }
      """
        .trimIndent(),
    )

    project
      .executor()
      .withFailOnWarning(false) // b/455891987
      .run(":kmpFirstLib:assembleDeviceTest")

    project.getSubproject("kmpFirstLib").assertApk(ApkSelector.NO_BUILD_TYPE.forTestSuite("androidTest")) {
      classes()
        .classDefinition("com/example/kmpfirstlib/KmpAndroidActivity")
        .invocationListForMethod("getText")
        .contains("Lj$/util/stream/Stream;->findFirst()Lj$/util/Optional;")

      mainDex().contains("com/example/kmpfirstlib/KmpAndroidActivity")
      // make sure KmpAndroidActivity is not in the secondary dex files
      secondaryDexes()
        .subPackage("com/example/kmpfirstlib")
        .containsExactly(
          "KmpAndroidFirstLibClass",
          "KmpAndroidFirstLibJavaClass",
          "KmpCommonFirstLibClass",
          "test/KmpAndroidFirstLibActivityTest",
          "test/KmpAndroidFirstLibActivityTest$\$ExternalSyntheticLambda0",
          "test/R$",
          "R\$drawable",
          "R\$string",
          "R",
        )
    }
  }
}
