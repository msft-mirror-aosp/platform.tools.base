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
import com.android.bundle.DeviceGroup
import com.android.bundle.DeviceGroupConfig
import com.android.bundle.DeviceId
import com.android.bundle.DeviceRam
import com.android.bundle.DeviceSelector
import com.android.testutils.truth.PathSubject.assertThat
import com.android.testutils.truth.ZipFileSubject.assertThat
import com.android.tools.build.bundletool.model.AppBundle
import com.google.common.base.Throwables
import com.google.common.truth.Truth.assertThat
import java.util.zip.ZipFile
import org.junit.Rule
import org.junit.Test

class DeviceTargetingConfigPackageBundleTaskTest {

  private val app = MinimalSubProject.app("com.example.test").withFile("src/main/config.xml", CONFIG_XML)

  @get:Rule val project = GradleTestProject.builder().fromTestApp(MultiModuleTestProject.builder().subproject(":app", app).build()).create()

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
            deviceTargetingConfig = file('src/main/config.xml')
          }
        }
        """
          .trimIndent()
      )
    project.executor().with(BooleanOption.ENABLE_DEVICE_TARGETING_CONFIG_API, true).run(":app:bundleDebug")

    val bundleFile = project.locateBundleFileViaModel("debug", ":app")

    assertThat(bundleFile).isNotNull()
    assertThat(bundleFile.toPath()).exists()

    ZipFile(bundleFile).use { zip ->
      val configEntry = zip.getEntry(METADATA_ENTRY)
      val configProto = DeviceGroupConfig.parseFrom(zip.getInputStream(configEntry))
      assertThat(configProto).isEqualTo(EXPECTED_PROTO)

      val appBundle = AppBundle.buildFromZip(zip)

      val splitsConfigBuilder = Config.SplitsConfig.newBuilder()
      splitsConfigBuilder
        .addSplitDimensionBuilder()
        .setValue(Config.SplitDimension.Value.DEVICE_GROUP)
        .suffixStrippingBuilder
        .setEnabled(true)
        .setDefaultSuffix("test_group")
      assertThat(appBundle.bundleConfig.optimizations.splitsConfig).isEqualTo(splitsConfigBuilder.build())
    }
  }

  @Test
  fun testDeviceTargetingConfig_inconsistent() {
    project
      .getSubproject(":app")
      .buildFile
      .appendText(
        """
        android {
          bundle {
            deviceGroup {
              enableSplit = true
              defaultGroup = 'UNKNOWN_group'
            }
            deviceTargetingConfig = file('src/main/config.xml')
          }
        }
        """
          .trimIndent()
      )
    val failure = project.executor().with(BooleanOption.ENABLE_DEVICE_TARGETING_CONFIG_API, true).expectFailure().run(":app:bundleDebug")

    val exception = Throwables.getRootCause(failure.exception!!)
    assertThat(exception).hasMessageThat().contains("device group [UNKNOWN_group] which is not in the list [test_group, other]")
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
            deviceTargetingConfig = file('src/main/config.xml')
          }
        }
        """
          .trimIndent()
      )
    val failure = project.executor().expectFailure().run(":app:bundleDebug")

    val exception = Throwables.getRootCause(failure.exception!!)
    assertThat(exception).hasMessageThat().contains("deviceTargetingConfig is not enabled")
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
        """
          .trimIndent()
      )
    val failure = project.executor().expectFailure().run(":app:bundleDebug")

    val exception = Throwables.getRootCause(failure.exception!!)
    assertThat(exception).hasMessageThat().contains("deviceGroup splits is not enabled")
  }

  @Test
  fun testDeviceTargetingConfig_notSpecified() {
    project.executor().run(":app:bundleDebug")

    val bundleFile = project.locateBundleFileViaModel("debug", ":app")

    assertThat(bundleFile).isNotNull()
    assertThat(bundleFile.toPath()).exists()
    assertThat(bundleFile) { it.doesNotContain(METADATA_ENTRY) }

    ZipFile(bundleFile).use { zip ->
      val appBundle = AppBundle.buildFromZip(zip)

      assertThat(appBundle.bundleConfig.optimizations.splitsConfig).isEqualTo(Config.SplitsConfig.getDefaultInstance())
    }
  }

  companion object {
    private val METADATA_ENTRY = "BUNDLE-METADATA/com.android.tools.build.bundletool/DeviceGroupConfig.pb"

    private val CONFIG_XML =
      """
      <config:device-targeting-config
        xmlns:config="http://schemas.android.com/apk/config">

        <config:device-group name="test_group">
          <config:device-selector ram-min-bytes="12345678">
            <config:included-device-id brand="google" device="husky"/>
          </config:device-selector>
        </config:device-group>

      </config:device-targeting-config>
      """

    private val EXPECTED_PROTO =
      DeviceGroupConfig.newBuilder()
        .addDeviceGroups(
          DeviceGroup.newBuilder()
            .setName("test_group")
            .addDeviceSelectors(
              DeviceSelector.newBuilder()
                .setDeviceRam(DeviceRam.newBuilder().setMinBytes(12345678L))
                .addIncludedDeviceIds(DeviceId.newBuilder().setBuildBrand("google").setBuildDevice("husky"))
            )
        )
        .build()
  }
}
