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

package com.android.build.gradle.integration.cacheability

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import kotlin.io.path.absolutePathString
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AarResourcesCompilerTransformCacheabilityTest {

  @get:Rule val localBuildCacheDir = TemporaryFolder()

  @get:Rule
  val rule =
    GradleRule.configure()
      .withMavenRepository {
        aar("com.lib", "unlinkableres", "1.0")
          .addResource(
            "layout/layout.xml",
            // language=XML
            """
            <?xml version="1.0" encoding="utf-8"?>
                  <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                  android:orientation="vertical"
                  android:layout_width="match_parent"
                  android:layout_height="match_parent">

                  <TextView
                  android:layout_width="match_parent"
                  android:layout_height="match_parent"
                  android:text="@string/doesnt_exist"
                  />

                  </LinearLayout>
            """
              .trimIndent(),
          )
      }
      .from {
        androidApplication {
          android {
            namespace = "com.example.app"
            defaultConfig { minSdk = 21 }
          }
          dependencies { implementation("androidx.compose.material:material:1.3.0") }
        }
      }

  @Test
  fun testCacheIsIndependentOfUserHomeChanges() {
    val build = rule.build
    val cacheDir = localBuildCacheDir.newFolder("cache").toPath()
    val userHome1 = localBuildCacheDir.newFolder("userHome1").toPath()
    val userHome2 = localBuildCacheDir.newFolder("userHome2").toPath()

    build.reconfigureSettings { enableLocalCache(cacheDir) }

    val userHome1Result =
      build.executor
        .withArgument("--build-cache")
        .withArgument("-Dgradle.user.home=${userHome1.toAbsolutePath()}")
        .withArgument("--info")
        .run(":app:assembleDebug")

    userHome1Result.assertOutputContains("Compiling xml file")
    userHome1Result.assertOutputContains("Stored cache entry for AarResourcesCompilerTransform")

    val userHome2Result =
      build.executor
        .withArgument("--build-cache")
        .withArgument("-Dgradle.user.home=${userHome2.toAbsolutePath()}")
        .withArgument("--info")
        .run(":app:clean", ":app:assembleDebug")

    // No resource compilation should take place as the previously compiled resources should be retrieved
    // from cache (despite Gradle home change).
    userHome2Result.assertOutputDoesNotContain("Compiling xml file")

    build.androidApplication().reconfigure { dependencies { implementation("com.lib:unlinkableres:1.0") } }

    val linkFailureResult =
      build.executor
        .withArgument("--build-cache")
        .withArgument("-Dgradle.user.home=${userHome2.toAbsolutePath()}")
        .expectFailure()
        .run(":app:clean", ":app:assembleDebug")

    // Check linking failure reports the path to the userHome2 Gradle home resource.
    linkFailureResult.assertErrorContains("Android resource linking failed")
    linkFailureResult.assertErrorContains(userHome2.absolutePathString())
    linkFailureResult.assertErrorContains("layout.xml")
  }
}
