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

class ArrayInitDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return ArrayInitDetector()
  }

  fun testDocumentationExample() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            fun testInit(size: Int, value: Int) {
                // Mutable Array to keep track of bound changes -- example from MotionLayout
                val startPoints = remember { IntArray(4) { 0 } }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:5: Hint: This initialization lambda ({ 0 }) is unnecessary and is less efficient [UnnecessaryArrayInit]
            val startPoints = remember { IntArray(4) { 0 } }
                                                     ~~~~~
        0 errors, 0 warnings, 1 hint
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 5: Remove initialization:
        @@ -5 +5
        -     val startPoints = remember { IntArray(4) { 0 } }
        +     val startPoints = remember { IntArray(4) }
        """
      )
  }

  @Suppress("RemoveRedundantQualifierName")
  fun testScenarios() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            fun testInit(size: Int, value: Int) {
                ByteArray(size) // OK
                ByteArray(size) { 1 }  // OK, since not default value
                kotlin.ByteArray(size) { 1 }
                ByteArray(size) { 0 }  // WARN 1
                kotlin.ByteArray(size) { 0 }  // WARN 2
                IntArray(size) { 0 }  // WARN 3
                IntArray(size) { value }  // OK
                intArrayOf(0) // OK
                intArrayOf(size) // OK - but maybe add warning that "size" or "length" as a parameter name is suspicious here
                val testActions = IntArray(3) { 0 } // WARN 4

                val width = Array(2) { 0f } // OK - initializer required for the raw array type
                kotlin.Array(2) { 0f } // OK
                val scaleX = FloatArray(5) { 0f } // WARN 5
            }

            fun testUnusedInitialValues(size: Int, value: Int) {
              val writeBeforeRead = IntArray(size) { -1 } // TODO: WARN 7: Value is written before read
              for (i in writeBeforeRead.indices) {
                writeBeforeRead[i] = i
              }
              val uncertainRead1 = IntArray(size) { -1 } // OK - not sure if value is read before read
              println(uncertainRead1)

              val uncertainRead2 = IntArray(size) { -1 } // OK - not certain value always written first
              for (i in uncertainRead2.indices) {
                if (i > 5) {
                  uncertainRead2[i] = i
                }
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:7: Hint: This initialization lambda ({ 0 }) is unnecessary and is less efficient [UnnecessaryArrayInit]
            ByteArray(size) { 0 }  // WARN 1
                            ~~~~~
        src/test/pkg/test.kt:8: Hint: This initialization lambda ({ 0 }) is unnecessary and is less efficient [UnnecessaryArrayInit]
            kotlin.ByteArray(size) { 0 }  // WARN 2
                                   ~~~~~
        src/test/pkg/test.kt:9: Hint: This initialization lambda ({ 0 }) is unnecessary and is less efficient [UnnecessaryArrayInit]
            IntArray(size) { 0 }  // WARN 3
                           ~~~~~
        src/test/pkg/test.kt:13: Hint: This initialization lambda ({ 0 }) is unnecessary and is less efficient [UnnecessaryArrayInit]
            val testActions = IntArray(3) { 0 } // WARN 4
                                          ~~~~~
        src/test/pkg/test.kt:17: Hint: This initialization lambda ({ 0f }) is unnecessary and is less efficient [UnnecessaryArrayInit]
            val scaleX = FloatArray(5) { 0f } // WARN 5
                                       ~~~~~~
        0 errors, 0 warnings, 5 hints
        """
      )
      .expectFixDiffs(
        """
        Autofix for src/test/pkg/test.kt line 7: Remove initialization:
        @@ -7 +7
        -     ByteArray(size) { 0 }  // WARN 1
        +     ByteArray(size)  // WARN 1
        Autofix for src/test/pkg/test.kt line 8: Remove initialization:
        @@ -8 +8
        -     kotlin.ByteArray(size) { 0 }  // WARN 2
        +     kotlin.ByteArray(size)  // WARN 2
        Autofix for src/test/pkg/test.kt line 9: Remove initialization:
        @@ -9 +9
        -     IntArray(size) { 0 }  // WARN 3
        +     IntArray(size)  // WARN 3
        Autofix for src/test/pkg/test.kt line 13: Remove initialization:
        @@ -13 +13
        -     val testActions = IntArray(3) { 0 } // WARN 4
        +     val testActions = IntArray(3) // WARN 4
        Autofix for src/test/pkg/test.kt line 17: Remove initialization:
        @@ -17 +17
        -     val scaleX = FloatArray(5) { 0f } // WARN 5
        +     val scaleX = FloatArray(5) // WARN 5
        """
      )
  }
}
