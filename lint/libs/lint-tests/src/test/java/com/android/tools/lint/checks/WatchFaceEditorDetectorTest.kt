/*
 * Copyright (C) 2021 The Android Open Source Project
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

import com.android.tools.lint.checks.infrastructure.TestMode

class WatchFaceEditorDetectorTest : AbstractCheckTest() {
  override fun getDetector() = WatchFaceEditorDetector()

  fun testDocumentationExample() {
    // Wrong launch mode
    lint()
      .files(
        xml(
            "AndroidManifest.xml",
            """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="test.pkg">
                        <application>
                            <activity
                                android:name=".WatchFaceEditor"
                                android:exported="true"
                                android:launchMode="singleTask">
                                <intent-filter>
                                    <action android:name="androidx.wear.watchface.editor.action.WATCH_FACE_EDITOR" />
                                </intent-filter>
                            </activity>
                        </application>
                    </manifest>""",
          )
          .indented()
      )
      .run()
      .expect(
        """
            AndroidManifest.xml:7: Warning: Watch face editor must use launchMode="standard" [WatchFaceEditor]
                        android:launchMode="singleTask">
                        ~~~~~~~~~~~~~~~~~~
            0 errors, 1 warnings"""
      )
      .verifyFixes()
      .expectFixDiffs(
        """
                Fix for AndroidManifest.xml line 7: Set launchMode="standard":
                @@ -9 +9 @@
                -            android:launchMode="singleTask" >
                +            android:launchMode="standard" >
        """
      )
  }

  fun testCorrectLaunchMode() {
    lint()
      .files(
        xml(
            "AndroidManifest.xml",
            """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="test.pkg">
                        <application>
                            <activity
                                android:name=".WatchFaceEditor"
                                android:exported="true"
                                android:launchMode="standard">
                                <intent-filter>
                                    <action android:name="androidx.wear.watchface.editor.action.WATCH_FACE_EDITOR" />
                                </intent-filter>
                            </activity>
                        </application>
                    </manifest>""",
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testWatchFaceFormatAppClean() {
    lint()
      .files(
        xml(
            "AndroidManifest.xml",
            """
                    <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                        package="test.pkg">
                        <application>
                            <activity
                                android:name=".WatchFaceEditor"
                                android:exported="true"
                                android:launchMode="singleTask">
                                <intent-filter>
                                    <action android:name="androidx.wear.watchface.editor.action.WATCH_FACE_EDITOR" />
                                </intent-filter>
                            </activity>
                        </application>
                    </manifest>""",
          )
          .indented(),
        xml("res/raw/watchface.xml", "<WatchFace width=\"450\" height=\"450\" />"),
      )
      .run()
      .expectClean()
  }

  /** It is unlikely to have multi-module DWF projects, but it's not impossible. */
  fun testMultiProjectWatchFaceFormatAppClean() {
    val lib =
      project(
          manifest(
              """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="test.pkg.lib">
                  <application>
                      <activity
                          android:name=".WatchFaceEditor"
                          android:exported="true"
                          android:launchMode="singleTask">
                          <intent-filter>
                              <action android:name="androidx.wear.watchface.editor.action.WATCH_FACE_EDITOR" />
                          </intent-filter>
                      </activity>
                  </application>
              </manifest>
              """
            )
            .indented()
        )
        .name("lib")

    val app =
      project(
          manifest(
              """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="test.pkg">
                  <application>
                      <property android:name="com.google.wear.watchface.format.version" android:value="1" />
                  </application>
              </manifest>
              """
            )
            .indented(),
          xml("res/raw/watchface.xml", "<WatchFace width=\"450\" height=\"450\" />"),
        )
        .name("app")
        .dependsOn(lib)

    lint().projects(app, lib).testModes(TestMode.DEFAULT).run().expectClean()
  }
}
