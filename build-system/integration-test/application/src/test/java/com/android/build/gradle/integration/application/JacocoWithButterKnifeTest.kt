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

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.GenericCallback
import com.android.testutils.truth.PathSubject.assertThat
import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.compile.JavaCompile
import org.junit.Rule
import org.junit.Test

/**
 * Check Jacoco doesn't get broken when non-class files (such as .java files from an annotation processor) are present in the compiler
 * output folder. This test doesn't actually use an annotation processor (butterknife originally exposed this problem) but instead registers
 * an interceding task.
 */
class JacocoWithButterKnifeTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication {
      android {
        namespace = "com.test.jacoco.annotation"
        buildTypes {
          named("debug") {
            it.enableAndroidTestCoverage = true
          }
        }
      }
      files {
        add(
          "src/main/java/com/test/jacoco/annotation/BindActivity.java",
          // language=java
          """
          package com.test.jacoco.annotation;

          import android.app.Activity;
          import android.os.Bundle;

          public class BindActivity extends Activity {
              @Override
              protected void onCreate(Bundle savedInstanceState) {
                  super.onCreate(savedInstanceState);
              }
          }
          """
            .trimIndent(),
        )
      }
      pluginCallbacks += AddNonClassFileCallback::class.java
    }
  }

  open class AddNonClassFileCallback : GenericCallback {
    override fun handleProject(project: Project) {
      val addNonClassFileTask =
        project.tasks.register("addNonClassFileDebug", AddNonClassFileTask::class.java) { task ->
          val javacTask = project.tasks.named("compileDebugJavaWithJavac", JavaCompile::class.java)
          task.nonClassFile.set(
            javacTask.flatMap {
              it.destinationDirectory.file("com/test/jacoco/annotation/BindActivity\$\$ViewBinder.java")
            }
          )
        }
      project.tasks.configureEach { task ->
        if (task.name == "jacocoDebug") {
          task.dependsOn(addNonClassFileTask)
        }
      }
    }
  }

  @Test
  fun build() {
    val build = rule.build
    build.executor.run(":app:jacocoDebug")

    val appBuildDir = build.androidApplication().buildDir
    val javaFile =
      appBuildDir.resolve(
        "intermediates/javac/debug/compileDebugJavaWithJavac/classes/com/test/jacoco/annotation/BindActivity\$\$ViewBinder.java"
      )
    assertThat(javaFile).exists()

    val instrumentedClassFile =
      appBuildDir.resolve("intermediates/classes/debug/jacocoDebug/dirs/com/test/jacoco/annotation/BindActivity.class")
    assertThat(instrumentedClassFile).exists()
    val ignoredJavaFile =
      appBuildDir.resolve("intermediates/classes/debug/jacocoDebug/dirs/com/test/jacoco/annotation/BindActivity\$\$ViewBinder.java")
    assertThat(ignoredJavaFile).doesNotExist()
  }
}

abstract class AddNonClassFileTask : DefaultTask() {
  @get:OutputFile abstract val nonClassFile: RegularFileProperty

  @TaskAction
  fun execute() {
    val file = nonClassFile.get().asFile
    file.parentFile.mkdirs()
    file.writeText("// Non-class file in compiler output directory\n")
  }
}
