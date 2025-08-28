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

import com.android.SdkConstants.FD_DATA
import com.android.SdkConstants.FD_PLATFORMS
import com.android.SdkConstants.FN_PERMISSION_VERSIONS
import com.android.testutils.TestUtils
import com.android.tools.lint.checks.PurposeDeclarationDetector.Companion.MISSING_PURPOSE
import com.android.tools.lint.detector.api.Detector
import java.io.File
import org.intellij.lang.annotations.Language
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class PurposeDeclarationDetectorTest : AbstractCheckTest() {
  @get:Rule var tempFolder = TemporaryFolder()

  @Before
  fun clearCache() {
    PurposeDeclarationDetector.clearPermissionsMap()
  }

  override fun getDetector(): Detector = PurposeDeclarationDetector()

  companion object {
    private const val MOCK_SDK_DIR = "mock-sdk"

    @Language("XML")
    private val DEFAULT_MOCK_XML =
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
                <permission name="USE_FOO" requiresPurposeMin="37">
                    <valid-purpose name="validPurposeForSdk37+" min="37" />
                    <valid-purpose name="validPurposeForSdk38+" min="38" />
                </permission>
            </permissions>
        """
        .trimIndent()

    @Language("XML")
    private val EMPTY_MOCK_XML =
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
            </permissions>
        """
        .trimIndent()

    @Language("XML")
    private val MALFORMED_MOCK_XML =
      // USE_FOO is missing requiresPurposeMin and USE_BAR is missing min for purpose.
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
                <permission name="USE_FOO">
                    <valid-purpose name="validPurposeForSdk37+" min="37" />
                </permission>
                <permission name="USE_BAR" requiresPurposeMin="37">
                    <valid-purpose name="validPurposeForSdk37+" />
                </permission>
            </permissions>
        """
        .trimIndent()

    @Language("XML")
    private val MULTI_PERMISSIONS_MOCK_XML =
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
                <permission name="USE_FOO" requiresPurposeMin="37" requiresPurposeMax="38">
                    <valid-purpose name="fooValidPurpose1" min="37" max="37"/>
                    <valid-purpose name="fooValidPurpose2" min="38" />
                </permission>
                <permission name="USE_BAR" requiresPurposeMin="38">
                    <valid-purpose name="barValidPurpose1" min="38" />
                </permission>
            </permissions>
        """
        .trimIndent()
  }

  @Test
  fun testDocumentationExampleRequestingPermissionWithNoPurposeFail() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 37
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expect(
        """
        src/main/AndroidManifest.xml:4: Error: USE_FOO will not be granted due to missing <purpose>. Possible valid purposes: validPurposeForSdk37+, validPurposeForSdk38+ [MissingPurpose]
          <uses-permission android:name="USE_FOO" />
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testDocumentationExampleRequestingPermissionWithNoPurposeOnOldSdkFail() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 38
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 38
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="validPurposeForSdk38+" />
                </uses-permission>
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expect(
        """
        src/main/AndroidManifest.xml:4: Error: USE_FOO will not be granted on API level(s) 37 due to no valid <purpose>. Ensure valid purpose(s) cover all API level(s). Possible valid purposes: validPurposeForSdk37+, validPurposeForSdk38+ [MissingPurpose]
          <uses-permission android:name="USE_FOO">
          ^
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingUsesPermission23WithNoPurposeFail() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 37
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission-sdk-23 android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expect(
        """
        src/main/AndroidManifest.xml:4: Error: USE_FOO will not be granted due to missing <purpose>. Possible valid purposes: validPurposeForSdk37+, validPurposeForSdk38+ [MissingPurpose]
          <uses-permission-sdk-23 android:name="USE_FOO" />
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithValidPurposePass() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 37
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="validPurposeForSdk37+" />
                </uses-permission>
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithValidAndInvalidPurposePass() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 37
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="invalidPurpose" />
                  <purpose android:name="validPurposeForSdk37+" />
                </uses-permission>
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsWithNoPurposeUsingOldTargetSdkPass() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 36
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsOnOldSdkWithNoPurposePass() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 37
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO" android:maxSdkVersion="36" />
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsOnFewSdksUsingMinSdkVersionAttrWithNoPurposePass() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 38
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 38
                  }
              }
              """,
          )
          .indented(),
        // SDK 37 does not need purpose due to android:minSdkVersion="38"
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO" android:minSdkVersion="38">
                  <purpose android:name="validPurposeForSdk38+" />
                </uses-permission>
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsOnFewSdksUsingMinSdkVersionWithNoPurposePass() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 38
                  defaultConfig {
                      minSdkVersion 38
                      targetSdkVersion 38
                  }
              }
              """,
          )
          .indented(),
        // SDK 37 does not need purpose due to the app's minSdkVersion set to 38
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="validPurposeForSdk38+" />
                </uses-permission>
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsWithNoPurposeUsingMalformedXmlPass() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 37
                  }
              }
              """,
          )
          .indented(),
        // SDK 37 does not need purpose due to the app's minSdkVersion set to 38
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO" />
                <uses-permission android:name="USE_BAR" />
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(MALFORMED_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionRequiringNoPurposePass() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 37
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_BAR" />
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithNoPurposeOn38PlusFail() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 39
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 39
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="validPurposeForSdk37+" android:maxSdkVersion="37"  />
                </uses-permission>
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expect(
        """
        src/main/AndroidManifest.xml:4: Error: USE_FOO will not be granted on API level(s) 38-39 due to no valid <purpose>. Ensure valid purpose(s) cover all API level(s). Possible valid purposes: validPurposeForSdk37+, validPurposeForSdk38+ [MissingPurpose]
          <uses-permission android:name="USE_FOO">
          ^
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithComplexXmlFail() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 38
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 38
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="fooValidPurpose1" />
                </uses-permission>
                <uses-permission android:name="USE_BAR">
                  <purpose android:name="barValidPurpose1" android:minSdkVersion="39"  />
                </uses-permission>
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(MULTI_PERMISSIONS_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expect(
        """
        src/main/AndroidManifest.xml:4: Error: USE_FOO will not be granted on API level(s) 38 due to no valid <purpose>. Ensure valid purpose(s) cover all API level(s). Possible valid purposes: fooValidPurpose1, fooValidPurpose2 [MissingPurpose]
          <uses-permission android:name="USE_FOO">
          ^
        src/main/AndroidManifest.xml:7: Error: USE_BAR will not be granted on API level(s) 38 due to no valid <purpose>. Ensure valid purpose(s) cover all API level(s). Possible valid purposes: barValidPurpose1 [MissingPurpose]
          <uses-permission android:name="USE_BAR">
          ^
        2 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithComplexXmlPass() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 38
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 38
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="fooValidPurpose1" android:maxSdkVersion="37" />
                  <purpose android:name="fooValidPurpose2" android:minSdkVersion="38" />
                </uses-permission>
                <uses-permission android:name="USE_BAR">
                  <purpose android:name="barValidPurpose1" />
                </uses-permission>
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(MULTI_PERMISSIONS_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithMultipleValidPurposeIntervalsPass() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 42
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="validPurposeForSdk37+" android:minSdkVersion="37" android:maxSdkVersion="37" />
                  <purpose android:name="validPurposeForSdk37+" android:minSdkVersion="38" android:maxSdkVersion="41" />
                  <purpose android:name="validPurposeForSdk37+" android:minSdkVersion="42" android:maxSdkVersion="42" />
                </uses-permission>
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithMultipleOverlappedValidPurposeIntervalsPass() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 42
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="validPurposeForSdk37+" android:maxSdkVersion="34" />
                  <purpose android:name="validPurposeForSdk37+" android:minSdkVersion="32" android:maxSdkVersion="36" />
                  <purpose android:name="validPurposeForSdk37+" android:minSdkVersion="35" android:maxSdkVersion="41" />
                  <purpose android:name="validPurposeForSdk37+" android:minSdkVersion="40" />
                </uses-permission>
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithMultipleSdkRangesMissingPurposeFail() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 45
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="validPurposeForSdk37+" android:minSdkVersion="34" android:maxSdkVersion="36" />
                  <purpose android:name="validPurposeForSdk37+" android:minSdkVersion="39" android:maxSdkVersion="39" />
                  <purpose android:name="validPurposeForSdk37+" android:minSdkVersion="41" android:maxSdkVersion="42" />
                  <purpose android:name="invalidPurpose" android:minSdkVersion="43" />
                </uses-permission>
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expect(
        """
        src/main/AndroidManifest.xml:4: Error: USE_FOO will not be granted on API level(s) 37-38, 40, 43-45 due to no valid <purpose>. Ensure valid purpose(s) cover all API level(s). Possible valid purposes: validPurposeForSdk37+, validPurposeForSdk38+ [MissingPurpose]
          <uses-permission android:name="USE_FOO">
          ^
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionsWithEmptyXmlPermissionFile() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 37
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(setupMockSdk(EMPTY_MOCK_XML))
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsWithMissingXmlPermissionFile() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
              android {
                  compileSdk 37
                  defaultConfig {
                      minSdkVersion 30
                      targetSdkVersion 37
                  }
              }
              """,
          )
          .indented(),
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:tools="http://schemas.android.com/tools"
                package="com.example.helloworld">
                <uses-permission android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented(),
      )
      .sdkHome(TestUtils.getSdk().toFile())
      .issues(MISSING_PURPOSE)
      .run()
      .expectClean()
  }

  private fun setupMockSdk(xmlContent: String): File {
    // Find and copy a real SDK to a temporary directory.
    val realSdk = TestUtils.getSdk().toFile()
    val mockSdk = tempFolder.newFolder(MOCK_SDK_DIR)
    realSdk.copyRecursively(target = mockSdk, overwrite = true)

    // Find the platform directory (e.g., "platforms/android-{VERSION}").
    val platformsDir = File(mockSdk, FD_PLATFORMS)
    val targetPlatformDir =
      platformsDir.listFiles()?.find { it.isDirectory && it.name.startsWith("android-") }
    targetPlatformDir!!

    // Create the data directory within that platform.
    val dataDir = File(targetPlatformDir, FD_DATA)
    dataDir.mkdir()

    // Write the custom XML content into data/
    File(dataDir, FN_PERMISSION_VERSIONS).writeText(xmlContent)

    return mockSdk
  }
}
