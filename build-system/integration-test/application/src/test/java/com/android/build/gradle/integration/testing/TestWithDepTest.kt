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

import com.android.build.gradle.integration.common.fixture.SUPPORT_LIB_MIN_SDK
import com.android.build.gradle.integration.common.fixture.model.ModelComparator
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import org.junit.Rule
import org.junit.Test

class TestWithDepTest : ModelComparator() {

  @get:Rule
  val project =
    GradleRule.configure().from {
      androidApplication(":app", createMinimumProject = false) {
        android {
          namespace = "com.android.tests.basic"
          compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          defaultConfig {
            minSdk = SUPPORT_LIB_MIN_SDK
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
        }
        dependencies {
          androidTestImplementation("com.google.guava:guava:19.0")
          androidTestImplementation("junit:junit:4.12")
          androidTestImplementation("androidx.test:runner:1.4.0-alpha06")
          androidTestImplementation("androidx.test:rules:1.4.0-alpha06")
        }
        files.add(
          "src/main/AndroidManifest.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application />
          </manifest>
          """
            .trimIndent(),
        )
        files.add(
          "src/main/java/com/android/tests/basic/Main.java",
          """
          package com.android.tests.basic;
          import android.app.Activity;
          public class Main extends Activity {}
          """
            .trimIndent(),
        )
        files.add(
          "src/androidTest/java/com/android/tests/basic/MainTest.java",
          """
          package com.android.tests.basic;
          import org.junit.Test;
          import com.google.common.collect.ImmutableList;
          public class MainTest {
              @Test
              public void testGuava() {
                  ImmutableList.of(1);
              }
          }
          """
            .trimIndent(),
        )
      }
    }

  @Test
  fun `test VariantDependencies model`() {
    val result = project.build.modelBuilder.fetchModels(variantName = "debug")

    with(result).compareVariantDependencies(projectAction = { getProject(":app") }, goldenFile = "VariantDependencies")
  }
}
