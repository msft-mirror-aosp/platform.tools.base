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

package com.android.tools.ui.inspector.client

import com.android.tools.ui.inspector.client.ComposeVersionSupport.CanCrashTheApp
import com.android.tools.ui.inspector.client.ComposeVersionSupport.Supported
import com.android.tools.ui.inspector.client.ComposeVersionSupport.UnsupportedOnAndroid17
import com.android.tools.ui.inspector.device.AndroidDeviceVersion
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private val API_30 = AndroidDeviceVersion(apiLevel = 30, minorApiLevel = 0, codename = null)
private val API_36 = AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 0, codename = null)
/** Android 16 QPR2. */
private val API_36_1 = AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 1, codename = null)
private val CINNAMON_BUN_PREVIEW = AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 1, codename = "CinnamonBun")
private val API_37 = AndroidDeviceVersion(apiLevel = 37, minorApiLevel = 0, codename = null)
private val API_37_2 = AndroidDeviceVersion(apiLevel = 37, minorApiLevel = 2, codename = null)
private val API_38 = AndroidDeviceVersion(apiLevel = 38, minorApiLevel = 0, codename = null)

class ComposeVersionSupportTest {

  @Test
  fun android17_composeBefore1_11_0_beta01_isNotSupported() {
    for (device in listOf(CINNAMON_BUN_PREVIEW, API_37, API_37_2, API_38)) {
      for (version in listOf("1.0.0", "1.9.0", "1.10.0", "1.10.6", "1.11.0-alpha01", "1.11.0-alpha06")) {
        assertThat(getComposeVersionSupport(version, device)).isEqualTo(UnsupportedOnAndroid17(version))
      }
    }
  }

  @Test
  fun android17_composeFrom1_11_0_beta01_isSupported() {
    for (device in listOf(CINNAMON_BUN_PREVIEW, API_37, API_37_2, API_38)) {
      for (version in listOf("1.11.0-beta01", "1.11.0-beta02", "1.11.0-rc01", "1.11.0", "1.11.1", "1.12.0-alpha01", "1.20.0", "2.0.0")) {
        assertThat(getComposeVersionSupport(version, device)).isEqualTo(Supported)
      }
    }
  }

  @Test
  fun beforeAndroid17_composeBefore1_11_isSupported() {
    // A preview reports the API level of the previous release with its codename: the Android 14 preview reports 33 and UpsideDownCake.
    val android14Preview = AndroidDeviceVersion(apiLevel = 33, minorApiLevel = 0, codename = "UpsideDownCake")
    for (device in listOf(API_30, API_36, API_36_1, android14Preview)) {
      for (version in listOf("1.0.0", "1.2.1", "1.3.0", "1.9.0", "1.10.6", "1.11.0-alpha06")) {
        assertThat(getComposeVersionSupport(version, device)).isEqualTo(Supported)
      }
    }
  }

