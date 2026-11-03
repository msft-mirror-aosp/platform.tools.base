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

import com.android.sdklib.AndroidApiLevel
import com.android.sdklib.AndroidVersion
import com.android.tools.ui.inspector.client.ComposeVersionSupport.CanCrashTheApp
import com.android.tools.ui.inspector.client.ComposeVersionSupport.Supported
import com.android.tools.ui.inspector.client.ComposeVersionSupport.UnsupportedOnAndroid17
import com.google.common.truth.Truth.assertThat
import org.junit.Test

private val API_30 = AndroidVersion(AndroidApiLevel(30, 0), null, null, true)
private val API_36 = AndroidVersion(AndroidApiLevel(36, 0), null, null, true)
/** Android 16 QPR2. */
private val API_36_1 = AndroidVersion(AndroidApiLevel(36, 1), null, null, true)
private val CINNAMON_BUN_PREVIEW = AndroidVersion(AndroidApiLevel(36, 1), "CinnamonBun", null, true)
private val API_37 = AndroidVersion(AndroidApiLevel(37, 0), null, null, true)
private val API_37_2 = AndroidVersion(AndroidApiLevel(37, 2), null, null, true)
private val API_38 = AndroidVersion(AndroidApiLevel(38, 0), null, null, true)

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
    val android14Preview = AndroidVersion(AndroidApiLevel(33, 0), "UpsideDownCake", null, true)
    for (device in listOf(API_30, API_36, API_36_1, android14Preview)) {
      for (version in listOf("1.0.0", "1.2.1", "1.3.0", "1.9.0", "1.10.6", "1.11.0-alpha06")) {
        assertThat(getComposeVersionSupport(version, device)).isEqualTo(Supported)
      }
    }
  }

  @Test
  fun android17_isDetectedLikeInAndroidStudio() {
    val isSupported = { device: AndroidVersion -> getComposeVersionSupport("1.10.6", device) == Supported }
    // The Android 17 release, beta 1, beta 3 and a canary, as their emulator system images report them.
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(37, 2), null, null, true))).isFalse()
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(37, 2), "CinnamonBun", null, true).withBetaNumber(1))).isFalse()
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(37, 2), "DEV", null, true).withBetaNumber(3))).isFalse()
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(37, 2), "CANARY", null, true).withCanaryNumber(20261007))).isFalse()
    // Android Studio compares with API level 36.1 and the codename CinnamonBun: at API level 36.1, a release comes before a codename, and
    // the codenames are compared as text.
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(36, 1), "CinnamonBun", null, true))).isFalse()
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(36, 1), "DEV", null, true))).isFalse()
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(36, 2), null, null, true))).isFalse()
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(36, 1), null, null, true))).isTrue()
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(36, 1), "CANARY", null, true))).isTrue()
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(36, 0), "CinnamonBun", null, true))).isTrue()
    // The beta numbers don't count: a beta at API level 36.1 with the codename CinnamonBun is compared as the CinnamonBun preview.
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(36, 1), "CinnamonBun", null, true).withBetaNumber(1))).isFalse()
    // A canary has the codename CANARY, whatever ro.build.version.codename says, so one at API level 36.1 is not refused.
    assertThat(isSupported(AndroidVersion(AndroidApiLevel(36, 1), "CinnamonBun", null, true).withCanaryNumber(20251201))).isTrue()
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
