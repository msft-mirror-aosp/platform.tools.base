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

package com.android.build.gradle.integration.multiplatform.model

import com.android.build.gradle.integration.common.fixture.GradleTestProjectBuilder
import com.android.build.gradle.integration.common.fixture.model.BaseModelComparator
import com.android.build.gradle.integration.common.utils.TestFileUtils
import com.android.build.gradle.integration.multiplatform.model.fixture.KmpModelComparator
import com.android.build.gradle.options.BooleanOption
import com.android.utils.FileUtils
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class KotlinMultiplatformAndroidTargetSnapshotTest : BaseModelComparator {

  @get:Rule
  val project =
    GradleTestProjectBuilder()
      .fromTestProject("kotlinMultiplatform")
      .withIncludedBuilds("build-logic")
      .addGradleProperties("${BooleanOption.R8_PROGUARD_ANDROID_TXT_DISALLOWED.propertyName}=true")
      .create()

  @Before
  fun setUp() {
    // Static res and assets directories added through the variant API must be part of the
    // androidMain sourceSet model so that the IDE can register them (b/567129335).
    val kmpFirstLib = project.getSubproject("kmpFirstLib")
    FileUtils.join(kmpFirstLib.projectDir, "src", "androidMain", "staticRes", "values", "static_strings.xml").apply {
      parentFile.mkdirs()
      writeText("""<resources><string name="static_string">Static String</string></resources>""")
    }
    FileUtils.join(kmpFirstLib.projectDir, "src", "androidMain", "staticAssets", "static_asset.txt").apply {
      parentFile.mkdirs()
      writeText("static asset")
    }
    TestFileUtils.appendToFile(
      kmpFirstLib.ktsBuildFile,
      """
      androidComponents {
          onVariants { variant ->
              variant.sources.res?.addStaticSourceDirectory("src/androidMain/staticRes")
              variant.sources.assets?.addStaticSourceDirectory("src/androidMain/staticAssets")
          }
      }
      """
        .trimIndent(),
    )
  }

  @Test
  fun testModels() {
    KmpModelComparator(
        project = project,
        testClass = this,
        modelSnapshotTask = "dumpAndroidTarget",
        taskOutputsLocator = { projectPath ->
          FileUtils.join(project.getSubproject(projectPath).buildDir, "ide", "targets").listFiles()!!.toList()
        },
      )
      .fetchAndCompareModels(listOf(":kmpFirstLib", ":kmpSecondLib", ":build-logic:lib-foo"))
  }
}
