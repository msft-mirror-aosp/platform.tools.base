/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.testutils.TestUtils
import com.android.testutils.ignore.IgnoreTestRule
import com.android.testutils.ignore.IgnoreWithCondition
import com.android.testutils.ignore.OnWindows
import org.junit.Rule
import org.junit.Test

// Regression test for b/363031540
class BuildConfigBytecodeCompilationTest {

  @get:Rule
  val ignoreTests = IgnoreTestRule()

  @get:Rule
  val project =
    GradleTestProject.builder()
      .fromTestProject("buildConfigBytecode")
      .addGradleProperties("org.gradle.java.installations.auto-detect=false")
      .addGradleProperties("org.gradle.java.installations.paths=${TestUtils.getJava21Jdk().toString().replace("\\", "/")}")
      .create()

  @IgnoreWithCondition(
    reason = "b/530211050",
    condition = OnWindows::class,
  )
  @Test
  fun testBuildConfigCompilation() {
    project.execute(
      ":app:compileDebugUnitTestJavaWithJavac",
      ":app:compileDebugUnitTestKotlin",
      ":app:compileDebugAndroidTestJavaWithJavac",
      ":app:compileDebugAndroidTestKotlin",
    )
  }
}
