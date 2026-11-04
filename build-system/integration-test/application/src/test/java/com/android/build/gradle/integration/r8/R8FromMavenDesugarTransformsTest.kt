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
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests that the desugaring artifact transforms (L8 on the desugar library, and the D8 backported methods list for lint) run on the R8
 * selected by [StringOption.R8_VERSION_OVERRIDE].
 *
 * Transform parameters can't be inspected from a build script, so each test runs a task that only needs one of the transforms, and then
 * prints how many isolated R8 ClassLoaders the shared R8ClassloaderBuildService created. No other R8 tool runs in these builds, so a
 * ClassLoader means the transform used the Maven-resolved R8.
 */
class R8FromMavenDesugarTransformsTest {

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
          add("src/main/baseline-prof.txt", "Lj$/util/Optional;")
        }
      }
    }

  @Before
  fun setUp() {
    rule.build.androidApplication().files.update("build.gradle.kts") {
      append(
        """

        android {
            compileOptions.isCoreLibraryDesugaringEnabled = true
            defaultConfig.minSdk = 21
        }
        dependencies {
            coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:$DESUGAR_DEPENDENCY_VERSION")
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
            mustRunAfter("lintAnalyzeDebug", "expandReleaseL8ArtProfileWildcards")
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
  fun backportedMethodsGeneratorRunsWithVersionOverride() {
    // Lint analysis reads the list of desugared methods, which the backported methods generator transform produces.
    val result =
      rule.build.executor
        .with(StringOption.R8_VERSION_OVERRIDE, R8Version.VERSION_AGP_WAS_SHIPPED_WITH)
        .run(":app:lintAnalyzeDebug", ":app:printIsolatedR8ClassLoaders")

    result.assertOutputContains("isolated R8 ClassLoaders: 1")
  }

  @Test
  fun backportedMethodsGeneratorUsesBundledR8WithoutOverride() {
    val result = rule.build.executor.run(":app:lintAnalyzeDebug", ":app:printIsolatedR8ClassLoaders")

    result.assertOutputContains("isolated R8 ClassLoaders: 0")
  }

  @Test
  fun l8DesugarLibTransformRunsWithVersionOverride() {
    // Expanding the L8 ART profile reads the desugared desugar library, which L8DesugarLibTransform produces. Unlike
    // l8DexDesugarLibRelease,
    // this task doesn't run L8 or R8 itself.
    val result =
      rule.build.executor
        .with(StringOption.R8_VERSION_OVERRIDE, R8Version.VERSION_AGP_WAS_SHIPPED_WITH)
        .run(":app:expandReleaseL8ArtProfileWildcards", ":app:printIsolatedR8ClassLoaders")

    result.assertOutputContains("isolated R8 ClassLoaders: 1")
  }

  @Test
  fun l8DesugarLibTransformUsesBundledR8WithoutOverride() {
    val result = rule.build.executor.run(":app:expandReleaseL8ArtProfileWildcards", ":app:printIsolatedR8ClassLoaders")

    result.assertOutputContains("isolated R8 ClassLoaders: 0")
  }
}
