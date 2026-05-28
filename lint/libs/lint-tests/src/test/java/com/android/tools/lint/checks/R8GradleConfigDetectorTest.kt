/*
 * Copyright (C) 2026 The Android Open Source Project
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

import com.android.tools.lint.detector.api.Detector

class R8GradleConfigDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return R8GradleConfigDetector()
  }

  /** Used to generate documentation */
  fun testDocumentationExample() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
               apply plugin: 'com.android.application'

               android {
                 buildTypes {
                   release {
                     minifyEnabled = true
                     shrinkResources = false
                     proguardFiles(getDefaultProguardFile('proguard-android-optimize.txt'), 'custom.pro')
                   }
                 }
               }
           """,
          )
          .indented()
      )
      .run()
      .expect(
        """
            build.gradle:7: Warning: Avoid setting shrinkResources = false [NotShrinkingResources]
                  shrinkResources = false
                                    ~~~~~
            0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
          Fix for build.gradle line 7: Replace with true:
          @@ -7 +7 @@
          -      shrinkResources = false
          +      shrinkResources = true
        """
      )
  }

  fun testShrinkResourcesFalseKts() { // KTS equivalent of doc example above
    lint()
      .files(
        kts(
            "build.gradle.kts",
            """
               plugins {
                   id("com.android.application")
               }

               android {
                 buildTypes {
                   release {
                     isMinifyEnabled = true
                     isShrinkResources = false
                   }
                 }
               }
           """,
          )
          .indented()
      )
      .run()
      .expect(
        """
            build.gradle.kts:9: Warning: Avoid setting isShrinkResources = false [NotShrinkingResources]
                  isShrinkResources = false
                                      ~~~~~
            0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
          Fix for build.gradle.kts line 9: Replace with true:
          @@ -9 +9 @@
          -      isShrinkResources = false
          +      isShrinkResources = true
        """
      )
  }

  fun testShrinkResourcesMissing() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
               apply plugin: 'com.android.application'

               android {
                 buildTypes {
                   release {
                     minifyEnabled = true
                   }
                 }
               }
           """,
          )
          .indented()
      )
      .run()
      .expect(
        """
          build.gradle:6: Warning: If enabling minification, also set shrinkResources = true [NotShrinkingResources]
                minifyEnabled = true
                ~~~~~~~~~~~~~~~~~~~~
          0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
          Fix for build.gradle line 6: Replace with true...:
          @@ -6,0 +7 @@
          +      shrinkResources = true
        """
      )

    // validate no warning shown if flag is set
    lint()
      .files(
        gradle(
            "build.gradle",
            """
               apply plugin: 'com.android.application'

               android {
                 buildTypes {
                   release {
                     minifyEnabled = true
                     shrinkResources = true
                   }
                 }
               }
          """,
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testShrinkResourcesMissingKts() {
    lint()
      .files(
        kts(
            "build.gradle.kts",
            """
               plugins {
                   id("com.android.application")
               }

               android {
                 buildTypes {
                   release {
                     isMinifyEnabled = true
                   }
                 }
               }
           """,
          )
          .indented()
      )
      .run()
      .expect(
        """
          build.gradle.kts:8: Warning: If enabling minification, also set isShrinkResources = true [NotShrinkingResources]
                isMinifyEnabled = true
                ~~~~~~~~~~~~~~~~~~~~~~
          0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
          Fix for build.gradle.kts line 8: Replace with true...:
          @@ -8,0 +9 @@
          +      isShrinkResources = true
        """
      )

    // validate no warning shown if flag is set
    lint()
      .files(
        gradle(
            "build.gradle",
            """
               apply plugin: 'com.android.application'

               android {
                 buildTypes {
                   release {
                     isMinifyEnabled = true
                     isShrinkResources = true
                   }
                 }
               }
          """,
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testNoWarningIfMinifyDisabled() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
               apply plugin: 'com.android.application'

               android {
                 buildTypes {
                   nonminified {
                     minifyEnabled = false
                     shrinkResources = false
                   }
                   release {
                     minifyEnabled = true
                     shrinkResources = true
                   }
                 }
               }
           """,
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testWarningIfMinifyEnabledRegardlessOfOrder() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
               apply plugin: 'com.android.application'

               android {
                 buildTypes {
                   release {
                     shrinkResources = false
                     minifyEnabled = true
                   }
                 }
               }
           """,
          )
          .indented()
      )
      .run()
      .expect(
        """
            build.gradle:6: Warning: Avoid setting shrinkResources = false [NotShrinkingResources]
                  shrinkResources = false
                                    ~~~~~
            0 errors, 1 warning
        """
      )
  }
}
