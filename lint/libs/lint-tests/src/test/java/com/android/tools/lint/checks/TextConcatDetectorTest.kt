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

import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector

class TextConcatDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return TextConcatDetector()
  }

  fun testDocumentationExample() {
    lint()
        .files(
            java(
                    """
            package test.pkg;

            public class Test {
                public void test() {
                    String s = "This is" +
                      "a second line"; // ERROR 1
                    String s = "This is" +
                      "a polyadic" // ERROR 2
                        + "expression"; // ERROR 3
                    String t = "This is" + "same line"; // OK
                }
            }
            """
                )
                .indented(),
            kotlin(
                    """
            package test.pkg

            fun test() {
                    val s = "This is" +
                      "a second line" // ERROR 4
                    val t = "This is" + "same line" // OK 2
            }
            """
                )
                .indented(),
        )
        .run()
        .expect(
            """
        src/test/pkg/Test.java:6: Warning: Missing space between "is" on the previous line and "a" here? Resulting string is "isa". [TextConcatSpace]
                  "a second line"; // ERROR 1
                  ~~~~~~~~~~~~~~~
            src/test/pkg/Test.java:5: Previous text here
                String s = "This is" +
                           ~~~~~~~~~
        src/test/pkg/Test.java:8: Warning: Missing space between "is" on the previous line and "a" here? Resulting string is "isa". [TextConcatSpace]
                  "a polyadic" // ERROR 2
                  ~~~~~~~~~~~~
            src/test/pkg/Test.java:7: Previous text here
                String s = "This is" +
                           ~~~~~~~~~
        src/test/pkg/Test.java:9: Warning: Missing space between "polyadic" on the previous line and "expression" here? Resulting string is "polyadicexpression". [TextConcatSpace]
                    + "expression"; // ERROR 3
                      ~~~~~~~~~~~~
            src/test/pkg/Test.java:8: Previous text here
                  "a polyadic" // ERROR 2
                  ~~~~~~~~~~~~
        src/test/pkg/test.kt:5: Warning: Missing space between "is" on the previous line and "a" here? Resulting string is "isa". [TextConcatSpace]
                  "a second line" // ERROR 4
                   ~~~~~~~~~~~~~
            src/test/pkg/test.kt:4: Previous text here
                val s = "This is" +
                         ~~~~~~~
        0 errors, 4 warnings
        """
        )
        .expectFixDiffs(
            """
        Fix for src/test/pkg/Test.java line 6: Insert space:
        @@ -6 +6 @@
        -          "a second line"; // ERROR 1
        +          " a second line"; // ERROR 1
        Fix for src/test/pkg/Test.java line 8: Insert space:
        @@ -8 +8 @@
        -          "a polyadic" // ERROR 2
        +          " a polyadic" // ERROR 2
        Fix for src/test/pkg/Test.java line 9: Insert space:
        @@ -9 +9 @@
        -            + "expression"; // ERROR 3
        +            + " expression"; // ERROR 3
        Fix for src/test/pkg/test.kt line 5: Insert space:
        @@ -5 +5 @@
        -          "a second line" // ERROR 4
        +          " a second line" // ERROR 4
        """
        )
  }

  fun testCornerCases() {
    lint()
        .files(
            java(
                    """
            package test.pkg;

            public class Test {
                public void test() {
                    String s = "This is" +
                      (("a second line")+  // ERROR 1
                      ("a third line") + "."); // ERROR 2
                }

                public void testNewlineSeparators() {
                    String msg = "Unable to establish a connection to adb.\n\n" + // OK
                                        "Check the Event Log for possible issues.\n"; // OK
                }
            }
            """
                )
                .indented(),
            kotlin(
                    """
            package test.pkg

            fun test2(propertySuffix: String, propertyName: String) {
              val message =
                "This method should be called `get＄propertySuffix` such that `＄propertyName` can " +
                  "be accessed as a property from Kotlin; see https://android.github.io/kotlin-guides/interop.html#property-prefixes" // OK
            }
            """
                )
                .indented(),
        )
        .testModes(TestMode.DEFAULT)
        .run()
        .expect(
            """
        src/test/pkg/Test.java:6: Warning: Missing space between "is" on the previous line and "a" here? Resulting string is "isa". [TextConcatSpace]
                  (("a second line")+  // ERROR 1
                    ~~~~~~~~~~~~~~~
            src/test/pkg/Test.java:5: Previous text here
                String s = "This is" +
                           ~~~~~~~~~
        src/test/pkg/Test.java:7: Warning: Missing space between "line" on the previous line and "a" here? Resulting string is "linea". [TextConcatSpace]
                  ("a third line") + "."); // ERROR 2
                   ~~~~~~~~~~~~~~
            src/test/pkg/Test.java:6: Previous text here
                  (("a second line")+  // ERROR 1
                    ~~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
        )
        .expectFixDiffs(
            """
        Fix for src/test/pkg/Test.java line 6: Insert space:
        @@ -6 +6 @@
        -          (("a second line")+  // ERROR 1
        +          ((" a second line")+  // ERROR 1
        Fix for src/test/pkg/Test.java line 7: Insert space:
        @@ -7 +7 @@
        -          ("a third line") + "."); // ERROR 2
        +          (" a third line") + "."); // ERROR 2
        """
        )
  }
}
