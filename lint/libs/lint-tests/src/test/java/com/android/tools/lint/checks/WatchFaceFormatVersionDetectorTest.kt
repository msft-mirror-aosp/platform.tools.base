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

import com.android.SdkConstants.WATCH_FACE_FORMAT_VERSION_PROPERTY
import com.android.tools.lint.checks.infrastructure.TestFile

class WatchFaceFormatVersionDetectorTest : AbstractCheckTest() {
  override fun getDetector() = WatchFaceFormatVersionDetector()

  fun testDocumentationExample() {
    lint()
      .files(manifestWithoutProperty(), declarativeWatchFaceFile())
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
        manifestWithoutProperty()
        // no DWF file
      )
      .run()
      .expectClean()
  }

  fun `test the WFF version property is set`() {
    lint().files(manifestWith(watchFaceFormatVersionProperty(value = "1")), declarativeWatchFaceFile()).run().expectClean()
  }

  fun `test the WFF version property value is missing`() {
    lint()
      .files(
        manifestWith(watchFaceFormatVersionProperty(value = null))
        // this should work even when there is no DWF file
      )
      .run()
      .expect(
        """
          AndroidManifest.xml:9: Error: The android:value attribute is missing [WatchFaceFormatMissingVersion]
                  <property android:name="$WATCH_FACE_FORMAT_VERSION_PROPERTY" />
                   ~~~~~~~~
          1 error
      """
          .trimIndent()
      )
      .expectFixDiffs(
        """
        Fix for AndroidManifest.xml line 9: Set value="1":
        @@ -13 +13
        -         <property android:name="com.google.wear.watchface.format.version" />
        +         <property
        +             android:name="com.google.wear.watchface.format.version"
        +             android:value="1" />
        """
          .trimIndent()
      )
  }

  fun `test the WFF version property value is invalid`() {
    lint()
      .files(
        manifestWith(watchFaceFormatVersionProperty(value = "invalid"))
        // this should work even when there is no DWF file
      )
      .run()
      .expect(
        """
        AndroidManifest.xml:9: Error: The Watch Face Format version is invalid [WatchFaceFormatInvalidVersion]
                <property android:name="com.google.wear.watchface.format.version" android:value="invalid" />
                                                                                  ~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  // Regression test for b/423518025
  fun `test the WFF version is valid when using manifest placeholders`() {
    lint()
      .files(
        manifestWith(watchFaceFormatVersionProperty(value = "\${wff_version}")),
        gradle(
            """
                android {
                    flavorDimensions "wff_version"
                    productFlavors {
                        wff1 {
                            dimension = "wff_version"
                            manifestPlaceholders = [ wff_version:"1"]
                        }
                    }
                }
                """
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  // Regression test for b/423518025
  fun `test an error is reported when the placeholder value is invalid`() {
    lint()
      .files(
        manifestWith(watchFaceFormatVersionProperty(value = "\${wff_version}")),
        gradle(
            """
                android {
                    flavorDimensions "wff_version"
                    productFlavors {
                        wff1 {
                            dimension = "wff_version"
                            manifestPlaceholders = [ wff_version:"invalid"]
                        }
                    }
                }
                """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/main/AndroidManifest.xml:9: Error: The Watch Face Format version is invalid [WatchFaceFormatInvalidVersion]
                <property android:name="com.google.wear.watchface.format.version" android:value="＄{wff_version}" />
                                                                                  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  // Regression test for b/423518025
  fun `test no error is reported when the placeholder does not resolve`() {
    lint().files(manifestWith(watchFaceFormatVersionProperty(value = "\${wff_version}"))).run().expectClean()
  }

  fun `test WFF version 1 is accepted when minSdk is 30`() {
    lint()
      .files(manifestWith(watchFaceFormatVersionProperty(value = "1"), minSdkVersion = "30"), declarativeWatchFaceFile())
      .run()
      .expectClean()
  }

  fun `test WFF version 1 reports error when minSdk is less than 30`() {
    lint()
      .files(manifestWith(watchFaceFormatVersionProperty(value = "1"), minSdkVersion = "29"))
      .run()
      .expect(
        """
        AndroidManifest.xml:9: Error: Watch Face Format version 1 requires a minSdkVersion of at least 33 [WatchFaceFormatRequiresHigherMinSdk]
                <property android:name="com.google.wear.watchface.format.version" android:value="1" />
                                                                                  ~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun `test WFF version 2 reports error when minSdk is 33`() {
    lint()
      .files(manifestWith(watchFaceFormatVersionProperty(value = "2"), minSdkVersion = "33"))
      .run()
      .expect(
        """
        AndroidManifest.xml:9: Error: Watch Face Format version 2 requires a minSdkVersion of at least 34 [WatchFaceFormatRequiresHigherMinSdk]
                <property android:name="com.google.wear.watchface.format.version" android:value="2" />
                                                                                  ~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
      .expectFixDiffs(
        """
        Fix for AndroidManifest.xml line 9: Lower version to 1:
        @@ -15 +15 @@
        -            android:value="2" />
        +            android:value="1" />
        """
          .trimIndent()
      )
  }

  fun `test WFF version 5 reports error when minSdk is 34`() {
    lint()
      .files(manifestWith(watchFaceFormatVersionProperty(value = "5"), minSdkVersion = "34"))
      .run()
      .expect(
        """
        AndroidManifest.xml:9: Error: Watch Face Format version 5 requires a minSdkVersion of at least 37 [WatchFaceFormatRequiresHigherMinSdk]
                <property android:name="com.google.wear.watchface.format.version" android:value="5" />
                                                                                  ~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
      .expectFixDiffs(
        """
        Fix for AndroidManifest.xml line 9: Lower version to 2:
        @@ -15 +15 @@
        -            android:value="5" />
        +            android:value="2" />
        """
          .trimIndent()
      )
  }

  fun `test WFF version 5 is clean when minSdk is 37`() {
    lint()
      .files(manifestWith(watchFaceFormatVersionProperty(value = "5"), minSdkVersion = "37"), declarativeWatchFaceFile())
      .run()
      .expectClean()
  }

  fun `test placeholder WFF version reports error when minSdk is too low`() {
    lint()
      .files(
        manifestWith(watchFaceFormatVersionProperty(value = "\${wff_version}"), minSdkVersion = "34"),
        gradle(
            """
                android {
                    flavorDimensions "wff_version"
                    productFlavors {
                        wff5 {
                            dimension = "wff_version"
                            manifestPlaceholders = [ wff_version:"5"]
                        }
                    }
                }
                """
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/main/AndroidManifest.xml:9: Error: Watch Face Format version 5 requires a minSdkVersion of at least 37 [WatchFaceFormatRequiresHigherMinSdk]
                <property android:name="com.google.wear.watchface.format.version" android:value="＄{wff_version}" />
                                                                                  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  fun `test error is reported when minSdk is not specified`() {
    lint()
      .files(manifestWith(watchFaceFormatVersionProperty(value = "2"), minSdkVersion = null))
      .run()
      .expect(
        """
        AndroidManifest.xml:9: Error: Watch Face Format version 2 requires a minSdkVersion of at least 34 [WatchFaceFormatRequiresHigherMinSdk]
                <property android:name="com.google.wear.watchface.format.version" android:value="2" />
                                                                                  ~~~~~~~~~~~~~~~~~
        1 error
        """
          .trimIndent()
      )
  }

  private fun declarativeWatchFaceFile() =
    xml(
      "res/raw/watch_face.xml",
      """
          <WatchFace />
        """,
    )

  private fun manifestWithoutProperty() = manifestWith(null)

  private fun manifestWith(watchFaceFormatVersionProperty: String?, minSdkVersion: String? = "33"): TestFile =
    manifest(
        """
          <manifest xmlns:android="http://schemas.android.com/apk/res/android"
              package="test.pkg">
              ${minSdkVersion?.let { "<uses-sdk android:minSdkVersion=\"$it\" />" } ?: ""}
              <uses-feature android:name="android.hardware.type.watch" />
              <application
                  android:icon="@mipmap/ic_launcher"
                  android:label="@string/app_name"
                  android:hasCode="false">
                  ${watchFaceFormatVersionProperty ?: ""}
              </application>
          </manifest>
        """
      )
      .indented()

  private fun watchFaceFormatVersionProperty(value: String? = "1") =
    "<property android:name=\"$WATCH_FACE_FORMAT_VERSION_PROPERTY\" ${value?.let { "android:value=\"$value\" " } ?: ""}/>"
}
