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
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import java.util.zip.ZipFile
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests that D8 dex merging and global synthetics generation run on the R8 selected by [StringOption.R8_VERSION_OVERRIDE].
 *
 * The tasks print their r8Classpath before they run, which shows the override is wired to them. To show that they actually ran D8 in the
 * isolated R8 ClassLoader, a probe task prints how many isolated R8 ClassLoaders the shared R8ClassloaderBuildService created in a build
 * where only the tested task needs R8: dex archive building also uses the isolated R8, so the dex merging tests first build the dex
 * archives, then delete the merged dex files so that only the merging tasks run again.
 */
class R8FromMavenD8MergingTest {

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
    // Guava is an external library, so mergeExtDex has something to merge.
    rule.build.androidApplication().files.update("build.gradle.kts") {
      append(
        """

        dependencies {
            implementation("com.google.guava:guava:20.0")
        }

        tasks.withType(com.android.build.gradle.internal.tasks.DexMergingTask::class.java).configureEach {
            val taskName = name
            val r8Classpath = sharedParams.r8Classpath
            doFirst { println(taskName + " r8Classpath: " + r8Classpath.files.map { it.name }.sorted()) }
        }
        tasks.withType(com.android.build.gradle.internal.tasks.GlobalSyntheticsMergeTask::class.java).configureEach {
            val taskName = name
            val r8Classpath = sharedParams.r8Classpath
            doFirst { println(taskName + " r8Classpath: " + r8Classpath.files.map { it.name }.sorted()) }
        }
        tasks.withType(com.android.build.gradle.internal.tasks.GlobalSyntheticsGeneratorTask::class.java).configureEach {
            val taskName = name
            val r8Classpath = this.r8Classpath
            doFirst { println(taskName + " r8Classpath: " + r8Classpath.files.map { it.name }.sorted()) }
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
            mustRunAfter(
                tasks.withType(com.android.build.gradle.internal.tasks.DexMergingTask::class.java),
                tasks.withType(com.android.build.gradle.internal.tasks.GlobalSyntheticsGeneratorTask::class.java),
            )
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
  fun debugDexMergingRunsWithVersionOverride() {
    // Debug builds with minSdk 21 merge the project, library and external library dex files in separate tasks.
    setDefaultConfig("minSdk = 21")
    val overrideVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH
    val mergingTasks = listOf(":app:mergeProjectDexDebug", ":app:mergeLibDexDebug", ":app:mergeExtDexDebug")

    rule.build.executor.with(StringOption.R8_VERSION_OVERRIDE, overrideVersion).run(mergingTasks)
    deleteMergedDex()
    val result =
      rule.build.executor.with(StringOption.R8_VERSION_OVERRIDE, overrideVersion).run(mergingTasks + ":app:printIsolatedR8ClassLoaders")

    assertThat(result.didWorkTasks).containsAtLeastElementsIn(mergingTasks)
    assertThat(result.didWorkTasks).doesNotContain(":app:dexBuilderDebug")
    for (task in mergingTasks) {
      result.assertOutputContains("${task.substringAfterLast(':')} r8Classpath: [r8-$overrideVersion.jar]")
    }
    result.assertOutputContains("isolated R8 ClassLoaders: 1")
  }

  @Test
  fun globalSyntheticsGenerationRunsWithVersionOverride() {
    // Debug builds with minSdk 21 generate all global synthetics in one task, which needs nothing else that uses R8.
    setDefaultConfig("minSdk = 21")
    val overrideVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH

    val result =
      rule.build.executor
        .with(StringOption.R8_VERSION_OVERRIDE, overrideVersion)
        .run(":app:generateDebugGlobalSynthetics", ":app:printIsolatedR8ClassLoaders")

    assertThat(result.didWorkTasks).contains(":app:generateDebugGlobalSynthetics")
    result.assertOutputContains("generateDebugGlobalSynthetics r8Classpath: [r8-$overrideVersion.jar]")
    result.assertOutputContains("isolated R8 ClassLoaders: 1")
  }

  @Test
  fun globalSyntheticsMergingRunsWithVersionOverride() {
    // Non-minified release builds with minSdk 21 merge the global synthetics from dexing in a separate task, which shares the dex merging
    // parameters.
    setDefaultConfig("minSdk = 21")
    val overrideVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH

    val result = rule.build.executor.with(StringOption.R8_VERSION_OVERRIDE, overrideVersion).run(":app:mergeReleaseGlobalSynthetics")

    assertThat(result.didWorkTasks).contains(":app:mergeReleaseGlobalSynthetics")
    result.assertOutputContains("mergeReleaseGlobalSynthetics r8Classpath: [r8-$overrideVersion.jar]")
  }

  @Test
  fun legacyMultidexBundleMergesWithVersionOverride() {
    // minSdk < 21 with multidex uses legacy multidex, which merges everything in a single MERGE_ALL task. That task writes the D8 build
    // metadata, which is packaged into the bundle. bundletool requires a version code.
    setDefaultConfig("minSdk = 19", "multiDexEnabled = true", "versionCode = 1")
    val overrideVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH

    val bundleResult = rule.build.executor.with(StringOption.R8_VERSION_OVERRIDE, overrideVersion).run(":app:bundleDebug")

    assertThat(bundleResult.didWorkTasks).containsAtLeast(":app:mergeDexDebug", ":app:packageDebugBundle")
    bundleResult.assertOutputContains("mergeDexDebug r8Classpath: [r8-$overrideVersion.jar]")
    val bundle =
      rule.build.androidApplication().resolve("build/outputs/bundle/debug").toFile().listFiles()!!.single { it.extension == "aab" }
    ZipFile(bundle).use { zip ->
      val d8Metadata = zip.getEntry("BUNDLE-METADATA/com.android.tools/d8.json")
      assertThat(d8Metadata).isNotNull()
      val json = zip.getInputStream(d8Metadata).readBytes().toString(Charsets.UTF_8).trim()
      assertThat(json).startsWith("{")
      assertThat(json).endsWith("}")
      assertThat(json.removePrefix("{").removeSuffix("}").trim()).isNotEmpty()
    }

    deleteMergedDex()
    val mergeResult =
      rule.build.executor
        .with(StringOption.R8_VERSION_OVERRIDE, overrideVersion)
        .run(":app:mergeDexDebug", ":app:printIsolatedR8ClassLoaders")

    assertThat(mergeResult.didWorkTasks).contains(":app:mergeDexDebug")
    assertThat(mergeResult.didWorkTasks).doesNotContain(":app:dexBuilderDebug")
    mergeResult.assertOutputContains("isolated R8 ClassLoaders: 1")
  }

  @Test
  fun dexMergingAndGlobalSyntheticsUseDefaultR8FromMavenWithoutOverride() {
    setDefaultConfig("minSdk = 21")
    val defaultVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH

    val result =
      rule.build.executor.run(
        ":app:mergeProjectDexDebug",
        ":app:mergeExtDexDebug",
        ":app:generateDebugGlobalSynthetics",
        ":app:printIsolatedR8ClassLoaders",
      )

    assertThat(result.didWorkTasks)
      .containsAtLeast(":app:mergeProjectDexDebug", ":app:mergeExtDexDebug", ":app:generateDebugGlobalSynthetics")
    result.assertOutputContains("mergeProjectDexDebug r8Classpath: [r8-$defaultVersion.jar]")
    result.assertOutputContains("mergeExtDexDebug r8Classpath: [r8-$defaultVersion.jar]")
    result.assertOutputContains("generateDebugGlobalSynthetics r8Classpath: [r8-$defaultVersion.jar]")
    result.assertOutputContains("isolated R8 ClassLoaders: 1")
  }

  private fun setDefaultConfig(vararg settings: String) {
    rule.build.androidApplication().files.update("build.gradle.kts") {
      append(
        """

        android {
            defaultConfig {
                ${settings.joinToString("\n                ")}
            }
        }
        """
          .trimIndent()
      )
    }
  }

  /** Deletes the outputs of the dex merging tasks, so that they run again in the next build while the dex archives are up to date. */
  private fun deleteMergedDex() {
    FileUtils.deleteRecursivelyIfExists(rule.build.androidApplication().resolve("build/intermediates/dex").toFile())
  }
}
