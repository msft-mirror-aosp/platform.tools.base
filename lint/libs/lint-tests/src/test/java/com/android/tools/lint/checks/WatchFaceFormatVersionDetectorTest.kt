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

class WatchFaceFormatVersionDetectorTest : AbstractCheckTest() {
  override fun getDetector() = WatchFaceFormatVersionDetector()

  fun testDocumentationExample() {
    lint()
      .files(manifest(withWFFVersion = false), declarativeWatchFaceFile())
      .run()
      .expect(
        """
          AndroidManifest.xml:5: Error: The Watch Face Format version property must be set [WatchFaceFormatMissingVersion]
              <application
               ~~~~~~~~~~~
          1 error
      """
          .trimIndent()
      )
  }

  fun `test the WFF version property is not required when there are no declarative watch face files`() {
    lint()
      .files(
        manifest(withWFFVersion = false)
        // no DWF file
      )
      .run()
      .expectClean()
  }

  fun `test the WFF version is set`() {
    lint().files(manifest(withWFFVersion = true), declarativeWatchFaceFile()).run().expectClean()
  }

  private fun declarativeWatchFaceFile() =
    xml(
      "res/raw/watch_face.xml",
      """
          <WatchFace />
        """,
    )

  private fun manifest(withWFFVersion: Boolean): TestFile? {
    val watchFaceFormatVersionProperty =
      if (withWFFVersion) {
        // language=XML
        """
        <property
            android:name="com.google.wear.watchface.format.version"
            android:value="1" />
      """
      } else ""
    return manifest(
        """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="test.pkg">
                <uses-sdk android:minSdkVersion="33" />
                <uses-feature android:name="android.hardware.type.watch" />
                <application
                    android:icon="@mipmap/ic_launcher"
                    android:label="@string/app_name"
                    android:hasCode="false">
                    $watchFaceFormatVersionProperty
                </application>
            </manifest>
          """
      )
      .indented()
  }
}