  @Test
  fun android17_isApiLevel37AndLater_orTheCinnamonBunCodename() {
    val isSupported = { device: AndroidDeviceVersion -> getComposeVersionSupport("1.10.6", device) == Supported }
    // The Android 17 release, beta 1, betas 2 and 3, and the canaries, as their emulator system images report them.
    assertThat(isSupported(AndroidDeviceVersion(apiLevel = 37, minorApiLevel = 2, codename = null))).isFalse()
    assertThat(isSupported(AndroidDeviceVersion(apiLevel = 37, minorApiLevel = 1, codename = "CinnamonBun"))).isFalse()
    assertThat(isSupported(AndroidDeviceVersion(apiLevel = 37, minorApiLevel = 1, codename = "DEV"))).isFalse()
    assertThat(isSupported(AndroidDeviceVersion(apiLevel = 37, minorApiLevel = 2, codename = "CANARY"))).isFalse()
    // The Android 17 preview of Android Studio's tests, and any CinnamonBun build that reports an earlier API level.
    assertThat(isSupported(AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 1, codename = "CinnamonBun"))).isFalse()
    assertThat(isSupported(AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 0, codename = "CinnamonBun"))).isFalse()
    // Android 16 QPR2, and a canary at API level 36.1 like in Android Studio's tests.
    assertThat(isSupported(AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 1, codename = null))).isTrue()
    assertThat(isSupported(AndroidDeviceVersion(apiLevel = 36, minorApiLevel = 1, codename = "CANARY"))).isTrue()
  }

  @Test
  fun compose1_1_and1_2_before1_2_1_canCrashTheApp() {
    for (version in listOf("1.1.0-alpha01", "1.1.0", "1.1.1", "1.2.0-alpha01", "1.2.0-beta03", "1.2.0-rc03", "1.2.0")) {
      assertThat(getComposeVersionSupport(version, API_30)).isEqualTo(CanCrashTheApp(version, fixedVersion = "1.2.1"))
    }
    for (version in listOf("1.0.0", "1.0.5", "1.2.1", "1.2.2")) {
      assertThat(getComposeVersionSupport(version, API_30)).isEqualTo(Supported)
    }
  }

  @Test
  fun compose1_3_preReleases_canCrashTheApp() {
    for (version in listOf("1.3.0-alpha01", "1.3.0-alpha03", "1.3.0-beta01", "1.3.0-rc01")) {
      assertThat(getComposeVersionSupport(version, API_30)).isEqualTo(CanCrashTheApp(version, fixedVersion = "1.3.0"))
    }
    for (version in listOf("1.3.0", "1.3.3", "1.4.0-alpha01")) {
      assertThat(getComposeVersionSupport(version, API_30)).isEqualTo(Supported)
    }
  }

  @Test
  fun otherMajorVersions_withTheSameMinorVersions_areSupported() {
    // The rules are about 1.x: unlike Android Studio, other major versions are not refused.
    for (version in listOf("0.1.0", "0.2.0", "0.3.0-alpha01", "2.1.0", "2.2.0", "2.3.0-alpha01")) {
      assertThat(getComposeVersionSupport(version, API_30)).isEqualTo(Supported)
    }
  }

  @Test
  fun android17_takesPrecedence_overTheOtherProblems() {
    assertThat(getComposeVersionSupport("1.2.0", API_37)).isEqualTo(UnsupportedOnAndroid17("1.2.0"))
  }

  @Test
  fun locallyBuiltVersions_areNotChecked() {
    for (version in listOf("1.2.0-SNAPSHOT", "1.10.0-SNAPSHOT", "1.11.0-alpha01-SNAPSHOT", "1.10.0-snapshot", "1.10.0-dev")) {
      assertThat(getComposeVersionSupport(version, API_37)).isEqualTo(Supported)
    }
  }

  @Test
  fun versionsThatAreNotComposeReleases_areNotChecked() {
    // Including the development versions from before 1.0, like 0.1.0-dev17, which Android Studio refuses.
    for (version in listOf("", "1", "1.10", "1.10.0-custom", "1.10.0-alpha", "0.1.0-dev17", "v1.10.0", "1.10.0 ", "1.10.0-ALPHA01")) {
      assertThat(getComposeVersionSupport(version, API_37)).isEqualTo(Supported)
    }
  }

  @Test
  fun versionPartsAreComparedAsNumbers() {
    // As text, "1.9.0" would come after "1.11.0-beta01".
    assertThat(getComposeVersionSupport("1.9.0", API_37)).isEqualTo(UnsupportedOnAndroid17("1.9.0"))
  }

  @Test
  fun unsupportedOnAndroid17_message() {
    assertThat(UnsupportedOnAndroid17("1.10.6").message)
      .isEqualTo(
        "Compose version not supported: the app uses androidx.compose.ui:ui:1.10.6, whose Compose inspector is not supported on " +
          "Android 17 and later. Update Compose to 1.11.0 or later."
      )
  }

  @Test
  fun canCrashTheApp_message() {
    assertThat(CanCrashTheApp("1.2.0", fixedVersion = "1.2.1").message)
      .isEqualTo(
        "Compose version not supported: the app uses androidx.compose.ui:ui:1.2.0, whose Compose inspector can crash the app. " +
          "Update Compose to 1.2.1 or later."
      )
  }
}
