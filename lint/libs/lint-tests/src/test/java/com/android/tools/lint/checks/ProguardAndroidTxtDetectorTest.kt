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

import com.android.tools.lint.detector.api.Detector

class ProguardAndroidTxtDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return ProguardAndroidTxtDetector()
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
                     proguardFiles(getDefaultProguardFile('proguard-android.txt'), 'custom.pro')
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
            build.gradle:6: Warning: Avoid getDefaultProguardFile('proguard-android.txt') [ProguardAndroidTxtUsage]
                  proguardFiles(getDefaultProguardFile('proguard-android.txt'), 'custom.pro')
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
          Fix for build.gradle line 6: Replace with proguard-android-optimize.txt:
          @@ -6 +6 @@
          -      proguardFiles(getDefaultProguardFile('proguard-android.txt'), 'custom.pro')
          +      proguardFiles(getDefaultProguardFile('proguard-android-optimize.txt'), 'custom.pro')
        """
      )
  }

  fun testForProguardAndroidTxt() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
               apply plugin: 'com.android.application'

               android {
                 buildTypes {
                   release {
                     proguardFiles(getDefaultProguardFile('proguard-android.txt'), 'custom.pro')
                     proguardFiles('custom.pro', getDefaultProguardFile('proguard-android.txt'))
                     proguardFile getDefaultProguardFile('proguard-android.txt')
                     proguardFile getDefaultProguardFile("proguard-android.txt") // test unneeded templating
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
            build.gradle:6: Warning: Avoid getDefaultProguardFile('proguard-android.txt') [ProguardAndroidTxtUsage]
                  proguardFiles(getDefaultProguardFile('proguard-android.txt'), 'custom.pro')
                                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            build.gradle:7: Warning: Avoid getDefaultProguardFile('proguard-android.txt') [ProguardAndroidTxtUsage]
                  proguardFiles('custom.pro', getDefaultProguardFile('proguard-android.txt'))
                                              ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            build.gradle:8: Warning: Avoid getDefaultProguardFile('proguard-android.txt') [ProguardAndroidTxtUsage]
                  proguardFile getDefaultProguardFile('proguard-android.txt')
                               ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            build.gradle:9: Warning: Avoid getDefaultProguardFile('proguard-android.txt') [ProguardAndroidTxtUsage]
                  proguardFile getDefaultProguardFile("proguard-android.txt") // test unneeded templating
                               ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
            0 errors, 4 warnings
        """
      )
      .expectFixDiffs(
        """
          Fix for build.gradle line 6: Replace with proguard-android-optimize.txt:
          @@ -6 +6 @@
          -      proguardFiles(getDefaultProguardFile('proguard-android.txt'), 'custom.pro')
          +      proguardFiles(getDefaultProguardFile('proguard-android-optimize.txt'), 'custom.pro')
          Fix for build.gradle line 7: Replace with proguard-android-optimize.txt:
          @@ -7 +7 @@
          -      proguardFiles('custom.pro', getDefaultProguardFile('proguard-android.txt'))
          +      proguardFiles('custom.pro', getDefaultProguardFile('proguard-android-optimize.txt'))
          Fix for build.gradle line 8: Replace with proguard-android-optimize.txt:
          @@ -8 +8 @@
          -      proguardFile getDefaultProguardFile('proguard-android.txt')
          +      proguardFile getDefaultProguardFile('proguard-android-optimize.txt')
          Fix for build.gradle line 9: Replace with proguard-android-optimize.txt:
          @@ -9 +9 @@
          -      proguardFile getDefaultProguardFile("proguard-android.txt") // test unneeded templating
          +      proguardFile getDefaultProguardFile("proguard-android-optimize.txt") // test unneeded templating
        """
      )

    // validate no warning shown for other proguard file usages
    lint()
      .files(
        gradle(
            "build.gradle",
            """
               apply plugin: 'com.android.application'

               android {
                 buildTypes {
                   release {
                     proguardFiles(getDefaultProguardFile('proguard-android-optimize.txt'), 'custom.pro')
                     proguardFiles('custom.pro', getDefaultProguardFile('proguard-android-optimize.txt'))
                     proguardFile getDefaultProguardFile('proguard-android-optimize.txt')
                     proguardFile getDefaultProguardFile("proguard-android-optimize.txt") // test unneeded templating
                     proguardFile 'custom.pro'
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

  fun testForAcceptableProguardConfigs() {
    // validate no warning shown for other proguard file usages
    lint()
      .files(
        gradle(
            "build.gradle",
            """
               apply plugin: 'com.android.application'

               android {
                 buildTypes {
                   release {
                     proguardFiles(getDefaultProguardFile('proguard-android-optimize.txt'), 'custom.pro')
                     proguardFiles('custom.pro', getDefaultProguardFile('proguard-android-optimize.txt'))
                     proguardFile getDefaultProguardFile('proguard-android-optimize.txt')
                     proguardFile getDefaultProguardFile("proguard-android-optimize.txt") // test unneeded templating
                     proguardFile 'custom.pro'
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
}
