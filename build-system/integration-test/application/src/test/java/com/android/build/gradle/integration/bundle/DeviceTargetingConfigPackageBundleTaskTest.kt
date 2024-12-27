/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.integration.bundle

import com.android.build.gradle.integration.common.fixture.GradleTestProject
import com.android.build.gradle.integration.common.fixture.app.MinimalSubProject
import com.android.build.gradle.integration.common.fixture.app.MultiModuleTestProject
import com.android.build.gradle.options.BooleanOption
import com.android.bundle.Config
import com.android.testutils.truth.PathSubject.assertThat
import com.android.testutils.truth.ZipFileSubject.assertThat
import com.android.tools.build.bundletool.model.AppBundle
import com.google.common.base.Throwables
import com.google.common.truth.Truth.assertThat
import java.util.zip.ZipFile
import org.junit.Rule
import org.junit.Test

class DeviceTargetingConfigPackageBundleTaskTest {

  private val config =
    """
    {
      "device_groups": [
        {
          "name": "test_group",
          "device_selectors": [
            {
              "included_device_ids": [
                { "build_brand": "google", "build_device": "husky"}
              ]
            }
          ]
        }
      ]
    }
    """
      .trimIndent()

  private val app =
    MinimalSubProject.app("com.example.test")
      .withFile("src/main/config.json", config)

  @get:Rule
  val project =
    GradleTestProject.builder()
      .fromTestApp(MultiModuleTestProject.builder().subproject(":app", app).build())
      .create()

  @Test
  fun testDeviceTargetingConfig_enabled() {
    project
      .getSubproject(":app")
      .buildFile
      .appendText(
          """
          android {
            bundle {
              deviceGroup {
                enableSplit = true
                defaultGroup = 'test_group'
              }
              deviceTargetingConfig = file('src/main/config.json')
            }
          }
          """.trimIndent())
    project.executor()
            .with(BooleanOption.ENABLE_DEVICE_TARGETING_CONFIG_API, true)
            .run(":app:bundleDebug")

    val bundleFile = project.locateBundleFileViaModel("debug", ":app")

    assertThat(bundleFile).isNotNull()
    assertThat(bundleFile.toPath()).exists()
    assertThat(bundleFile) {
      it.containsFileWithContent(
        "BUNDLE-METADATA/com.android.tools.build.bundletool/DeviceGroupConfig.json",
        config,
      )
    }

    ZipFile(bundleFile).use { zip ->
      val appBundle = AppBundle.buildFromZip(zip)

      val splitsConfigBuilder = Config.SplitsConfig.newBuilder()
      splitsConfigBuilder
          .addSplitDimensionBuilder()
          .setValue(Config.SplitDimension.Value.DEVICE_GROUP)
          .suffixStrippingBuilder
          .setEnabled(true)
          .setDefaultSuffix("test_group")
      assertThat(appBundle.bundleConfig.optimizations.splitsConfig)
          .isEqualTo(splitsConfigBuilder.build())
    }
  }

  @Test
  fun testDeviceTargetingConfig_notEnabled() {
    project
      .getSubproject(":app")
      .buildFile
      .appendText(
          """
          android {
            bundle {
              deviceTargetingConfig = file('src/main/config.json')
            }
          }
          """.trimIndent())
    val failure = project.executor().expectFailure().run(":app:bundleDebug")

    val exception = Throwables.getRootCause(failure.exception!!)
    assertThat(exception).hasMessageThat()
        .contains("deviceTargetingConfig is not enabled")
  }

  @Test
  fun testDeviceGroup_notEnabled() {
    project
      .getSubproject(":app")
      .buildFile
      .appendText(
          """
          android {
            bundle {
              deviceGroup {
                enableSplit = true
              }
            }
          }
          """.trimIndent())
    val failure = project.executor().expectFailure().run(":app:bundleDebug")

    val exception = Throwables.getRootCause(failure.exception!!)
    assertThat(exception).hasMessageThat()
        .contains("deviceGroup splits is not enabled")
  }

  @Test
  fun testDeviceTargetingConfig_notSpecified() {
    project.executor().run(":app:bundleDebug")

    val bundleFile = project.locateBundleFileViaModel("debug", ":app")

    assertThat(bundleFile).isNotNull()
    assertThat(bundleFile.toPath()).exists()
    assertThat(bundleFile) {
      it.doesNotContain(
        "BUNDLE-METADATA/com.android.tools.build.bundletool/DeviceGroupConfig.json"
      )
    }

    ZipFile(bundleFile).use { zip ->
      val appBundle = AppBundle.buildFromZip(zip)

      assertThat(appBundle.bundleConfig.optimizations.splitsConfig)
          .isEqualTo(Config.SplitsConfig.getDefaultInstance())
    }
  }
}
