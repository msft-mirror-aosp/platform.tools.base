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
import com.android.build.gradle.options.StringOption
import com.android.builder.dexing.R8Version
import com.android.testutils.TestInputsGenerator
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests that D8 dex archive building runs on the R8 selected by [StringOption.R8_VERSION_OVERRIDE]: the dexBuilder task for the project
 * classes, the dexing artifact transform for external libraries, and the task that dexes file dependencies.
 *
 * Each test runs only the tasks that need one of them, and then prints how many isolated R8 ClassLoaders the shared
 * R8ClassloaderBuildService created. Nothing else in these builds uses an isolated R8 (dex merging still uses the bundled R8), so a
 * ClassLoader means that dex archive building used the Maven-resolved R8.
 */
class R8FromMavenD8DexingTest {

  @get:Rule
  val rule =
    GradleRule.configure().from {
      buildFileType = BuildFileType.KTS
      androidApplication {
        files {
          add(
            "src/main/java/com/example/UsesLambda.java",
            // language=java
            """
            package com.example;

            public class UsesLambda {
                public static Runnable get() { return () -> {}; }
            }
            """
              .trimIndent(),
          )
        }
      }
    }

  @Before
  fun setUp() {
    // minSdk 21 so D8 desugars lambdas against the bootclasspath and classpath providers. Guava is an external library, so the dexing
    // artifact transform dexes it.
    rule.build.androidApplication().files.update("build.gradle.kts") {
      append(
        """

        android {
            defaultConfig.minSdk = 21
        }
        dependencies {
            implementation("com.google.guava:guava:20.0")
        }

        tasks.withType(com.android.build.gradle.internal.tasks.DexArchiveBuilderTask::class.java).configureEach {
            val r8Classpath = this.r8Classpath
            doFirst { println("dexBuilder r8Classpath: " + r8Classpath.files.map { it.name }.sorted()) }
        }
        tasks.withType(com.android.build.gradle.internal.tasks.DexFileDependenciesTask::class.java).configureEach {
            val r8Classpath = this.r8Classpath
            doFirst { println("file dependencies r8Classpath: " + r8Classpath.files.map { it.name }.sorted()) }
        }

        tasks.register("printIsolatedR8ClassLoaders") {
            val r8ClassloaderService =
                gradle.sharedServices.registrations
                    .findByName(
                        com.android.build.gradle.internal.services.getBuildServiceName(
                            com.android.build.gradle.internal.services.R8ClassloaderBuildService::class.java
                        )
                    )!!
                    .service
            usesService(r8ClassloaderService)
            mustRunAfter("dexBuilderDebug", "desugarDebugFileDependencies", "mergeExtDexDebug")
            doLast {
                val service = r8ClassloaderService.get() as com.android.build.gradle.internal.services.R8ClassloaderBuildService
                println("isolated R8 ClassLoaders: " + service.getCachedClassLoaderCount())
            }
        }
        """
          .trimIndent()
      )
    }
  }

  @Test
  fun dexBuilderRunsWithVersionOverride() {
    val overrideVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH

    val result =
      rule.build.executor
        .with(StringOption.R8_VERSION_OVERRIDE, overrideVersion)
        .run(":app:dexBuilderDebug", ":app:printIsolatedR8ClassLoaders")

    assertThat(result.didWorkTasks).contains(":app:dexBuilderDebug")
    result.assertOutputContains("dexBuilder r8Classpath: [r8-$overrideVersion.jar")
    result.assertOutputContains("isolated R8 ClassLoaders: 1")
  }

  @Test
  fun dexingTransformRunsWithVersionOverride() {
    // Merging the external library dex files needs the dexing transform output for Guava, but not the dexBuilder task. The file
    // dependencies task also runs, but without file dependencies it has nothing to dex.
    val result =
      rule.build.executor
        .with(StringOption.R8_VERSION_OVERRIDE, R8Version.VERSION_AGP_WAS_SHIPPED_WITH)
        .run(":app:mergeExtDexDebug", ":app:printIsolatedR8ClassLoaders")

    assertThat(result.didWorkTasks).contains(":app:mergeExtDexDebug")
    assertThat(result.didWorkTasks).doesNotContain(":app:dexBuilderDebug")
    result.assertOutputContains("isolated R8 ClassLoaders: 1")
  }

  @Test
  fun fileDependenciesDexingRunsWithVersionOverride() {
    addFileDependency()
    val overrideVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH

    val result =
      rule.build.executor
        .with(StringOption.R8_VERSION_OVERRIDE, overrideVersion)
        .run(":app:desugarDebugFileDependencies", ":app:printIsolatedR8ClassLoaders")

    assertThat(result.didWorkTasks).contains(":app:desugarDebugFileDependencies")
    result.assertOutputContains("file dependencies r8Classpath: [r8-$overrideVersion.jar")
    result.assertOutputContains("isolated R8 ClassLoaders: 1")
  }

  @Test
  fun dexArchiveBuildingUsesBundledR8WithoutOverride() {
    addFileDependency()

    val result =
      rule.build.executor.run(
        ":app:dexBuilderDebug",
        ":app:desugarDebugFileDependencies",
        ":app:mergeExtDexDebug",
        ":app:printIsolatedR8ClassLoaders",
      )

    assertThat(result.didWorkTasks).containsAtLeast(":app:dexBuilderDebug", ":app:desugarDebugFileDependencies", ":app:mergeExtDexDebug")
    result.assertOutputContains("dexBuilder r8Classpath: []")
    result.assertOutputContains("file dependencies r8Classpath: []")
    result.assertOutputContains("isolated R8 ClassLoaders: 0")
  }

  /** Adds a local jar dependency, which the file dependencies task dexes. */
  private fun addFileDependency() {
    with(rule.build.androidApplication().files) {
      add("libs/local.jar", TestInputsGenerator.jarWithEmptyClasses(listOf("com/example/local/LocalClass")))
      update("build.gradle.kts") {
        append(
          """

          dependencies {
              implementation(files("libs/local.jar"))
          }
          """
            .trimIndent()
        )
      }
    }
  }
}
