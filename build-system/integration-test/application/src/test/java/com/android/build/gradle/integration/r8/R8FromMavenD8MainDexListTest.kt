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
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_APP_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.AndroidProjectDefinition.Companion.DEFAULT_FEATURE_PATH
import com.android.build.gradle.integration.common.fixture.project.builder.BuildFileType
import com.android.build.gradle.options.StringOption
import com.android.builder.dexing.R8Version
import com.android.utils.FileUtils
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests that the main dex list for bundles ([com.android.build.gradle.internal.tasks.D8BundleMainDexListTask]) is generated on the R8
 * selected by [StringOption.R8_VERSION_OVERRIDE]. That task only exists for base modules with dynamic features using legacy multidex.
 *
 * The task prints its r8Classpath before it runs, which shows the override is wired to it. To show that it actually ran in the isolated R8
 * ClassLoader, the test first builds the bundle, then deletes the main dex list so that only that task runs again, and a probe task prints
 * how many isolated R8 ClassLoaders the shared R8ClassloaderBuildService created in that build.
 */
class R8FromMavenD8MainDexListTest {

  @get:Rule
  val rule =
    GradleRule.configure().from {
      buildFileType = BuildFileType.KTS
      androidApplication {
        android {
          defaultConfig {
            minSdk = 19
            multiDexEnabled = true
            // bundletool requires a version code.
            versionCode = 1
          }
          dynamicFeatures.add(DEFAULT_FEATURE_PATH)
        }
      }
      androidFeature {
        android { defaultConfig { minSdk = 19 } }
        dependencies { implementation(project(DEFAULT_APP_PATH)) }
      }
    }

  @Before
  fun setUp() {
    // bundletool requires dynamic feature modules to declare their delivery options.
    rule.build.androidFeature().files.update("src/main/AndroidManifest.xml") {
      replaceWith(
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            xmlns:dist="http://schemas.android.com/apk/distribution">
            <dist:module>
                <dist:fusing dist:include="true" />
                <dist:delivery>
                    <dist:install-time />
                </dist:delivery>
            </dist:module>
            <application />
        </manifest>
        """
          .trimIndent()
      )
    }
    rule.build.androidApplication().files.update("build.gradle.kts") {
      append(
        """

        tasks.withType(com.android.build.gradle.internal.tasks.D8BundleMainDexListTask::class.java).configureEach {
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
            mustRunAfter(tasks.withType(com.android.build.gradle.internal.tasks.D8BundleMainDexListTask::class.java))
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
  fun bundleMainDexListRunsWithVersionOverride() {
    val overrideVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH

    val bundleResult = rule.build.executor.with(StringOption.R8_VERSION_OVERRIDE, overrideVersion).run(":app:bundleDebug")

    assertThat(bundleResult.didWorkTasks).containsAtLeast(":app:bundleMultiDexListDebug", ":app:packageDebugBundle")
    bundleResult.assertOutputContains("bundleMultiDexListDebug r8Classpath: [r8-$overrideVersion.jar]")

    deleteMainDexList()
    val result =
      rule.build.executor
        .with(StringOption.R8_VERSION_OVERRIDE, overrideVersion)
        .run(":app:bundleMultiDexListDebug", ":app:printIsolatedR8ClassLoaders")

    assertThat(result.didWorkTasks).contains(":app:bundleMultiDexListDebug")
    assertThat(result.didWorkTasks).doesNotContain(":app:dexBuilderDebug")
    assertThat(result.didWorkTasks).doesNotContain(":app:mergeDexDebug")
    result.assertOutputContains("isolated R8 ClassLoaders: 1")
  }

  @Test
  fun bundleMainDexListUsesDefaultR8FromMavenWithoutOverride() {
    val defaultVersion = R8Version.VERSION_AGP_WAS_SHIPPED_WITH
    val result = rule.build.executor.run(":app:bundleMultiDexListDebug", ":app:printIsolatedR8ClassLoaders")

    assertThat(result.didWorkTasks).contains(":app:bundleMultiDexListDebug")
    result.assertOutputContains("bundleMultiDexListDebug r8Classpath: [r8-$defaultVersion.jar]")
    result.assertOutputContains("isolated R8 ClassLoaders: 1")
  }

  /** Deletes the main dex list for the bundle, so that it is generated again in the next build while its inputs are up to date. */
  private fun deleteMainDexList() {
    FileUtils.deleteRecursivelyIfExists(rule.build.androidApplication().resolve("build/intermediates/main_dex_list_for_bundle").toFile())
  }
}
