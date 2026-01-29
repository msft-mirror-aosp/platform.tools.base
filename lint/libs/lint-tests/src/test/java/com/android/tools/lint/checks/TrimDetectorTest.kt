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

class TrimDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return TrimDetector()
  }

  fun testDocumentationExample() {
    lint()
        .files(
            kotlin(
                    """
            fun test(s: String) {
                s.trim() // OK
                s.trim() { it <= ' ' } // HINT 1
                s.trim { it <= ' ' } // HINT 2
                s.trim({ it <= ' ' }) // HINT 3
                s.trim() {it<=' '} // HINT 4
                s.trim() { c -> c <= ' ' } // HINT 5
                s.trim() { it.isWhitespace() } // HINT 6
                s.trim() { it <= ' ' || c == '.' } // OK
                val to = s.trim { it <= ' ' }.substring(2)
            }
            """
                )
                .indented()
        )
        .run()
        .expect(
            """
        src/test.kt:3: Hint: The lambda argument ({ it <= ' ' }) is unnecessary [TrimLambda]
            s.trim() { it <= ' ' } // HINT 1
                     ~~~~~~~~~~~~~
        src/test.kt:4: Hint: The lambda argument ({ it <= ' ' }) is unnecessary [TrimLambda]
            s.trim { it <= ' ' } // HINT 2
                   ~~~~~~~~~~~~~
        src/test.kt:5: Hint: The lambda argument ({ it <= ' ' }) is unnecessary [TrimLambda]
            s.trim({ it <= ' ' }) // HINT 3
                   ~~~~~~~~~~~~~
        src/test.kt:6: Hint: The lambda argument ({it<=' '}) is unnecessary [TrimLambda]
            s.trim() {it<=' '} // HINT 4
                     ~~~~~~~~~
        src/test.kt:7: Hint: The lambda argument ({ c -> c <= ' ' }) is unnecessary [TrimLambda]
            s.trim() { c -> c <= ' ' } // HINT 5
                     ~~~~~~~~~~~~~~~~~
        src/test.kt:8: Hint: The lambda argument ({ it.isWhitespace() }) is unnecessary [TrimLambda]
            s.trim() { it.isWhitespace() } // HINT 6
                     ~~~~~~~~~~~~~~~~~~~~~
        src/test.kt:10: Hint: The lambda argument ({ it <= ' ' }) is unnecessary [TrimLambda]
            val to = s.trim { it <= ' ' }.substring(2)
                            ~~~~~~~~~~~~~
        0 errors, 0 warnings, 7 hints
        """
        )
        .expectFixDiffs(
            """
        Autofix for src/test.kt line 3: Remove lambda:
        @@ -3 +3 @@
        -    s.trim() { it <= ' ' } // HINT 1
        +    s.trim() // HINT 1
        Autofix for src/test.kt line 4: Remove lambda:
        @@ -4 +4 @@
        -    s.trim { it <= ' ' } // HINT 2
        +    s.trim() // HINT 2
        Autofix for src/test.kt line 5: Remove lambda:
        @@ -5 +5 @@
        -    s.trim({ it <= ' ' }) // HINT 3
        +    s.trim() // HINT 3
        Autofix for src/test.kt line 6: Remove lambda:
        @@ -6 +6 @@
        -    s.trim() {it<=' '} // HINT 4
        +    s.trim() // HINT 4
        Autofix for src/test.kt line 7: Remove lambda:
        @@ -7 +7 @@
        -    s.trim() { c -> c <= ' ' } // HINT 5
        +    s.trim() // HINT 5
        Autofix for src/test.kt line 8: Remove lambda:
        @@ -8 +8 @@
        -    s.trim() { it.isWhitespace() } // HINT 6
        +    s.trim() // HINT 6
        Autofix for src/test.kt line 10: Remove lambda:
        @@ -10 +10 @@
        -    val to = s.trim { it <= ' ' }.substring(2)
        +    val to = s.trim().substring(2)
        """
        )
  }
}
