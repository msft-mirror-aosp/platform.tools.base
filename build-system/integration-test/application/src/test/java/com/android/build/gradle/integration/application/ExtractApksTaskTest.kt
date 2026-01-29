/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.truth.TruthHelper.assertThat
import com.android.build.gradle.internal.scope.InternalArtifactType
import com.android.build.gradle.options.StringOption
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExtractApksTaskTest {

  @get:Rule val temporaryFolder = TemporaryFolder()

  @get:Rule
  val project =
    GradleRule.from {
      androidApplication {
        android {
          namespace = "com.example.multideployment"
          defaultConfig {
            versionCode = 1
            versionName = "1.0"
          }
          files {
            add(
              "src/main/res/values-hdpi/strings.xml",
              // language=xml
              """
              <resources>
                  <string name="density">I have a high density</string>
              </resources>

              """
                .trimIndent(),
            )
            add(
              "src/main/res/values-ldpi/strings.xml",
              // language=xml
              """
              <resources>
                  <string name="density">Im a low density device</string>
              </resources>

              """
                .trimIndent(),
            )
          }
        }
      }
    }

  @Test
  fun extractApkNoConfig() {
    val build = project.build

    val failure = build.executor.expectFailure().run(":app:extractApksFromBundleForDebug")
    failure.assertErrorContains("Calling ExtractApk with no device config")
  }

  @Test
  fun extractApkFailsWithoutTargetDeviceSpec() {
    val build = project.build

    val configInfo =
      """
      |{"sdk_version":30,"screen_density":160,
      |"supported_abis":["arm64-v8a"],
      |"supported_locales":["en"]}
      """
        .trimMargin()

    val spec = temporaryFolder.newFile("deviceSpec.json").apply { writeText(configInfo) }

    val result =
      build.executor
        .expectFailure()
        .with(StringOption.IDE_APK_SELECT_MULTIPLE_DEVICE_SPECS, spec.invariantSeparatorsPath)
        .run(":app:extractApksFromBundleForDebug")

    result.assertErrorContains("Calling ExtractApk with no device config")
  }

  @Test
  fun extractApkSingleConfig() {
    val build = project.build

    val apkSelectConfig =
      temporaryFolder.newFile("apkSelectConfig.json").apply {
        writeText(
          """
          |{"sdk_version":25,"screen_density":160,
          |"supported_abis":["x86_64","arm64-v8a"],
          |"supported_locales":["en"]}
          """
            .trimMargin()
        )
      }

    build.executor.with(StringOption.IDE_APK_SELECT_CONFIG, apkSelectConfig.absolutePath).run(":app:extractApksFromBundleForDebug")

    val extractedApks =
      build.androidApplication().resolve(InternalArtifactType.EXTRACTED_APKS).resolve("debug/extractApksFromBundleForDebug/").toFile()
    val deviceMap =
      build.androidApplication().resolve(InternalArtifactType.DEVICE_SPEC_PATH_MAP).resolve("debug/extractApksFromBundleForDebug").toFile()

    assertThat(extractedApks.exists()).isTrue()
    assertThat(extractedApks.resolve("base-master.apk").exists()).isTrue()
    assertThat(deviceMap.resolve("device_spec_path_map.txt").exists()).isTrue()
    assertThat(deviceMap.resolve("device_spec_path_map.txt").readLines()).containsExactly("${apkSelectConfig.invariantSeparatorsPath} 0")
  }

  @Test
  fun testSingleDeviceInMultipleDeviceSpecs() {
    val build = project.build

    val apkSelectConfig =
      temporaryFolder.newFile("apkSelectConfig.json").apply {
        writeText(
          """
          |{"sdk_version":25,"screen_density":160,
          |"supported_abis":["x86_64","arm64-v8a"],
          |"supported_locales":["en"]}
          """
            .trimMargin()
        )
      }

    build.executor
      .with(StringOption.IDE_APK_SELECT_CONFIG, apkSelectConfig.invariantSeparatorsPath)
      .with(StringOption.IDE_APK_SELECT_MULTIPLE_DEVICE_SPECS, apkSelectConfig.invariantSeparatorsPath)
      .run(":app:extractApksFromBundleForDebug")

    val extractedApks =
      build.androidApplication().resolve(InternalArtifactType.EXTRACTED_APKS).resolve("debug/extractApksFromBundleForDebug/").toFile()
    assertThat(extractedApks.resolve("base-master.apk").exists()).isTrue()

    val deviceMap =
      build.androidApplication().resolve(InternalArtifactType.DEVICE_SPEC_PATH_MAP).resolve("debug/extractApksFromBundleForDebug").toFile()
    assertThat(deviceMap.resolve("device_spec_path_map.txt").readLines()).containsExactly("${apkSelectConfig.invariantSeparatorsPath} 0")
  }

  @Test
  fun testMultipleDeviceConfigs() {
    val build = project.build

    val apkSelectConfig1 =
      temporaryFolder.newFile("apkSelectConfig_1.json").apply {
        writeText(
          """
          |{"sdk_version":25,"screen_density":160,
          |"supported_abis":["x86_64","arm64-v8a"],
          |"supported_locales":["en"]}
          """
            .trimMargin()
        )
      }

    val apkSelectConfig2 =
      temporaryFolder.newFile("apkSelectConfig_2.json").apply {
        writeText(
          """
          |{"sdk_version":34,"screen_density":240,
          |"supported_abis":["x86_64","arm64-v8a"],
          |"supported_locales":["en"]}
          """
            .trimMargin()
        )
      }

    val targetDeviceSpec =
      temporaryFolder.newFile("target-device-config.json").apply {
        writeText(
          """
          |{"sdk_version":25,"screen_density":240,
          |"supported_abis":["x86_64","arm64-v8a"],
          |"supported_locales":["en"]}
          """
            .trimMargin()
        )
      }

    build.executor
      .with(StringOption.IDE_APK_SELECT_CONFIG, targetDeviceSpec.invariantSeparatorsPath)
      .with(
        StringOption.IDE_APK_SELECT_MULTIPLE_DEVICE_SPECS,
        listOf(apkSelectConfig1.invariantSeparatorsPath, apkSelectConfig2.invariantSeparatorsPath).joinToString(","),
      )
      .run(":app:extractApksFromBundleForDebug")

    val extractedApks =
      build.androidApplication().resolve(InternalArtifactType.EXTRACTED_APKS).resolve("debug/extractApksFromBundleForDebug/").toFile()
    val deviceMap =
      build.androidApplication().resolve(InternalArtifactType.DEVICE_SPEC_PATH_MAP).resolve("debug/extractApksFromBundleForDebug").toFile()

    assertThat(extractedApks.resolve("0/base-mdpi.apk").exists()).isTrue()
    assertThat(extractedApks.resolve("1/base-hdpi_3.apk").exists()).isTrue()
    assertThat(deviceMap.resolve("device_spec_path_map.txt").readLines())
      .containsExactly("${apkSelectConfig1.invariantSeparatorsPath} 0", "${apkSelectConfig2.invariantSeparatorsPath} 1")
  }

  @Test
  fun testMultipleDuplicateDeviceConfigs() {
    val build = project.build
    build.androidApplication()

    val configInfo =
      """
      |{"sdk_version":30,"screen_density":160,
      |"supported_abis":["arm64-v8a"],
      |"supported_locales":["en"]}
      """
        .trimMargin()

    val apkSelectConfig1 = temporaryFolder.newFile("deviceSpec_1.json").apply { writeText(configInfo) }

    val apkSelectConfig2 = temporaryFolder.newFile("deviceSpec_2.json").apply { writeText(configInfo) }

    val targetDeviceSpec =
      temporaryFolder.newFile("target-device-config.json").apply {
        writeText(
          """
          |{"sdk_version":30,"screen_density":240,
          |"supported_abis":["x86_64","arm64-v8a"],
          |"supported_locales":["en"]}
          """
            .trimMargin()
        )
      }

    build.executor
      .with(StringOption.IDE_APK_SELECT_CONFIG, targetDeviceSpec.invariantSeparatorsPath)
      .with(
        StringOption.IDE_APK_SELECT_MULTIPLE_DEVICE_SPECS,
        "${apkSelectConfig1.invariantSeparatorsPath},${apkSelectConfig2.invariantSeparatorsPath}",
      )
      .run(":app:extractApksFromBundleForDebug")

    val extractedApks =
      build.androidApplication().resolve(InternalArtifactType.EXTRACTED_APKS).resolve("debug/extractApksFromBundleForDebug/").toFile()
    val deviceMap =
      build.androidApplication().resolve(InternalArtifactType.DEVICE_SPEC_PATH_MAP).resolve("debug/extractApksFromBundleForDebug").toFile()

    assertThat(extractedApks.exists()).isTrue()
    assertThat(extractedApks.resolve("0/base-mdpi_2.apk").exists()).isTrue()
    assertThat(deviceMap.resolve("device_spec_path_map.txt").exists()).isTrue()
    assertThat(deviceMap.resolve("device_spec_path_map.txt").readLines())
      .containsExactly("${apkSelectConfig1.invariantSeparatorsPath} 0", "${apkSelectConfig2.invariantSeparatorsPath} 0")
  }
}
