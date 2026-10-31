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

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceMetadataTest {

  @Test
  fun command_readsAbiApiLevelsAndCodename() {
    assertThat(DEVICE_METADATA_SHELL_COMMAND)
      .isEqualTo(
        "getprop ro.product.cpu.abi && getprop ro.build.version.sdk && getprop ro.build.version.sdk_full && " +
          "getprop ro.build.version.codename"
      )
  }

  // Unless stated otherwise, the outputs below are what Android emulators print for DEVICE_METADATA_SHELL_COMMAND. getprop prints an empty
  // line for a property the device doesn't have, like ro.build.version.sdk_full before Android 16, and exits successfully.

  @Test
  fun android10Release() {
    assertThat(parseDeviceMetadata("x86_64\n29\n\nREL\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidDeviceVersion(apiLevel = 29, minorApiLevel = 0, codename = null)))
  }

  @Test
  fun android14Preview() {
    // The UpsideDownCake preview reported the API level of Android 13 with its codename.
    assertThat(parseDeviceMetadata("x86_64\n33\n\nUpsideDownCake\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidDeviceVersion(apiLevel = 33, minorApiLevel = 0, codename = "UpsideDownCake")))
  }

  @Test
  fun android15Release() {
    assertThat(parseDeviceMetadata("x86_64\n35\n\nREL\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidDeviceVersion(apiLevel = 35, minorApiLevel = 0, codename = null)))
  }

  @Test
  fun android16Release() {
    // Android 16 has ro.build.version.sdk_full, with minor version 0.
    assertThat(parseDeviceMetadata("x86_64\n36\n36.0\nREL\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 0, codename = null)))
  }

  @Test
  fun android16Qpr2Release() {
    assertThat(parseDeviceMetadata("x86_64\n36\n36.1\nREL\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 1, codename = null)))
  }

  @Test
  fun android17Release() {
    assertThat(parseDeviceMetadata("x86_64\n37\n37.2\nREL\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidDeviceVersion(apiLevel = 37, minorApiLevel = 2, codename = null)))
  }

  @Test
  fun android17Preview() {
    // Not from a system image: the API level and codename of the CinnamonBun beta in Android Studio's AndroidVersionUtilTest.
    assertThat(parseDeviceMetadata("arm64-v8a\n36\n36.1\nCinnamonBun\n"))
      .isEqualTo(DeviceMetadata("arm64-v8a", AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 1, codename = "CinnamonBun")))
  }

  @Test
  fun carriageReturnsAndSpaces_areIgnored() {
    assertThat(parseDeviceMetadata("arm64-v8a\r\n36\r\n36.1\r\nCinnamonBun\r\n"))
      .isEqualTo(DeviceMetadata("arm64-v8a", AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 1, codename = "CinnamonBun")))
    assertThat(parseDeviceMetadata(" arm64-v8a \n 36 \n 36.1 \n CinnamonBun \n"))
      .isEqualTo(DeviceMetadata("arm64-v8a", AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 1, codename = "CinnamonBun")))
  }

  @Test
  fun missingLines_areMissingProperties() {
    // The lines for ro.build.version.sdk_full and ro.build.version.codename are optional.
    val expected = DeviceMetadata("arm64-v8a", AndroidDeviceVersion(apiLevel = 30, minorApiLevel = 0, codename = null))
    assertThat(parseDeviceMetadata("arm64-v8a\n30\n")).isEqualTo(expected)
    assertThat(parseDeviceMetadata("arm64-v8a\n30")).isEqualTo(expected)
    assertThat(parseDeviceMetadata("arm64-v8a\n36\n36.1\n"))
      .isEqualTo(DeviceMetadata("arm64-v8a", AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 1, codename = null)))
  }

  @Test
  fun extraLines_areIgnored() {
    assertThat(parseDeviceMetadata("x86_64\n37\n37.2\nREL\nsomething else\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidDeviceVersion(apiLevel = 37, minorApiLevel = 2, codename = null)))
  }

  @Test
  fun fullApiLevel_winsOverApiLevel() {
    // Like Android Studio, the API level comes from ro.build.version.sdk_full when the device has it.
    assertThat(parseDeviceMetadata("x86_64\n36\n37.0\nREL\n"))
      .isEqualTo(DeviceMetadata("x86_64", AndroidDeviceVersion(apiLevel = 37, minorApiLevel = 0, codename = null)))
  }

  @Test
  fun fullApiLevelWithoutMinorVersion_hasMinorVersion0() {
    assertThat(parseDeviceMetadata("arm64-v8a\n36\n36\nREL\n"))
      .isEqualTo(DeviceMetadata("arm64-v8a", AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 0, codename = null)))
  }

  @Test
  fun invalidFullApiLevel_isIgnored() {
    for (fullApiLevel in listOf("36.x", "36.1.0", "36.", ".1", "99999999999.1")) {
      assertThat(parseDeviceMetadata("arm64-v8a\n36\n$fullApiLevel\nREL\n"))
        .isEqualTo(DeviceMetadata("arm64-v8a", AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 0, codename = null)))
    }
  }

  @Test
  fun missingAbi_fails() {
    for (output in listOf("\n30\n\nREL\n", "", "\n")) {
      val exception = assertThrows(IllegalStateException::class.java) { parseDeviceMetadata(output) }
      assertThat(exception).hasMessageThat().isEqualTo("Failed to retrieve device CPU ABI.")
    }
  }

  @Test
  fun invalidApiLevel_fails() {
    for (output in listOf("arm64-v8a\n\n36.1\nREL\n", "arm64-v8a\n36.1\n36.1\nREL\n", "arm64-v8a\ninvalid_sdk\n", "arm64-v8a\n")) {
      val exception = assertThrows(IllegalStateException::class.java) { parseDeviceMetadata(output) }
      assertThat(exception).hasMessageThat().isEqualTo("Failed to retrieve device SDK API level.")
    }
  }
}
