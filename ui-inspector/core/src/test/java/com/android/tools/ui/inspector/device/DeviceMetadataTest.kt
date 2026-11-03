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

package com.android.tools.ui.inspector.device

import com.android.sdklib.AndroidApiLevel
import com.android.sdklib.AndroidVersion
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceMetadataTest {

  @Test
  fun command_readsAbiApiLevelsCodenameAndPreviewSdk() {
    assertThat(DEVICE_METADATA_SHELL_COMMAND)
      .isEqualTo(
        "getprop ro.product.cpu.abi && getprop ro.build.version.sdk && getprop ro.build.version.sdk_full && " +
          "getprop ro.build.version.codename && getprop ro.build.version.preview_sdk"
      )
  }

  // Unless stated otherwise, the outputs below are what Android emulators print for DEVICE_METADATA_SHELL_COMMAND. getprop prints an empty
  // line for a property the device doesn't have, like ro.build.version.sdk_full before Android 16, and exits successfully.

  @Test
  fun android10Release() {
    assertThat(parseDeviceMetadata("x86_64\n29\n\nREL\n0\n")).isEqualTo(DeviceMetadata("x86_64", release(29)))
  }

  @Test
  fun android14Preview() {
    // The UpsideDownCake preview reported the API level of Android 13 with its codename. Its preview SDK version, 1, is an old-style one.
    assertThat(parseDeviceMetadata("x86_64\n33\n\nUpsideDownCake\n1\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidVersion(AndroidApiLevel(33, 0), "UpsideDownCake", null, true)))
  }

  @Test
  fun android15Release() {
    assertThat(parseDeviceMetadata("x86_64\n35\n\nREL\n0\n")).isEqualTo(DeviceMetadata("x86_64", release(35)))
  }

  @Test
  fun android16Release() {
    // Android 16 has ro.build.version.sdk_full, with minor version 0.
    assertThat(parseDeviceMetadata("x86_64\n36\n36.0\nREL\n0\n")).isEqualTo(DeviceMetadata("x86_64", release(36, 0)))
  }

  @Test
  fun android16Qpr2Release() {
    assertThat(parseDeviceMetadata("x86_64\n36\n36.1\nREL\n0\n")).isEqualTo(DeviceMetadata("x86_64", release(36, 1)))
  }

  @Test
  fun android17Release() {
    assertThat(parseDeviceMetadata("x86_64\n37\n37.2\nREL\n0\n")).isEqualTo(DeviceMetadata("x86_64", release(37, 2)))
  }

  @Test
  fun android17Beta1() {
    // Like in Android Studio, the API level of a beta comes from its preview SDK version: 37.2 beta 1 from 3721.
    assertThat(parseDeviceMetadata("x86_64\n37\n37.1\nCinnamonBun\n3721\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidVersion(AndroidApiLevel(37, 2), "CinnamonBun", null, true).withBetaNumber(1)))
  }

  @Test
  fun android17Beta3() {
    assertThat(parseDeviceMetadata("x86_64\n37\n37.1\nDEV\n3723\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidVersion(AndroidApiLevel(37, 2), "DEV", null, true).withBetaNumber(3)))
  }

  @Test
  fun android17Canary() {
    assertThat(parseDeviceMetadata("x86_64\n37\n37.2\nCANARY\n20261007\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidVersion(AndroidApiLevel(37, 2), "CANARY", null, true).withCanaryNumber(20261007)))
  }

  @Test
  fun carriageReturnsAndSpaces_areIgnored() {
    val expected = DeviceMetadata("x86_64", AndroidVersion(AndroidApiLevel(37, 2), "CinnamonBun", null, true).withBetaNumber(1))
    assertThat(parseDeviceMetadata("x86_64\r\n37\r\n37.1\r\nCinnamonBun\r\n3721\r\n")).isEqualTo(expected)
    assertThat(parseDeviceMetadata(" x86_64 \n 37 \n 37.1 \n CinnamonBun \n 3721 \n")).isEqualTo(expected)
  }

  @Test
  fun missingLines_areMissingProperties() {
    // Not from a system image: the lines after ro.build.version.sdk are optional.
    assertThat(parseDeviceMetadata("arm64-v8a\n30\n")).isEqualTo(DeviceMetadata("arm64-v8a", release(30)))
    assertThat(parseDeviceMetadata("arm64-v8a\n30")).isEqualTo(DeviceMetadata("arm64-v8a", release(30)))
    assertThat(parseDeviceMetadata("arm64-v8a\n36\n36.1\nCinnamonBun\n"))
      .isEqualTo(DeviceMetadata("arm64-v8a", AndroidVersion(AndroidApiLevel(36, 1), "CinnamonBun", null, true)))
    assertThat(parseDeviceMetadata("arm64-v8a\n36\n36.1\n")).isEqualTo(DeviceMetadata("arm64-v8a", release(36, 1)))
  }

  @Test
  fun extraLines_areIgnored() {
    // Not from a system image.
    assertThat(parseDeviceMetadata("x86_64\n37\n37.2\nREL\n0\nsomething else\n")).isEqualTo(DeviceMetadata("x86_64", release(37, 2)))
  }

  @Test
  fun fullApiLevel_winsOverApiLevel() {
    // Not from a system image: like in Android Studio, the API level comes from ro.build.version.sdk_full when the device has it.
    assertThat(parseDeviceMetadata("x86_64\n36\n37.0\nREL\n0\n")).isEqualTo(DeviceMetadata("x86_64", release(37, 0)))
  }

  @Test
  fun fullApiLevelWithoutMinorVersion_hasMinorVersion0() {
    // Not from a system image.
    assertThat(parseDeviceMetadata("arm64-v8a\n36\n36\nREL\n0\n")).isEqualTo(DeviceMetadata("arm64-v8a", release(36, 0)))
  }

  @Test
  fun invalidFullApiLevel_isIgnored() {
    // Not from a system image. Unlike Android Studio, which then uses AndroidVersion.DEFAULT or fails, the API level comes from
    // ro.build.version.sdk.
    for (fullApiLevel in listOf("36.x", "36.1.0", "36.", ".1", "36.1-suffix", "99999999999.1", "36.99999999999")) {
      assertThat(parseDeviceMetadata("arm64-v8a\n36\n$fullApiLevel\nREL\n0\n")).isEqualTo(DeviceMetadata("arm64-v8a", release(36, 0)))
    }
  }

  @Test
  fun apiLevelWithMinorVersion_isAccepted() {
    // Not from a system image. Like in Android Studio.
    assertThat(parseDeviceMetadata("arm64-v8a\n36.1\n\nREL\n0\n")).isEqualTo(DeviceMetadata("arm64-v8a", release(36, 1)))
  }

  @Test
  fun invalidCodename_isIgnored() {
    // Not from a system image. Android Studio's AndroidVersion rejects codenames that don't start with a letter or have other characters
    // than letters, digits, '_' and '-'.
    for (codename in listOf("1Bun", "Cinnamon Bun", "Cinnamon.Bun")) {
      assertThat(parseDeviceMetadata("x86_64\n36\n36.1\n$codename\n0\n")).isEqualTo(DeviceMetadata("x86_64", release(36, 1)))
    }
  }

  @Test
  fun invalidPreviewSdk_isIgnored() {
    // Not from a system image.
    for (previewSdk in listOf("", "x", "-1", "99999999999")) {
      assertThat(parseDeviceMetadata("x86_64\n37\n37.2\nREL\n$previewSdk\n")).isEqualTo(DeviceMetadata("x86_64", release(37, 2)))
    }
  }

  @Test
  fun invalidFullApiLevel_withPreviewSdk() {
    // Not from a system image: the canary keeps its codename and number, with the API level of ro.build.version.sdk.
    assertThat(parseDeviceMetadata("x86_64\n37\n37.x\nCANARY\n20261007\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidVersion(AndroidApiLevel(37, 0), "CANARY", null, true).withCanaryNumber(20261007)))
  }

  @Test
  fun missingAbi_fails() {
    for (output in listOf("\n30\n\nREL\n0\n", "", "\n")) {
      val exception = assertThrows(IllegalStateException::class.java) { parseDeviceMetadata(output) }
      assertThat(exception).hasMessageThat().isEqualTo("Failed to retrieve device CPU ABI.")
    }
  }

  @Test
  fun invalidApiLevel_fails() {
    // Not from a system image. ro.build.version.sdk must be an API level, even when ro.build.version.sdk_full is one.
    for (output in listOf("arm64-v8a\n\n36.1\nREL\n0\n", "arm64-v8a\ninvalid_sdk\n", "arm64-v8a\n99999999999\n", "arm64-v8a\n")) {
      val exception = assertThrows(IllegalStateException::class.java) { parseDeviceMetadata(output) }
      assertThat(exception).hasMessageThat().isEqualTo("Failed to retrieve device SDK API level.")
    }
  }

  private fun release(apiLevel: Int, minorApiLevel: Int = 0) = AndroidVersion(AndroidApiLevel(apiLevel, minorApiLevel), null, null, true)
}
