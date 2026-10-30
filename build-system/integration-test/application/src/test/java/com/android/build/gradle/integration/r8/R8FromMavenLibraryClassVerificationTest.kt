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

package com.android.build.gradle.integration.r8

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.options.BooleanOption
import com.android.build.gradle.options.StringOption
import com.android.builder.dexing.R8Version
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests that library class verification (TraceReferences) runs on the R8 selected by [StringOption.R8_VERSION_OVERRIDE]. */
class R8FromMavenLibraryClassVerificationTest {

  @get:Rule
  val rule =
    GradleRule.configure().from {
      buildFileType = BuildFileType.KTS
      androidLibrary(":lib") {
        android {
          namespace = "com.example.lib"
          defaultConfig.minSdk = 21
        }
        dependencies {
          implementation(localJar("embedded.jar") { addEmptyClasses("com/example/EmbeddedJarClass") })
          compileOnly(localJar("compileOnly.jar") { addEmptyClasses("com/example/CompileOnlyJarClass") })
        }
        files {
          add(
            "src/main/java/com/example/lib/Use.java",
            // language=java
            """
            package com.example.lib;

            public class Use extends com.example.EmbeddedJarClass {}
            """
              .trimIndent(),
          )
        }
      }
      gradleProperties { add(BooleanOption.VERIFY_AAR_CLASSES, true) }
    }

  @Before
  fun setUp() {
    // Print the R8 classpath the verification task runs with, so the tests can tell the isolated path from the bundled one.
    rule.build.androidLibrary(":lib").files.update("build.gradle.kts") {
      append(
        """

        tasks.withType(com.android.build.gradle.internal.tasks.VerifyLibraryClassesTask::class.java).configureEach {
            val r8Classpath = this.r8Classpath
            doFirst { println("verify r8Classpath: " + r8Classpath.files.map { it.name }.sorted()) }
        }
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun verificationRunsWithVersionOverride() {
    val build = rule.build
    val overrideVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH

    val result = build.executor.with(StringOption.R8_VERSION_OVERRIDE, overrideVersion).run(":lib:verifyReleaseClasses")

    assertThat(result.didWorkTasks).contains(":lib:verifyReleaseClasses")
    result.assertOutputContains("verify r8Classpath: [r8-$overrideVersion.jar")

    // The check itself runs on the override R8: a reference to a compileOnly class fails verification.
    build
      .androidLibrary(":lib")
      .files
      .update("src/main/java/com/example/lib/Use.java")
      .searchAndReplace("EmbeddedJarClass", "CompileOnlyJarClass")
    val failure = build.executor.expectFailure().with(StringOption.R8_VERSION_OVERRIDE, overrideVersion).run(":lib:verifyReleaseClasses")
    failure.assertErrorContains("Missing class com.example.CompileOnlyJarClass")
  }

  @Test
  fun verificationUsesBundledR8WithoutOverride() {
    val result = rule.build.executor.run(":lib:verifyReleaseClasses")

    assertThat(result.didWorkTasks).contains(":lib:verifyReleaseClasses")
    result.assertOutputContains("verify r8Classpath: []")
  }
}
