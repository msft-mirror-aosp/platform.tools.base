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

class ManifestAttributeDetectorTest : AbstractCheckTest() {
  override fun getDetector() = ManifestAttributeDetector()

  fun testDocumentationExample() {
    lint()
        .files(
            manifest(
                    """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
              <application>
                <activity-alias
                    android:name="com.example.ActivityAlias"
                    android:targetActivity="com.example.Activity"
                    android:process=":process"
                    android:label="@string/activity_name"
                    android:exported="true"
                    android:icon="@drawable/activity_alias_icon"
                    android:attributionTags="activity_alias"
                    android:permission="com.example.permission.PERMISSION"
                    android:banner="@string/activity_alias_banner"
                    android:description="@string/activity_alias_description"
                    android:logo="@drawable/activity_alias_logo"
                    android:roundIcon="@drawable/activity_alias_round_icon"
                    >
                  <intent-filter>
                      <action android:name="android.intent.action.VIEW" />
                      <category android:name="android.intent.category.DEFAULT" />
                  </intent-filter>
                </activity-alias>
                <activity-alias
                    android:name="com.example.ActivityAlias2"
                    android:targetActivity="com.example.Activity"
                    android:permission="com.example.permission.PERMISSION"
                    android:theme="@style/Theme.NoDisplay"
                    >
                </activity-alias>
                <activity-alias
                    android:name="com.example.ActivityAlias3"
                    android:targetActivity="com.example.Activity"
                    android:visibleToInstantApps="true"
                    android:permission="com.example.permission.PERMISSION"
                    android:theme="@style/Theme.NoDisplay"
                    >
                </activity-alias>
              </application>
            </manifest>
            """
                )
                .indented()
        )
        .run()
        .expect(
            """
        AndroidManifest.xml:7: Warning: Attribute process on <activity-alias> com.example.ActivityAlias is invalid, and will be silently ignored. This attribute is always ignored on <activity-alias>. [InvalidManifestAttribute]
                android:process=":process"
                ~~~~~~~~~~~~~~~~~~~~~~~~~~
        AndroidManifest.xml:27: Warning: Attribute theme on <activity-alias> com.example.ActivityAlias2 is invalid, and will be silently ignored. This attribute is always ignored on <activity-alias>. [InvalidManifestAttribute]
                android:theme="@style/Theme.NoDisplay"
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        AndroidManifest.xml:33: Warning: Attribute visibleToInstantApps on <activity-alias> com.example.ActivityAlias3 is invalid, and will be silently ignored. This attribute is always ignored on <activity-alias>. [InvalidManifestAttribute]
                android:visibleToInstantApps="true"
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        AndroidManifest.xml:35: Warning: Attribute theme on <activity-alias> com.example.ActivityAlias3 is invalid, and will be silently ignored. This attribute is always ignored on <activity-alias>. [InvalidManifestAttribute]
                android:theme="@style/Theme.NoDisplay"
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 4 warnings
        """
        )
  }

  fun testSkipUnknownAttributes() {
    // We only report known invalid attributes because:
    // (a) There might be newly added attributes that are valid.
    // (b) An attribute like "aaa" will already fail the build, and Studio will already show a
    // warning in the editor, so we don't want to double-report.
    lint()
        .files(
            manifest(
                    """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
              <application>
                <activity-alias
                    android:name="com.example.ActivityAlias"
                    android:targetActivity="com.example.Activity"
                    android:aaa="aaa"
                    >
                </activity-alias>
              </application>
            </manifest>
            """
                )
                .indented()
        )
        .run()
        .expectClean()
  }
}
