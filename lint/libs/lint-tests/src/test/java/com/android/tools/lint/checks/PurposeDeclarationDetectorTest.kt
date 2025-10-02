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
                <permission name="USE_FOO" requiresSpecificPurposeMinTargetSdkVersion="37">
                    <valid-specific-purpose name="specificPurposeForSdk37+" minSdkVersion="37" />
                    <valid-specific-purpose name="specificPurposeForSdk38+" minSdkVersion="38" />
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
      // USE_FOO missing requiresSpecificPurposeMinTargetSdkVersion and USE_BAR missing
      // minSdkVersion
      // for purpose.
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
                <permission name="USE_FOO">
                    <valid-specific-purpose name="specificPurposeForSdk37+" minSdkVersion="37" />
                </permission>
                <permission name="USE_BAR" requiresSpecificPurposeMinTargetSdkVersion="37">
                    <valid-specific-purpose name="specificPurposeForSdk37+" />
                </permission>
            </permissions>
        """
        .trimIndent()

    @Language("XML")
    private val MULTI_PERMISSIONS_MOCK_XML =
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
                <permission name="USE_FOO" requiresSpecificPurposeMinTargetSdkVersion="37" requiresSpecificPurposeMaxSdkVersion="38">
                    <valid-specific-purpose name="fooSpecificPurpose1" minSdkVersion="37" maxSdkVersion="37"/>
                    <valid-specific-purpose name="fooSpecificPurpose2" minSdkVersion="38" />
                </permission>
                <permission name="USE_BAR" requiresSpecificPurposeMinTargetSdkVersion="38">
                    <valid-specific-purpose name="barSpecificPurpose1" minSdkVersion="38" />
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
        src/main/AndroidManifest.xml:4: Error: USE_FOO on API level(s) 37 requires one or more <specific-purpose> child tag declaration(s). Ensure declared purpose(s) cover all targeted API level(s). Possible purposes: specificPurposeForSdk37+, specificPurposeForSdk38+ [MissingPurpose]
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
                  <specific-purpose android:name="specificPurposeForSdk38+" />
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
        src/main/AndroidManifest.xml:4: Error: USE_FOO on API level(s) 37 requires one or more <specific-purpose> child tag declaration(s). Ensure declared purpose(s) cover all targeted API level(s). Possible purposes: specificPurposeForSdk37+, specificPurposeForSdk38+ [MissingPurpose]
          <uses-permission android:name="USE_FOO">
           ~~~~~~~~~~~~~~~
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
        src/main/AndroidManifest.xml:4: Error: USE_FOO on API level(s) 37 requires one or more <specific-purpose> child tag declaration(s). Ensure declared purpose(s) cover all targeted API level(s). Possible purposes: specificPurposeForSdk37+, specificPurposeForSdk38+ [MissingPurpose]
          <uses-permission-sdk-23 android:name="USE_FOO" />
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithSpecificPurposePass() {
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
                  <specific-purpose android:name="specificPurposeForSdk37+" />
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
                  <specific-purpose android:name="invalidPurpose" />
                  <specific-purpose android:name="specificPurposeForSdk37+" />
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
                  <specific-purpose android:name="specificPurposeForSdk38+" />
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
                  <specific-purpose android:name="specificPurposeForSdk38+" />
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
                  <specific-purpose android:name="specificPurposeForSdk37+" android:maxSdkVersion="37"  />
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
        src/main/AndroidManifest.xml:4: Error: USE_FOO on API level(s) 38-39 requires one or more <specific-purpose> child tag declaration(s). Ensure declared purpose(s) cover all targeted API level(s). Possible purposes: specificPurposeForSdk37+, specificPurposeForSdk38+ [MissingPurpose]
          <uses-permission android:name="USE_FOO">
           ~~~~~~~~~~~~~~~
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
                  <specific-purpose android:name="fooSpecificPurpose1" />
                </uses-permission>
                <uses-permission android:name="USE_BAR">
                  <specific-purpose android:name="barSpecificPurpose1" android:minSdkVersion="39"  />
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
        src/main/AndroidManifest.xml:4: Error: USE_FOO on API level(s) 38 requires one or more <specific-purpose> child tag declaration(s). Ensure declared purpose(s) cover all targeted API level(s). Possible purposes: fooSpecificPurpose1, fooSpecificPurpose2 [MissingPurpose]
          <uses-permission android:name="USE_FOO">
           ~~~~~~~~~~~~~~~
        src/main/AndroidManifest.xml:7: Error: USE_BAR on API level(s) 38 requires one or more <specific-purpose> child tag declaration(s). Ensure declared purpose(s) cover all targeted API level(s). Possible purposes: barSpecificPurpose1 [MissingPurpose]
          <uses-permission android:name="USE_BAR">
           ~~~~~~~~~~~~~~~
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
                  <specific-purpose android:name="fooSpecificPurpose1" android:maxSdkVersion="37" />
                  <specific-purpose android:name="fooSpecificPurpose2" android:maxSdkVersion="38" />
                </uses-permission>
                <uses-permission android:name="USE_BAR">
                  <specific-purpose android:name="barSpecificPurpose1" />
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
  fun testRequestingPermissionWithMultipleSpecificPurposeIntervalsPass() {
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
                  <specific-purpose android:name="specificPurposeForSdk37+" android:minSdkVersion="37" android:maxSdkVersion="37" />
                  <specific-purpose android:name="specificPurposeForSdk37+" android:minSdkVersion="38" android:maxSdkVersion="41" />
                  <specific-purpose android:name="specificPurposeForSdk37+" android:minSdkVersion="42" android:maxSdkVersion="42" />
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
  fun testRequestingPermissionWithMultipleOverlappedSpecificPurposeIntervalsPass() {
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
                  <specific-purpose android:name="specificPurposeForSdk37+" android:maxSdkVersion="34" />
                  <specific-purpose android:name="specificPurposeForSdk37+" android:minSdkVersion="32" android:maxSdkVersion="36" />
                  <specific-purpose android:name="specificPurposeForSdk37+" android:minSdkVersion="35" android:maxSdkVersion="41" />
                  <specific-purpose android:name="specificPurposeForSdk37+" android:minSdkVersion="40" />
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
                  <specific-purpose android:name="specificPurposeForSdk37+" android:minSdkVersion="34" android:maxSdkVersion="36" />
                  <specific-purpose android:name="specificPurposeForSdk37+" android:minSdkVersion="39" android:maxSdkVersion="39" />
                  <specific-purpose android:name="specificPurposeForSdk37+" android:minSdkVersion="41" android:maxSdkVersion="42" />
                  <specific-purpose android:name="invalidPurpose" android:minSdkVersion="43" />
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
        src/main/AndroidManifest.xml:4: Error: USE_FOO on API level(s) 37-38, 40, 43-45 requires one or more <specific-purpose> child tag declaration(s). Ensure declared purpose(s) cover all targeted API level(s). Possible purposes: specificPurposeForSdk37+, specificPurposeForSdk38+ [MissingPurpose]
          <uses-permission android:name="USE_FOO">
           ~~~~~~~~~~~~~~~
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
    val sourcePlatformDir =
      File(realSdk, FD_PLATFORMS).listFiles()?.find {
        it.isDirectory && it.name.startsWith("android-")
      }!!
    val platformsDir = File(mockSdk, FD_PLATFORMS + File.separator + sourcePlatformDir.name)
    sourcePlatformDir.copyRecursively(target = platformsDir, overwrite = true)
    val dataDir = File(platformsDir, FD_DATA)
    dataDir.mkdir()
    File(dataDir, FN_PERMISSION_VERSIONS).writeText(xmlContent)
    return mockSdk
  }
}
