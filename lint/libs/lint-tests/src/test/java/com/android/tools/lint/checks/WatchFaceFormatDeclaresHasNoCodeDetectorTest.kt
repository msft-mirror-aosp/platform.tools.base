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
package com.android.tools.lint.checks

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestMode

class WatchFaceFormatDeclaresHasNoCodeDetectorTest : AbstractCheckTest() {
  override fun getDetector() = WatchFaceFormatDeclaresHasNoCodeDetector()

  fun testDocumentationExample() {
    lint()
      .files(manifestWithoutHasCodeAttribute())
      .run()
      .expect(
        """
        AndroidManifest.xml:7: Error: Applications using Watch Face Format must declare hasCode=false [WatchFaceFormatDeclaresHasNoCode]
            <application
             ~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
      .expectFixDiffs(
        """
        Fix for AndroidManifest.xml line 7: Set hasCode="false":
        @@ -10 +10
        +         android:hasCode="false"
        """
          .trimIndent()
      )
  }

  fun `test hasCode declares 'true' with literal`() {
    lint()
      .files(manifestWithHasCodeAttribute("true"))
      .run()
      .expect(
        """
        AndroidManifest.xml:10: Error: Applications using Watch Face Format must declare hasCode=false [WatchFaceFormatDeclaresHasNoCode]
                android:hasCode="true">
                ~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
      .expectFixDiffs(
        """
        Fix for AndroidManifest.xml line 10: Set hasCode="false":
        @@ -10 +10
        -         android:hasCode="true"
        +         android:hasCode="false"
        """
          .trimIndent()
      )
  }

  fun `test hasCode declares 'true' through resource value`() {
    lint()
      .files(
        xml(
          "res/values/bools.xml",
          """
        <resources>
            <bool name="editable">true</bool>
        </resources>
      """,
        ),
        manifestWithHasCodeAttribute("@bool/editable"),
      )
      .testModes(TestMode.DEFAULT)
      .run()
      .expect(
        """
        AndroidManifest.xml:10: Error: Applications using Watch Face Format must declare hasCode=false [WatchFaceFormatDeclaresHasNoCode]
                android:hasCode="@bool/editable">
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
      .expectFixDiffs(
        """
        Fix for AndroidManifest.xml line 10: Set hasCode="false":
        @@ -10 +10
        -         android:hasCode="@bool/editable"
        +         android:hasCode="false"
        """
          .trimIndent()
      )
  }

  fun `test hasCode declares 'true' through one of multiple resource values`() {
    lint()
      .files(
        xml(
          "res/values/bools.xml",
          """
        <resources>
            <bool name="editable">false</bool>
        </resources>
      """,
        ),
        xml(
          "res/values-v21/bools.xml",
          """
        <resources>
            <bool name="editable">true</bool>
        </resources>
      """,
        ),
        manifestWithHasCodeAttribute("@bool/editable"),
      )
      .testModes(TestMode.DEFAULT)
      .run()
      .expect(
        """
        AndroidManifest.xml:10: Error: Applications using Watch Face Format must declare hasCode=false [WatchFaceFormatDeclaresHasNoCode]
                android:hasCode="@bool/editable">
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
      .expectFixDiffs(
        """
        Fix for AndroidManifest.xml line 10: Set hasCode="false":
        @@ -10 +10
        -         android:hasCode="@bool/editable"
        +         android:hasCode="false"
        """
          .trimIndent()
      )
  }

  fun `test no issue is reported when there is no watch face version and DWF file`() {
    lint().files(manifestWithHasCodeAttribute("true", withWatchFaceVersion = false)).run().expectClean()
  }

  fun `test an issue is reported when a watch face file exists and the watch face version is not declared`() {
    lint()
      .files(
        manifestWithHasCodeAttribute("true", withWatchFaceVersion = false),
        xml(
          "res/raw/watch_face.xml",
          """
          <WatchFace />
        """,
        ),
      )
      .testModes(TestMode.DEFAULT)
      .run()
      .expect(
        """
        AndroidManifest.xml:10: Error: Applications using Watch Face Format must declare hasCode=false [WatchFaceFormatDeclaresHasNoCode]
                android:hasCode="true">
                ~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
      .expectFixDiffs(
        """
        Fix for AndroidManifest.xml line 10: Set hasCode="false":
        @@ -10 +10
        -         android:hasCode="true"
        +         android:hasCode="false"
        """
          .trimIndent()
      )
  }

  fun `test hasCode declares 'false' with literal`() {
    lint().files(manifestWithHasCodeAttribute("false")).run().expectClean()
  }

  fun `test hasCode declares 'false' through resource value`() {
    lint()
      .files(
        xml(
          "res/values/bools.xml",
          """
        <resources>
            <bool name="editable">false</bool>
        </resources>
      """,
        ),
        manifestWithHasCodeAttribute("@bool/editable"),
      )
      .testModes(TestMode.DEFAULT)
      .run()
      .expectClean()
  }

  fun `test hasCode declares 'false' through multiple resource values`() {
    lint()
      .files(
        xml(
          "res/values/bools.xml",
          """
        <resources>
            <bool name="editable">false</bool>
        </resources>
      """,
        ),
        xml(
          "res/values-v21/bools.xml",
          """
        <resources>
            <bool name="editable">false</bool>
        </resources>
      """,
        ),
        manifestWithHasCodeAttribute("@bool/editable"),
      )
      .testModes(TestMode.DEFAULT)
      .run()
      .expectClean()
  }

  private fun manifestWithoutHasCodeAttribute() =
    manifest(
      // language=XML
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
          package="test.pkg">

          <uses-sdk android:minSdkVersion="33" />
          <uses-feature android:name="android.hardware.type.watch" />
          <application
              android:icon="@mipmap/ic_launcher"
              android:label="@string/app_name">
              <property
                  android:name="com.google.wear.watchface.format.version"
                  android:value="1" />
          </application>
      </manifest>
      """
        .trimIndent()
    )

  private fun manifestWithHasCodeAttribute(hasCode: String, withWatchFaceVersion: Boolean = true): TestFile {
    val watchFaceVersionProperty =
      if (withWatchFaceVersion) {
        // language=XML
        """
        <property
            android:name="com.google.wear.watchface.format.version"
            android:value="1" />
      """
      } else ""
    return manifest(
      // language=XML
      """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
            package="test.pkg">

            <uses-sdk android:minSdkVersion="33" />
            <uses-feature android:name="android.hardware.type.watch" />
            <application
                android:icon="@mipmap/ic_launcher"
                android:label="@string/app_name"
                android:hasCode="$hasCode">
                $watchFaceVersionProperty
            </application>
        </manifest>
      """
        .trimIndent()
    )
  }
}
