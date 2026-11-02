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

import com.android.build.gradle.integration.common.fixture.DESUGAR_DEPENDENCY_VERSION
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.options.StringOption
import com.android.builder.dexing.R8Version
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Tests that L8 desugared library dexing (keep rule generation and L8) runs on the R8 selected by [StringOption.R8_VERSION_OVERRIDE]. */
class R8FromMavenL8DesugarLibTest {

  @get:Rule
  val rule =
    GradleRule.configure().from {
      buildFileType = BuildFileType.KTS
      androidApplication {
        files {
          add(
            "src/main/java/com/example/UsesOptional.java",
            // language=java
            """
            package com.example;

            public class UsesOptional {
                public static Object get() { return java.util.Optional.empty(); }
            }
            """
              .trimIndent(),
          )
          add("proguard-rules.pro", "-keep class com.example.UsesOptional { *; }")
        }
      }
    }

  @Before
  fun setUp() {
    // Library desugaring with minify, so the L8 task generates keep rules for the app's use of java.util.Optional and shrinks the desugar
    // library with them. Also print the R8 classpath the L8 task runs with, so the tests can tell the isolated path from the bundled one.
    rule.build.androidApplication().files.update("build.gradle.kts") {
      append(
        """

        android {
            compileOptions.isCoreLibraryDesugaringEnabled = true
            defaultConfig.minSdk = 21
            buildTypes {
                release {
                    isMinifyEnabled = true
                    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
                }
            }
        }
        dependencies {
            coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:$DESUGAR_DEPENDENCY_VERSION")
        }

        tasks.withType(com.android.build.gradle.internal.tasks.L8DexDesugarLibTask::class.java).configureEach {
            val r8Classpath = this.r8Classpath
            doFirst { println("l8 r8Classpath: " + r8Classpath.files.map { it.name }.sorted()) }
        }
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun l8DexingRunsWithVersionOverride() {
    val overrideVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH

    val result = rule.build.executor.with(StringOption.R8_VERSION_OVERRIDE, overrideVersion).run(":app:l8DexDesugarLibRelease")

    assertThat(result.didWorkTasks).contains(":app:l8DexDesugarLibRelease")
    result.assertOutputContains("l8 r8Classpath: [r8-$overrideVersion.jar")
  }

  @Test
  fun l8DexingUsesBundledR8WithoutOverride() {
    val result = rule.build.executor.run(":app:l8DexDesugarLibRelease")

    assertThat(result.didWorkTasks).contains(":app:l8DexDesugarLibRelease")
    result.assertOutputContains("l8 r8Classpath: []")
  }
}
