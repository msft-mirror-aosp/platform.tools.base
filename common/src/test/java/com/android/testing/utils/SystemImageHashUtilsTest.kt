/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.testing.utils

import kotlin.test.assertEquals
import org.junit.Test

class SystemImageHashUtilsTest {

  @Test
  fun testComputeSystemImageHashFromDsl() {
    // in earlier versions if minor == 0, then it should not be included in the hash.
    var expectedHash = "system-images;android-29;default;x86"
    var computedHash = computeSystemImageHashFromDsl(29, 0, null, "aosp", "", "x86")

    assertEquals(expectedHash, computedHash)

    expectedHash = "system-images;android-30;google_apis;x86_64"
    computedHash = computeSystemImageHashFromDsl(30, 0, null, "google", "", "x86_64")

    assertEquals(expectedHash, computedHash)

    expectedHash = "system-images;android-31;android-wear;x86_64"
    computedHash = computeSystemImageHashFromDsl(31, 0, null, "android-wear", "", "x86_64")

    assertEquals(expectedHash, computedHash)

    expectedHash = "system-images;android-31;google_apis_ps16k;arm64-v8a"
    computedHash = computeSystemImageHashFromDsl(31, 0, null, "google", "_ps16k", "arm64-v8a")

    assertEquals(expectedHash, computedHash)

    expectedHash = "system-images;android-37.1;google_apis;arm64-v8a"
    computedHash = computeSystemImageHashFromDsl(37, 1, null, "google", "", "arm64-v8a")

    assertEquals(expectedHash, computedHash)

    expectedHash = "system-images;android-45.7-ext100;google_apis;arm64-v8a"
    computedHash = computeSystemImageHashFromDsl(45, 7, 100, "google", "", "arm64-v8a")

    assertEquals(expectedHash, computedHash)

    // starting in 37, minor version should be included regardless of value.
    expectedHash = "system-images;android-37.0;google_apis;arm64-v8a"
    computedHash = computeSystemImageHashFromDsl(37, 0, null, "google", "", "arm64-v8a")

    assertEquals(expectedHash, computedHash)
  }

  @Test
  fun testGetPageAlignmentSuffix() {
    assertEquals(null, getPageAlignmentSuffix("google_apis"))

    assertEquals(null, getPageAlignmentSuffix("default"))

    assertEquals("_ps16k", getPageAlignmentSuffix("google_apis_ps16k"))

    // if we end up supporting other page sizes in the future
    assertEquals("_ps32k", getPageAlignmentSuffix("default_ps32k"))
  }

  @Test
  fun testParseApiFromHash() {
    assertEquals(29, parseApiFromHash("system-images;android-29;default;x86"))
    assertEquals(24, parseApiFromHash("system-images;android-24;default;arm64-v8a"))
    assertEquals(30, parseApiFromHash("system-images;android-30;google_apis_playstore;x86_64"))
    assertEquals(30, parseApiFromHash("system-images;android-30-ext12;default;x86_64"))
    assertEquals(300, parseApiFromHash("system-images;android-300.5;default;x86_64"))
  }

  @Test
  fun testParseMinorApiFromHash() {
    assertEquals(0, parseMinorApiFromHash("system-images;android-35;default;x86_64"))
    assertEquals(0, parseMinorApiFromHash("system-images;android-35-ext17;default;x86_64"))
    assertEquals(1, parseMinorApiFromHash("system-images;android-37.1;default;x86_64"))
    assertEquals(2, parseMinorApiFromHash("system-images;android-300.2-ext1000;default;x86_64"))
  }

  @Test
  fun testParseExtensionFromHash() {
    assertEquals(null, parseExtensionFromHash("system-images;android-29;default;x86"))
    assertEquals(9, parseExtensionFromHash("system-images;android-29-ext9;google_apis;x86"))
    assertEquals(12, parseExtensionFromHash("system-images;android-30-ext12;default;x86_64"))
  }

  @Test
  fun testParseSystemImageSource() {
    assertEquals("google_apis_playstore", parseSystemImageSourceFromHash("system-images;android-30;google_apis_playstore;x86_64"))
    assertEquals("google_apis_playstore", parseSystemImageSourceFromHash("system-images;android-36;google_apis_playstore_ps16k;x86_64"))
  }

  @Test
  fun testParseVendor() {
    assertEquals("google_apis_playstore", parseVendorFromHash("system-images;android-30;google_apis_playstore;x86_64"))
    // Vendor string will include page size suffix.
    assertEquals("google_apis_playstore_ps16k", parseVendorFromHash("system-images;android-36;google_apis_playstore_ps16k;x86_64"))
  }
}
