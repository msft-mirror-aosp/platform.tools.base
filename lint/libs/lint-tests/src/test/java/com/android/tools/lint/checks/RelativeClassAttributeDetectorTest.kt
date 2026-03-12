/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.tools.lint.checks

import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

/** Unit tests for [RelativeClassAttributeDetector]. */
class RelativeClassAttributeDetectorTest : AbstractCheckTest() {

  override fun getDetector(): Detector {
    return RelativeClassAttributeDetector()
  }

  override fun getIssues(): List<Issue> {
    return listOf(RelativeClassAttributeDetector.ISSUE)
  }

  fun testDocumentationExample() {
    lint()
      .files(
        manifest("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.core" />""").indented(),
        gradle(
            """
          android {
              namespace 'com.example.core'
              defaultConfig {
                  applicationId 'com.example.app'
              }
          }
          """
          )
          .indented(),
        xml(
            "src/main/res/layout/activity_main.xml",
            """
          <androidx.coordinatorlayout.widget.CoordinatorLayout
              xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:app="http://schemas.android.com/apk/res-auto"
              android:layout_width="match_parent"
              android:layout_height="match_parent">

              <View
                  android:layout_width="100dp"
                  android:layout_height="100dp"
                  app:layout_behavior=".FakeBehavior" />

          </androidx.coordinatorlayout.widget.CoordinatorLayout>
          """,
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/main/res/layout/activity_main.xml:10: Error: Relative class name (.FakeBehavior) will resolve using applicationId at runtime and crash. Use the fully qualified name instead: com.example.core.FakeBehavior [RelativeClassResolution]
                app:layout_behavior=".FakeBehavior" />
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/main/res/layout/activity_main.xml line 10: Replace with fully qualified class name:
        @@ -10 +10 @@
        -        app:layout_behavior=".FakeBehavior" />
        +        app:layout_behavior="com.example.core.FakeBehavior" />
        """
      )
  }

  fun testMatchingIdsReportWarning() {
    lint()
      .files(
        manifest("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.core" />""").indented(),
        gradle(
            """
          android {
              namespace 'com.example.core'
              defaultConfig {
                  applicationId 'com.example.core'
              }
          }
          """
          )
          .indented(),
        xml(
            "src/main/res/layout/activity_main.xml",
            """
          <androidx.coordinatorlayout.widget.CoordinatorLayout
              xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:app="http://schemas.android.com/apk/res-auto"
              android:layout_width="match_parent"
              android:layout_height="match_parent">

              <View app:layout_behavior=".FakeBehavior" />

          </androidx.coordinatorlayout.widget.CoordinatorLayout>
          """,
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/main/res/layout/activity_main.xml:7: Warning: Relative class name (.FakeBehavior) is fragile. If the applicationId changes via build flavors or refactoring, this will crash at runtime. Use the fully qualified name instead: com.example.core.FakeBehavior [RelativeClassResolution]
            <View app:layout_behavior=".FakeBehavior" />
                  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/main/res/layout/activity_main.xml line 7: Replace with fully qualified class name:
        @@ -7 +7 @@
        -    <View app:layout_behavior=".FakeBehavior" />
        +    <View app:layout_behavior="com.example.core.FakeBehavior" />
        """
      )
  }

  fun testLibraryModuleIsFatal() {
    lint()
      .files(
        manifest("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.lib" />""").indented(),
        gradle(
            """
          apply plugin: 'com.android.library'
          android {
              namespace 'com.example.lib'
          }
          """
          )
          .indented(),
        xml(
            "src/main/res/layout/lib_layout.xml",
            """
          <androidx.coordinatorlayout.widget.CoordinatorLayout
              xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:app="http://schemas.android.com/apk/res-auto"
              android:layout_width="match_parent"
              android:layout_height="match_parent">

              <View app:layout_behavior=".FakeBehavior" />

          </androidx.coordinatorlayout.widget.CoordinatorLayout>
          """,
          )
          .indented(),
      )
      .run()
      .expect(
        """
        src/main/res/layout/lib_layout.xml:7: Error: Relative class name (.FakeBehavior) will resolve using applicationId at runtime and crash. Use the fully qualified name instead: com.example.lib.FakeBehavior [RelativeClassResolution]
            <View app:layout_behavior=".FakeBehavior" />
                  ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
      )
  }

  fun testNoDotPrefixIsIgnored() {
    lint()
      .files(
        manifest("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.core" />""").indented(),
        gradle(
            """
          android {
              namespace 'com.example.core'
              defaultConfig {
                  applicationId 'com.example.app'
              }
          }
          """
          )
          .indented(),
        xml(
            "src/main/res/layout/activity_main.xml",
            """
          <androidx.coordinatorlayout.widget.CoordinatorLayout
              xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:app="http://schemas.android.com/apk/res-auto"
              android:layout_width="match_parent"
              android:layout_height="match_parent">

              <View app:layout_behavior="FakeBehavior" />

          </androidx.coordinatorlayout.widget.CoordinatorLayout>
          """,
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  fun testFullyQualifiedNamesAreIgnored() {
    lint()
      .files(
        manifest("""<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.core" />""").indented(),
        gradle(
            """
          android {
              namespace 'com.example.core'
              defaultConfig {
                  applicationId 'com.example.app'
              }
          }
          """
          )
          .indented(),
        xml(
            "src/main/res/layout/activity_main.xml",
            """
          <androidx.coordinatorlayout.widget.CoordinatorLayout
              xmlns:android="http://schemas.android.com/apk/res/android"
              xmlns:app="http://schemas.android.com/apk/res-auto"
              android:layout_width="match_parent"
              android:layout_height="match_parent">

              <View app:layout_behavior="com.example.core.FakeBehavior" />

          </androidx.coordinatorlayout.widget.CoordinatorLayout>
          """,
          )
          .indented(),
      )
      .run()
      .expectClean()
  }
}
