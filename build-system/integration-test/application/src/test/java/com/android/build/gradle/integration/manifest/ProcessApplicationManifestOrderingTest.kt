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

package com.android.build.gradle.integration.manifest

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.testutils.truth.PathSubject.assertThat
import kotlin.io.path.readText
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Integration tests to verify that changing the order of dependencies correctly invalidates the build cache and triggers manifest merging
 * for application components (b/514242899).
 */
class ProcessApplicationManifestOrderingTest {

  @get:Rule
  val rule = GradleRule.from {
    androidApplication {
      dependencies {
        implementation(project(":libbluetooth"))
        implementation(project(":libwifi"))
      }
    }
    androidLibrary(":libbluetooth") {
      files.add(
        "src/main/AndroidManifest.xml",
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-permission android:name="android.permission.BLUETOOTH"/>
        </manifest>
        """
          .trimIndent(),
      )
    }
    androidLibrary(":libwifi") {
      files.add(
        "src/main/AndroidManifest.xml",
        """
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>
        </manifest>
        """
          .trimIndent(),
      )
    }
  }

  @Test
  fun testManifestMergingOrderCorrectnessAndCaching() {
    val build = rule.build
    val appProject = build.androidApplication()

    // Initial dependency order: libbluetooth then libwifi
    var result = build.executor.run(":app:processDebugManifest")
    assertTrue(result.failedTasks.isEmpty())

    val manifestFile = appProject.resolve("build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml")
    assertThat(manifestFile).exists()

    var content = manifestFile.readText()
    var bluetoothIndex = content.indexOf("android.permission.BLUETOOTH")
    var wifiIndex = content.indexOf("android.permission.ACCESS_WIFI_STATE")
    assertTrue(bluetoothIndex < wifiIndex)

    // Swap order in app/build.gradle
    appProject.files.update("build.gradle") {
      searchAndReplace("implementation(project(':libbluetooth'))", "temp_placeholder")
        .searchAndReplace("implementation(project(':libwifi'))", "implementation(project(':libbluetooth'))")
        .searchAndReplace("temp_placeholder", "implementation(project(':libwifi'))")
    }

    // Run again. The task MUST execute again and not be UP-TO-DATE.
    result = build.executor.run(":app:processDebugManifest")
    assertTrue(result.failedTasks.isEmpty())
    result.assertTask(":app:processDebugManifest").didWork() // Not skipped / up-to-date

    content = manifestFile.readText()
    bluetoothIndex = content.indexOf("android.permission.BLUETOOTH")
    wifiIndex = content.indexOf("android.permission.ACCESS_WIFI_STATE")
    assertTrue(wifiIndex < bluetoothIndex) // Order swapped successfully
  }
}
