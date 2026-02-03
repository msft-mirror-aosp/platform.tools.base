/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.integration.annotationprocessor

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.common.runner.FilterableParameterized
import com.android.build.gradle.internal.scope.InternalArtifactType.ANNOTATION_PROCESSOR_LIST
import com.android.build.gradle.internal.scope.getOutputDir
import com.android.build.gradle.tasks.ANNOTATION_PROCESSOR_LIST_FILE_NAME
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(FilterableParameterized::class)
class JavaPreCompileTest(private val useKapt: Boolean) {

  companion object {
    @Parameterized.Parameters(name = "useKapt_{0}") @JvmStatic fun parameters() = listOf(true, false)
  }

  @get:Rule
  val rule =
    GradleRule.from {
      if (useKapt) {
        androidKotlinApplication {
          applyPlugin(PluginType.ANDROID_BUILT_IN_KAPT)
          dependencies {
            compileOnly("com.google.auto.service:auto-service:1.0-rc2")
            add("kapt", "com.google.auto.service:auto-service:1.0-rc2")
          }
        }
      } else {
        androidJavaApplication {
          dependencies {
            compileOnly("com.google.auto.service:auto-service:1.0-rc2")
            add("annotationProcessor", "com.google.auto.service:auto-service:1.0-rc2")
          }
        }
      }
    }

  @Test
  fun `check output`() {
    rule.build.executor.run(":app:javaPreCompileDebug")

    val app = rule.build.androidApplication(":app")
    val annotationProcessorList =
      ANNOTATION_PROCESSOR_LIST.getOutputDir(app.buildDir.toFile())
        .resolve("debug/javaPreCompileDebug/$ANNOTATION_PROCESSOR_LIST_FILE_NAME")
        .readText()
    assertThat(annotationProcessorList)
      .isEqualTo("{\"auto-service-1.0-rc2.jar (com.google.auto.service:auto-service:1.0-rc2)\":\"NON_INCREMENTAL_AP\"}")
  }
}
