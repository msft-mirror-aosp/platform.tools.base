/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.build.gradle.integration.dsl

import com.android.SdkConstants
import com.android.build.gradle.LibraryExtension
import com.android.build.gradle.integration.common.fixture.app.ManifestFileBuilder
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyApplicationCallback
import com.android.build.gradle.integration.common.fixture.project.plugins.LegacyLibraryCallback
import com.android.build.gradle.internal.dsl.BaseAppModuleExtension
import com.android.build.gradle.options.BooleanOption
import com.android.utils.XmlUtils
import com.google.common.io.Files
import com.google.common.truth.Truth
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class LegacyVariantApiDslManifestPlaceholderTest {
  companion object {
    private const val mainPermissionSuffix = "main"
    private const val androidTestPermissionSuffix = "test"
    private const val unitTestPermissionSuffix = "unitTest"
    private const val permissionPrefix = "org.test.permission.READ_CREDENTIALS"
    private val libraryManifest =
      with(ManifestFileBuilder()) {
        addUsesPermissionTag("$permissionPrefix:\${permissionSuffix}")
        build()
      }
  }

  // A Gradle project with 3 modules: app, lib1 and lib2. lib2 has a manifest with a placeholder.
  // lib1 depends on lib2. We use legacy variant API to specify substitutions for lib2's placeholder
  // for lib1's unit test and android test variants in lib1's build file.
  // app depends on lib2. We use legacy variant API to specify substitutions for lib2's placeholder
  // for app's application variant in app's build file.
  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android { defaultConfig.minSdk = 14 }
        pluginCallbacks += AppCallback::class.java
        dependencies { implementation(project(":lib2")) }
      }
      androidLibrary(":lib1") {
        android {
          defaultConfig {
            minSdk = 14
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
          dependencies {
            androidTestImplementation(project(":lib2"))
            testImplementation(project(":lib2"))
          }
        }
        pluginCallbacks += LibCallback::class.java
      }
      androidLibrary(":lib2") { files.update("src/main/AndroidManifest.xml").replaceWith(libraryManifest) }
      gradleProperties { add(BooleanOption.USE_NEW_DSL, false) }
    }

  class AppCallback : LegacyApplicationCallback {
    override fun handleExtension(project: Project, extension: BaseAppModuleExtension) {
      extension.applicationVariants.configureEach { variant ->
        variant.mergedFlavor.manifestPlaceholders += mapOf("permissionSuffix" to mainPermissionSuffix)
      }
    }
  }

  class LibCallback : LegacyLibraryCallback {
    override fun handleExtension(project: Project, extension: LibraryExtension) {
      extension.testVariants.configureEach { variant ->
        variant.mergedFlavor.manifestPlaceholders += mapOf("permissionSuffix" to androidTestPermissionSuffix)
      }
      extension.unitTestVariants.configureEach { variant ->
        variant.mergedFlavor.manifestPlaceholders += mapOf("permissionSuffix" to unitTestPermissionSuffix)
      }
      extension.testOptions { unitTests.isIncludeAndroidResources = true }
    }
  }

  @Test
  fun applicationVariantManifestPlaceholder() {
    val build = rule.build
    build.executor.run(":app:processDebugManifest")

    verifyPermissionAddedToManifest(
      build.androidApplication().intermediatesDir.resolve("merged_manifests/debug/processDebugManifest/AndroidManifest.xml"),
      mainPermissionSuffix,
    )
  }

  @Test
  fun androidTestManifestPlaceholder() {
    val build = rule.build
    build.executor.run(":lib1:processDebugAndroidTestManifest")

    verifyPermissionAddedToManifest(
      build
        .androidLibrary(":lib1")
        .intermediatesDir
        .resolve("packaged_manifests/debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml"),
      androidTestPermissionSuffix,
    )
  }

  @Test
  fun unitTestManifestPlaceholder() {
    val build = rule.build
    build.executor.run(":lib1:processDebugUnitTestManifest")

    verifyPermissionAddedToManifest(
      build
        .androidLibrary(":lib1")
        .intermediatesDir
        .resolve("packaged_manifests/debugUnitTest/processDebugUnitTestManifest/AndroidManifest.xml"),
      unitTestPermissionSuffix,
    )
  }

  private fun verifyPermissionAddedToManifest(manifestPath: Path, permissionSuffix: String) {
    val document = XmlUtils.parseDocument(Files.asCharSource(manifestPath.toFile(), StandardCharsets.UTF_8).read(), false)
    val nodeList = document.getElementsByTagName(SdkConstants.TAG_USES_PERMISSION)

    var found = false
    for (i in 0 until nodeList.length) {
      val permissionName = nodeList.item(i).attributes.getNamedItem("android:name")?.nodeValue
      found = found || permissionName.equals("$permissionPrefix:$permissionSuffix")
    }
    Truth.assertThat(found).isTrue()
  }
}
