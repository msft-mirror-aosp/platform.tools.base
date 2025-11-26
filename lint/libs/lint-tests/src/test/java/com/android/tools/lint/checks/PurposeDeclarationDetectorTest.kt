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
import com.android.tools.lint.checks.PurposeDeclarationDetector.Companion.INVALID_PURPOSE_STRING
import com.android.tools.lint.checks.PurposeDeclarationDetector.Companion.MISSING_PURPOSE
import com.android.tools.lint.checks.infrastructure.TestMode
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

  override fun getIssues() = listOf(MISSING_PURPOSE, INVALID_PURPOSE_STRING)

  companion object {
    private const val MOCK_SDK_DIR = "mock-sdk"

    @Language("XML")
    private val DEFAULT_MOCK_XML =
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
                <permission name="USE_FOO" requiresPurposeMinTargetSdkVersion="38">
                    <valid-purpose name="purposeForSdk38+" minSdkVersion="38" />
                    <valid-purpose name="purposeForSdk39+" minSdkVersion="39" />
                    <valid-purpose name="purposeForSdk38+2" minSdkVersion="38" />
                    <valid-purpose name="purposeForSdk38+3" minSdkVersion="38" />
                    <valid-purpose name="purposeForSdk38+4" minSdkVersion="38" />
                </permission>
            </permissions>
        """
        .trimIndent()

    @Language("XML")
    private val EMPTY_MOCK_XML =
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions/>
        """
        .trimIndent()

    @Language("XML")
    private val MALFORMED_MOCK_XML =
      // USE_FOO missing requiresPurposeMinTargetSdkVersion and USE_BAR missing
      // minSdkVersion for purpose.
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
                <permission name="USE_FOO">
                    <valid-purpose name="purposeForSdk38+" minSdkVersion="38" />
                </permission>
                <permission name="USE_BAR" requiresPurposeMinTargetSdkVersion="38">
                    <valid-purpose name="purposeForSdk38+" />
                </permission>
            </permissions>
        """
        .trimIndent()

    @Language("XML")
    private val MULTI_PERMISSIONS_MOCK_XML =
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
                <permission name="USE_FOO" requiresPurposeMinTargetSdkVersion="38" requiresPurposeMaxSdkVersion="39">
                    <valid-purpose name="fooPurpose1" minSdkVersion="38" maxSdkVersion="38"/>
                    <valid-purpose name="fooPurpose2" minSdkVersion="39" />
                </permission>
                <permission name="USE_BAR" requiresPurposeMinTargetSdkVersion="39">
                    <valid-purpose name="barPurpose1" minSdkVersion="39" />
                </permission>
            </permissions>
        """
        .trimIndent()

    @Language("XML")
    private val PERMISSION_REQUIRING_PURPOSE_STRING =
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
                <permission name="USE_FOO" requiresPurposeStringMinTargetSdkVersion="38" />
            </permissions>
        """
        .trimIndent()

    @Language("XML")
    private val PERMISSION_REQUIRING_ALL_PURPOSES =
      """
            <?xml version="1.0" encoding="utf-8"?>
            <permissions>
                <permission name="USE_FOO" requiresPurposeMinTargetSdkVersion="38" requiresPurposeStringMinTargetSdkVersion="38">
                    <valid-purpose name="purposeForSdk38+" minSdkVersion="38" />
                </permission>
            </permissions>
        """
        .trimIndent()

    private const val MAX_PURPOSE_STRING_LENGTH = 300

    private val LONG_PURPOSE_STRING = "L".repeat(MAX_PURPOSE_STRING_LENGTH + 1)
    private const val VALID_PURPOSE_STRING = "My valid purpose String"
    private const val BLANK_PURPOSE_STRING = "     "

    @Language("XML")
    private val validPurposeString =
      xml(
        "res/values-en-rUS/strings.xml",
        """<resources><string name="myPurposeString">$VALID_PURPOSE_STRING</string></resources>""",
      )

    @Language("XML")
    private val invalidLongPurposeString =
      xml(
        "res/values/strings.xml",
        """<resources><string name="myPurposeString">$LONG_PURPOSE_STRING</string></resources>""",
      )

    @Language("XML")
    private val invalidBlankPurposeString =
      xml(
        "res/values-en-rNZ/strings.xml",
        """<resources><string name="myPurposeString">$BLANK_PURPOSE_STRING</string></resources>""",
      )
  }

  @Test
  fun testDocumentationExampleRequestingPermissionWithNoPurposeFail() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: USE_FOO permission is missing required purpose attributes/elements: missing one or more <purpose> tags required for API level(s) 38 [MissingPurpose]
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
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="39" />
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="purposeForSdk39+" />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: USE_FOO permission is missing required purpose attributes/elements: missing one or more <purpose> tags required for API level(s) 38 [MissingPurpose]
          <uses-permission android:name="USE_FOO">
           ~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testDocumentationExampleRequestingPermissionWithNoPurposeStringFail() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(PERMISSION_REQUIRING_PURPOSE_STRING))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: USE_FOO permission is missing required purpose attributes/elements: missing purposeString attribute [MissingPurpose]
          <uses-permission android:name="USE_FOO" />
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithInvalidPurposeStringAttributeTypeFail() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" android:purposeString="@array/foo_array_resource" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(PERMISSION_REQUIRING_PURPOSE_STRING))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: purposeString must reference a string resource (e.g. @string/my_purpose_resource) [MissingPurpose]
          <uses-permission android:name="USE_FOO" android:purposeString="@array/foo_array_resource" />
                                                                         ~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithLongPurposeStringFail() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" android:purposeString="@string/myPurposeString" />
              </manifest>
              """
          )
          .indented(),
        invalidLongPurposeString,
      )
      .sdkHome(setupMockSdk(PERMISSION_REQUIRING_PURPOSE_STRING))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: The referenced purposeString must be non-blank and have no more than 300 characters. Invalid example(s) include: myPurposeString (Default) [InvalidPurposeString]
          <uses-permission android:name="USE_FOO" android:purposeString="@string/myPurposeString" />
                                                                         ~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithBlankPurposeStringFail() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" android:purposeString="@string/myPurposeString" />
              </manifest>
              """
          )
          .indented(),
        invalidBlankPurposeString,
      )
      .sdkHome(setupMockSdk(PERMISSION_REQUIRING_PURPOSE_STRING))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: The referenced purposeString must be non-blank and have no more than 300 characters. Invalid example(s) include: myPurposeString (en-rNZ) [InvalidPurposeString]
          <uses-permission android:name="USE_FOO" android:purposeString="@string/myPurposeString" />
                                                                         ~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithValidPurposeStringPass() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" android:purposeString="@string/myPurposeString" />
              </manifest>
              """
          )
          .indented(),
        validPurposeString,
      )
      .sdkHome(setupMockSdk(PERMISSION_REQUIRING_PURPOSE_STRING))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithNoPurposeAndPurposeStringFail() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(PERMISSION_REQUIRING_ALL_PURPOSES))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: USE_FOO permission is missing required purpose attributes/elements: missing one or more <purpose> tags required for API level(s) 38; missing purposeString attribute [MissingPurpose]
          <uses-permission android:name="USE_FOO" />
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithNoPurposeAndInvalidPurposeStringTypeFail() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" android:purposeString="@array/my_array_resource" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(PERMISSION_REQUIRING_ALL_PURPOSES))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: USE_FOO permission is missing required purpose attributes/elements: missing one or more <purpose> tags required for API level(s) 38 [MissingPurpose]
          <uses-permission android:name="USE_FOO" android:purposeString="@array/my_array_resource" />
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        AndroidManifest.xml:4: Error: purposeString must reference a string resource (e.g. @string/my_purpose_resource) [MissingPurpose]
          <uses-permission android:name="USE_FOO" android:purposeString="@array/my_array_resource" />
                                                                         ~~~~~~~~~~~~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithNoPurposeAndMultipleInvalidPurposeStringsFail() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" android:purposeString="@string/myPurposeString" />
              </manifest>
              """
          )
          .indented(),
        invalidLongPurposeString,
        invalidBlankPurposeString,
        validPurposeString,
      )
      .sdkHome(setupMockSdk(PERMISSION_REQUIRING_ALL_PURPOSES))
      .skipTestModes(TestMode.RESOURCE_REPOSITORIES)
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: USE_FOO permission is missing required purpose attributes/elements: missing one or more <purpose> tags required for API level(s) 38 [MissingPurpose]
          <uses-permission android:name="USE_FOO" android:purposeString="@string/myPurposeString" />
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        AndroidManifest.xml:4: Error: The referenced purposeString must be non-blank and have no more than 300 characters. Invalid example(s) include: myPurposeString (Default), myPurposeString (en-rNZ) [InvalidPurposeString]
          <uses-permission android:name="USE_FOO" android:purposeString="@string/myPurposeString" />
                                                                         ~~~~~~~~~~~~~~~~~~~~~~~
        2 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithNoPurposeAndInvalidPurposeStringOnOldSdkPass() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36" />
                <uses-permission android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(PERMISSION_REQUIRING_ALL_PURPOSES))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithAllPurposesPass() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" android:purposeString="@string/my_string_resource">
                  <purpose android:name="purposeForSdk38+" />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(PERMISSION_REQUIRING_ALL_PURPOSES))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingUsesPermission23WithNoPurposeFail() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission-sdk-23 android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: USE_FOO permission is missing required purpose attributes/elements: missing one or more <purpose> tags required for API level(s) 38 [MissingPurpose]
          <uses-permission-sdk-23 android:name="USE_FOO" />
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  @Test
  fun testRequestingPermissionWithPurposePass() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="purposeForSdk38+" />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithValidAndInvalidPurposePass() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="invalidPurpose" />
                  <purpose android:name="purposeForSdk38+" />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsWithNoPurposeUsingOldTargetSdkPass() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="36" />
                <uses-permission android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsOnOldSdkWithNoPurposePass() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" android:maxSdkVersion="36" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsOnFewSdksUsingMinSdkVersionAttrWithNoPurposePass() {
    lint()
      .files(
        // SDK 38 does not need purpose due to android:minSdkVersion="38"
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="39" />
                <uses-permission android:name="USE_FOO" android:minSdkVersion="39">
                  <purpose android:name="purposeForSdk39+" />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsOnFewSdksUsingMinSdkVersionWithNoPurposePass() {
    lint()
      .files(
        // SDK 38 does not need purpose due to the app's minSdkVersion set to 39
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="39" android:targetSdkVersion="39" />
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="purposeForSdk39+" />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsWithNoPurposeUsingMalformedXmlPass() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" />
                <uses-permission android:name="USE_BAR" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(MALFORMED_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionRequiringNoPurposePass() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_BAR" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithNoPurposeOn39PlusFail() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="40" />
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="purposeForSdk38+" android:maxSdkVersion="38"  />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: USE_FOO permission is missing required purpose attributes/elements: missing one or more <purpose> tags required for API level(s) 39-40 [MissingPurpose]
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
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="39" />
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="fooPurpose1" />
                </uses-permission>
                <uses-permission android:name="USE_BAR">
                  <purpose android:name="barPurpose1" android:minSdkVersion="40"  />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(MULTI_PERMISSIONS_MOCK_XML))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: USE_FOO permission is missing required purpose attributes/elements: missing one or more <purpose> tags required for API level(s) 39 [MissingPurpose]
          <uses-permission android:name="USE_FOO">
           ~~~~~~~~~~~~~~~
        AndroidManifest.xml:7: Error: USE_BAR permission is missing required purpose attributes/elements: missing one or more <purpose> tags required for API level(s) 39 [MissingPurpose]
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
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="40" />
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="fooPurpose1" android:maxSdkVersion="38" />
                  <purpose android:name="fooPurpose2" android:maxSdkVersion="39" />
                </uses-permission>
                <uses-permission android:name="USE_BAR">
                  <purpose android:name="barPurpose1" />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(MULTI_PERMISSIONS_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithMultiplePurposeIntervalsPass() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="43" />
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="purposeForSdk38+" android:minSdkVersion="38" android:maxSdkVersion="38" />
                  <purpose android:name="purposeForSdk38+2" android:minSdkVersion="39" android:maxSdkVersion="42" />
                  <purpose android:name="purposeForSdk38+3" android:minSdkVersion="43" android:maxSdkVersion="43" />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithMultipleOverlappedPurposeIntervalsPass() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="43" />
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="purposeForSdk38+" android:maxSdkVersion="34" />
                  <purpose android:name="purposeForSdk38+2" android:minSdkVersion="32" android:maxSdkVersion="39" />
                  <purpose android:name="purposeForSdk38+3" android:minSdkVersion="35" android:maxSdkVersion="42" />
                  <purpose android:name="purposeForSdk38+4" android:minSdkVersion="41" />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionWithMultipleSdkRangesMissingPurposeFail() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="46" />
                <uses-permission android:name="USE_FOO">
                  <purpose android:name="purposeForSdk38+" android:minSdkVersion="35" android:maxSdkVersion="37" />
                  <purpose android:name="purposeForSdk38+2" android:minSdkVersion="40" android:maxSdkVersion="40" />
                  <purpose android:name="purposeForSdk38+3" android:minSdkVersion="42" android:maxSdkVersion="43" />
                  <purpose android:name="invalidPurpose" android:minSdkVersion="43" />
                </uses-permission>
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(DEFAULT_MOCK_XML))
      .run()
      .expect(
        """
        AndroidManifest.xml:4: Error: USE_FOO permission is missing required purpose attributes/elements: missing one or more <purpose> tags required for API level(s) 38-39, 41, 44-46 [MissingPurpose]
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
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(setupMockSdk(EMPTY_MOCK_XML))
      .run()
      .expectClean()
  }

  @Test
  fun testRequestingPermissionsWithMissingXmlPermissionFile() {
    lint()
      .files(
        manifest(
            """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.helloworld">
                <uses-sdk android:minSdkVersion="30" android:targetSdkVersion="38" />
                <uses-permission android:name="USE_FOO" />
              </manifest>
              """
          )
          .indented()
      )
      .sdkHome(TestUtils.getSdk().toFile())
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
