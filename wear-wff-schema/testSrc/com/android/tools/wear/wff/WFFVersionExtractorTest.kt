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
package com.android.tools.wear.wff

import com.android.tools.wear.wff.WFFVersion.WFFVersion1
import com.android.tools.wear.wff.WFFVersion.WFFVersion2
import com.android.tools.wear.wff.WFFVersion.WFFVersion3
import com.android.utils.XmlUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class WFFVersionExtractorTest {

  private val extractor = WFFVersionExtractor()

  @Test
  fun `extracts version from Manifest`() {
    assertEquals(WFFVersion1, extractor.extractFromManifest(manifestWithWFFVersion("1")))
    assertEquals(WFFVersion2, extractor.extractFromManifest(manifestWithWFFVersion("2")))
    assertEquals(WFFVersion3, extractor.extractFromManifest(manifestWithWFFVersion("3")))
  }

  @Test
  fun `defaults to null if the version is invalid`() {
    assertEquals(null, extractor.extractFromManifest(manifestWithWFFVersion("invalid version")))
  }

  @Test
  fun `returns null when there is no version`() {
    assertEquals(null, extractor.extractFromManifest(manifestWithoutWFFVersion()))
  }
}

private fun manifestWithWFFVersion(version: String) =
  XmlUtils.parseDocument(
    // language=XML
    """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.hardware.type.watch" />
    <application
        android:icon="@drawable/preview"
        android:label="@string/app_name"
        android:hasCode="false"
        >

        <meta-data
            android:name="com.google.android.wearable.standalone"
            android:value="true" />

        <property
            android:name="com.google.wear.watchface.format.version"
            android:value="$version" />
        <property
            android:name="com.google.wear.watchface.format.publisher"
            android:value="Test publisher" />

        <uses-library
            android:name="com.google.android.wearable"
            android:required="false" />
    </application>
</manifest>
        """
      .trimIndent(),
    true,
  )

private fun manifestWithoutWFFVersion() =
  XmlUtils.parseDocument(
    // language=XML
    """
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-feature android:name="android.hardware.type.watch" />
    <application
        android:icon="@drawable/preview"
        android:label="@string/app_name"
        >
        <meta-data
            android:name="com.google.android.wearable.standalone"
            android:value="true" />
        <uses-library
            android:name="com.google.android.wearable"
            android:required="false" />
    </application>
</manifest>
        """
      .trimIndent(),
    true,
  )
