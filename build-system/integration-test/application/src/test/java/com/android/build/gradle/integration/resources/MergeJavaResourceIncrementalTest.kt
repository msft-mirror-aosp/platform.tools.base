/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.build.gradle.integration.resources

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.junit.Rule
import org.junit.Test

/** Tests related to the incremental behavior of [MergeJavaResourceTask]. */
class MergeJavaResourceIncrementalTest {
  @get:Rule
  val rule =
    GradleRule.from {
      androidLibrary(":foo:lib") {
        applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
        files.add("src/main/resources/res1.txt", "res 1 from foo")
      }
      androidLibrary(":bar:lib") {
        applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
        files.add("src/main/resources/res1.txt", "res 1 from bar")
      }
      androidApplication {
        applyPlugin(PluginType.ANDROID_BUILT_IN_KOTLIN)
        android { packaging { resources { pickFirsts += "res1.txt" } } }
        kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) } }
        dependencies {
          implementation(project(":foo:lib"))
          implementation(project(":bar:lib"))
        }
      }
    }

  /**
   * Checks that the java resource merger can handle changes to multiple files with the same normalized path (and therefore result in
   * conflicting changes in the APK resources).
   *
   * For now this is implemented by falling back to a non-incremental run of the java resource merger.
   *
   * Regression test for b/284003132: when adding and removing kotlin files to projects which use the same kotlin module name, this can lead
   * to conflicting changes of the java resource META-INF/<library_name>.kotlin_module
   */
  @Test
  fun testIncrementalChanges() {
    val build = rule.build
    val app = build.androidApplication()
    val fooLib = build.androidLibrary(":foo:lib")
    val barLib = build.androidLibrary(":bar:lib")

    build.executor.run(":app:assembleDebug")
    app.assertApk(ApkSelector.DEBUG) { javaResources().resourceAsText("res1.txt").isEqualTo("res 1 from foo") }

    fooLib.files.remove("src/main/resources/res1.txt")
    barLib.files.update("src/main/resources/res1.txt").append(" edited")

    build.executor.run(":app:assembleDebug")
    app.assertApk(ApkSelector.DEBUG) { javaResources().resourceAsText("res1.txt").isEqualTo("res 1 from bar edited") }

    fooLib.files.add("src/main/resources/res1.txt", "res 1 from foo added back")
    barLib.files.update("src/main/resources/res1.txt").append(" twice")

    build.executor.run(":app:assembleDebug")
    app.assertApk(ApkSelector.DEBUG) { javaResources().resourceAsText("res1.txt").isEqualTo("res 1 from foo added back") }
  }
}
