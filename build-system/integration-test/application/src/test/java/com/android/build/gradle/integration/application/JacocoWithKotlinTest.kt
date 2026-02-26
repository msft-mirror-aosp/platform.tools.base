/*
 * Copyright (C) 2017 The Android Open Source Project
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

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject
import com.android.utils.FileUtils
import java.io.File
import org.junit.Rule
import org.junit.Test

/** Check that Jacoco runs for a Kotlin-based project. */
class JacocoWithKotlinTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android {
          namespace = "com.example.helloworld"
          compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION

          buildTypes { named("debug") { it.enableAndroidTestCoverage = true } }

          sourceSets.named("main") { it.kotlin.directories += "src/main/kotlin" }

          files.add(
            "src/main/kotlin/com/example/helloworld/HelloWorld.kt",
            // language=kotlin
            """
            package com.example.helloworld

            import android.app.Activity
            import android.os.Bundle

            class HelloWorld : Activity() {
                override fun onCreate(savedInstanceState: Bundle?) {
                    super.onCreate(savedInstanceState)
                    setContentView(R.layout.main)
                }
            }
            """
              .trimIndent(),
          )

          files.add(
            "src/main/res/layout/main.xml",
            // language=xml
            """
            <?xml version="1.0" encoding="utf-8"?>
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:orientation="vertical"
                android:layout_width="fill_parent"
                android:layout_height="fill_parent">
            </LinearLayout>
            """
              .trimIndent(),
          )

          files.add(
            "src/main/res/values/strings.xml",
            // language=xml
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">HelloWorld</string>
            </resources>
            """
              .trimIndent(),
          )
        }
      }
    }

  @Test
  fun build() {
    val build = rule.build
    build.executor.with(BooleanOption.FORCE_JACOCO_OUT_OF_PROCESS, true).run(":app:assembleDebug")

    // check HelloWorld class is instrumented
    val appProject = build.androidApplication()
    appProject.assertApk(ApkSelector.DEBUG) {
      classes { classDefinition("com/example/helloworld/HelloWorld").methods().contains("\$jacocoInit") }
    }

    val kotlinModuleFile: File? =
      FileUtils.join(appProject.intermediatesDir.toFile(), "classes", "debug", "jacocoDebug", "dirs", "META-INF", "app.kotlin_module")
    PathSubject.assertThat(kotlinModuleFile).exists()
    PathSubject.assertThat(kotlinModuleFile).isFile()
  }
}
