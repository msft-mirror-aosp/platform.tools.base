/*
 * Copyright (C) 2022 The Android Open Source Project
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

class MissingResourcesPropertiesDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return MissingResourcesPropertiesDetector()
  }

  fun testDocumentationExample() {
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            apply plugin: 'com.android.application'

            android {
              androidResources {
                generateLocaleConfig true
              }
            }
            """
              .trimIndent(),
          )
          .indented()
      )
      .run()
      .expect(
        """
        build.gradle:5: Warning: Missing resources.properties file [MissingResourcesProperties]
            generateLocaleConfig true
            ~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
          .trimIndent()
      )

    // Remove warning
    lint()
      .files(
        gradle(
            "build.gradle",
            """
            apply plugin: 'com.android.application'

            android {
              androidResources {
                generateLocaleConfig true
              }
            }
            """
              .trimIndent(),
          )
          .indented(),
        source("src/main/res/resources.properties", "unqualifiedResLocale=en-US").indented(),
      )
      .run()
      .expectClean()
  }
}
