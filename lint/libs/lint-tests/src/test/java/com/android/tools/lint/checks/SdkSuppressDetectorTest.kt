/*
 * Copyright (C) 2023 The Android Open Source Project
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
import com.android.tools.lint.detector.api.requiresExtensionStub

class SdkSuppressDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return SdkSuppressDetector()
  }

  fun testDocumentationExample() {
    lint()
        .files(
            manifest().minSdk(4),
            kotlin(
                    "src/test/java/test/pkg/UnitTestKotlin.kt",
                    """
            import android.widget.GridLayout
            import androidx.annotation.RequiresApi
            import org.junit.Test

            @RequiresApi(29) // ERROR: don't use in tests, use @SdkSuppress instead
            class UnitTestKotlin {
                private val field1 = GridLayout(null) // OK via @RequiresApiSuppress
                @Test
                fun thisIsATest() = Unit
            }
            """,
                )
                .indented(),
            gradle(
                    """
            android {
                lintOptions {
                    checkTestSources = true
                }
            }
            """
                )
                .indented(),
            SUPPORT_ANNOTATIONS_JAR,
            junitTestStub,
        )
        .run()
        .expect(
            """
        src/test/java/test/pkg/UnitTestKotlin.kt:5: Error: Don't use @RequiresApi from tests; use @SdkSuppress on UnitTestKotlin instead [UseSdkSuppress]
        @RequiresApi(29) // ERROR: don't use in tests, use @SdkSuppress instead
        ~~~~~~~~~~~~~~~~
        1 errors, 0 warnings
        """
        )
        .expectFixDiffs(
            """
        Fix for src/test/java/test/pkg/UnitTestKotlin.kt line 5: Replace with @androidx.test.filters.SdkSuppress(minSdkVersion=29):
        @@ -2,0 +3 @@
        +import androidx.test.filters.SdkSuppress
        @@ -5 +6 @@
        -@RequiresApi(29) // ERROR: don't use in tests, use @SdkSuppress instead
        +@SdkSuppress(minSdkVersion=29) // ERROR: don't use in tests, use @SdkSuppress instead
        """
        )
  }

  fun testBasic() {
    lint()
        .files(
            manifest().minSdk(4),
            java(
                    "src/test/java/test/pkg/UnitTestJava.java",
                    """
            package test.pkg;

            import androidx.annotation.RequiresApi;
            import android.widget.GridLayout;
            import org.junit.Test;

            // Comment
            @RequiresApi(29) // ERROR: don't use in tests, use @SdkSuppress instead
            public class UnitTestJava {
                private GridLayout field1 = new GridLayout(null); // OK via @RequiresApiSuppress
                @androidx.annotation.RequiresApi(api=31) // ERROR: don't use in tests, use @SdkSuppress instead
                @Test
                public void test() { }

                @RequiresApi(29) void utility1() { } // OK - not public
                @RequiresApi(29) private void utility2() { } // OK - not public
            }
            """,
                )
                .indented(),
            kotlin(
                    "src/test/java/test/pkg/UnitTestKotlin.kt",
                    """
            import android.widget.GridLayout
            import androidx.test.filters.SdkSuppress
            import androidx.annotation.RequiresApi
            import org.junit.Test

            @RequiresApi(29) // ERROR: don't use in tests, use @SdkSuppress instead
            class UnitTestKotlin {
                private val field1 = GridLayout(null) // OK via @RequiresApiSuppress

                @RequiresApi(api = 31) // ERROR: don't use in tests, use @SdkSuppress instead
                @Test
                fun test() {
                }

                @SdkSuppress(minSdkVersion = 31) // OK
                @Test
                fun test2() {
                }

                @RequiresApi(api = 31) // OK because we only flag annotations on classes & methods
                private val field2 = GridLayout(null)

                @androidx.annotation.RequiresExtension(extension= Build.VERSION_CODES.R, version=4)
                @Test
                fun test3() { // OK until b/257429573 is fixed
                }
            }

            @RequiresApi(29) private fun utility1() { } // OK - not public
            @RequiresApi(29) internal fun utility2() { } // OK - not public
            """,
                )
                .indented(),
            gradle(
                    """
            android {
                lintOptions {
                    checkTestSources = true
                }
            }
            """
                )
                .indented(),
            sdkSuppressStub,
            SUPPORT_ANNOTATIONS_JAR,
            requiresExtensionStub,
            junitTestStub,
        )
        .run()
        .expect(
            """
        src/test/java/test/pkg/UnitTestJava.java:8: Error: Don't use @RequiresApi from tests; use @SdkSuppress on UnitTestJava instead [UseSdkSuppress]
        @RequiresApi(29) // ERROR: don't use in tests, use @SdkSuppress instead
        ~~~~~~~~~~~~~~~~
        src/test/java/test/pkg/UnitTestJava.java:11: Error: Don't use @RequiresApi from tests; use @SdkSuppress on test instead [UseSdkSuppress]
            @androidx.annotation.RequiresApi(api=31) // ERROR: don't use in tests, use @SdkSuppress instead
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/test/java/test/pkg/UnitTestKotlin.kt:6: Error: Don't use @RequiresApi from tests; use @SdkSuppress on UnitTestKotlin instead [UseSdkSuppress]
        @RequiresApi(29) // ERROR: don't use in tests, use @SdkSuppress instead
        ~~~~~~~~~~~~~~~~
        src/test/java/test/pkg/UnitTestKotlin.kt:10: Error: Don't use @RequiresApi from tests; use @SdkSuppress on test instead [UseSdkSuppress]
            @RequiresApi(api = 31) // ERROR: don't use in tests, use @SdkSuppress instead
            ~~~~~~~~~~~~~~~~~~~~~~
        4 errors, 0 warnings
        """
        )
        .expectFixDiffs(
            """
        Fix for src/test/java/test/pkg/UnitTestJava.java line 8: Replace with @androidx.test.filters.SdkSuppress(minSdkVersion=29):
        @@ -4,0 +5 @@
        +import androidx.test.filters.SdkSuppress;
        @@ -8 +9 @@
        -@RequiresApi(29) // ERROR: don't use in tests, use @SdkSuppress instead
        +@SdkSuppress(minSdkVersion=29) // ERROR: don't use in tests, use @SdkSuppress instead
        Fix for src/test/java/test/pkg/UnitTestJava.java line 11: Replace with @androidx.test.filters.SdkSuppress(minSdkVersion=31):
        @@ -4,0 +5 @@
        +import androidx.test.filters.SdkSuppress;
        @@ -11 +12 @@
        -    @androidx.annotation.RequiresApi(api=31) // ERROR: don't use in tests, use @SdkSuppress instead
        +    @SdkSuppress(minSdkVersion=31) // ERROR: don't use in tests, use @SdkSuppress instead
        Fix for src/test/java/test/pkg/UnitTestKotlin.kt line 6: Replace with @androidx.test.filters.SdkSuppress(minSdkVersion=29):
        @@ -6 +6 @@
        -@RequiresApi(29) // ERROR: don't use in tests, use @SdkSuppress instead
        +@SdkSuppress(minSdkVersion=29) // ERROR: don't use in tests, use @SdkSuppress instead
        Fix for src/test/java/test/pkg/UnitTestKotlin.kt line 10: Replace with @androidx.test.filters.SdkSuppress(minSdkVersion=31):
        @@ -10 +10 @@
        -    @RequiresApi(api = 31) // ERROR: don't use in tests, use @SdkSuppress instead
        +    @SdkSuppress(minSdkVersion=31) // ERROR: don't use in tests, use @SdkSuppress instead
        """
        )
        // Test compatibility handling for diffs; before a bug was fixed in the test differ,
        // for window=0 diffs we'd sometimes miss a context separator (@@). We now handle
        // it right, but we allow tests to pass if they had the exact old diff format instead.
        .expectFixDiffs(
            """
        Fix for src/test/java/test/pkg/UnitTestJava.java line 8: Replace with @androidx.test.filters.SdkSuppress(minSdkVersion=29):
        @@ -4,0 +5 @@
        +import androidx.test.filters.SdkSuppress;
        @@ -8 +9 @@
        -@RequiresApi(29) // ERROR: don't use in tests, use @SdkSuppress instead
        +@SdkSuppress(minSdkVersion=29) // ERROR: don't use in tests, use @SdkSuppress instead
        Fix for src/test/java/test/pkg/UnitTestJava.java line 11: Replace with @androidx.test.filters.SdkSuppress(minSdkVersion=31):
        @@ -4,0 +5 @@
        +import androidx.test.filters.SdkSuppress;
        @@ -11 +12 @@
        -    @androidx.annotation.RequiresApi(api=31) // ERROR: don't use in tests, use @SdkSuppress instead
        +    @SdkSuppress(minSdkVersion=31) // ERROR: don't use in tests, use @SdkSuppress instead
        Fix for src/test/java/test/pkg/UnitTestKotlin.kt line 6: Replace with @androidx.test.filters.SdkSuppress(minSdkVersion=29):
        @@ -6 +6 @@
        -@RequiresApi(29) // ERROR: don't use in tests, use @SdkSuppress instead
        +@SdkSuppress(minSdkVersion=29) // ERROR: don't use in tests, use @SdkSuppress instead
        Fix for src/test/java/test/pkg/UnitTestKotlin.kt line 10: Replace with @androidx.test.filters.SdkSuppress(minSdkVersion=31):
        @@ -10 +10 @@
        -    @RequiresApi(api = 31) // ERROR: don't use in tests, use @SdkSuppress instead
        +    @SdkSuppress(minSdkVersion=31) // ERROR: don't use in tests, use @SdkSuppress instead
        """
        )
  }

  fun testUsageOnNonTests() {
    lint()
        .files(
            manifest().minSdk(4),
            kotlin(
                    "src/test/java/test/pkg/UnitTestKotlin.kt",
                    """
            import android.widget.GridLayout
            import androidx.annotation.RequiresApi
            import androidx.test.filters.SdkSuppress
            import org.junit.Test

            @SdkSuppress(minSdkVersion=29)
            class UnitTestKotlin {
                @SdkSuppress(minSdkVersion=30)
                @Test
                fun thisIsATest() = Unit

                @RequiresApi(30) // OK: not a test method
                fun testHelper()
            }

            @RequiresApi(29) // OK: not a test class
            class TestHelper {
                @RequiresApi(30) // OK: not a test method
                fun testHelper() = Unit
            }
            """,
                )
                .indented(),
            gradle(
                    """
            android {
                lintOptions {
                    checkTestSources = true
                }
            }
            """
                )
                .indented(),
            SUPPORT_ANNOTATIONS_JAR,
            junitTestStub,
            sdkSuppressStub,
        )
        .run()
        .expectClean()
  }

  fun testUsageOnTestCaseTests() {
    lint()
        .files(
            manifest().minSdk(4),
            kotlin(
                    "src/test/java/test/pkg/UnitTestKotlin.kt",
                    """
            import junit.framework.TestCase
            import androidx.annotation.RequiresApi

            open class ExtendsTestCase : TestCase()

            @RequiresApi(29) // ERROR 1: don't use in tests, use @SdkSuppress instead
            class UnitTestKotlin : ExtendsTestCase() {
              @RequiresApi(30) // ERROR 2: don't use in tests, use @SdkSuppress instead
              fun testThisIsATest() = Unit

              @RequiresApi(30) // OK: not a test method
              fun thisIsNotATest() = Unit
            }
            """,
                )
                .indented(),
            gradle(
                    """
            android {
                lintOptions {
                    checkTestSources = true
                }
            }
            """
                )
                .indented(),
            SUPPORT_ANNOTATIONS_JAR,
            junitTestCaseStub,
        )
        .run()
        .expect(
            """
        src/test/java/test/pkg/UnitTestKotlin.kt:6: Error: Don't use @RequiresApi from tests; use @SdkSuppress on UnitTestKotlin instead [UseSdkSuppress]
        @RequiresApi(29) // ERROR 1: don't use in tests, use @SdkSuppress instead
        ~~~~~~~~~~~~~~~~
        src/test/java/test/pkg/UnitTestKotlin.kt:8: Error: Don't use @RequiresApi from tests; use @SdkSuppress on testThisIsATest instead [UseSdkSuppress]
          @RequiresApi(30) // ERROR 2: don't use in tests, use @SdkSuppress instead
          ~~~~~~~~~~~~~~~~
        2 errors
        """
        )
        .expectFixDiffs(
            """
        Fix for src/test/java/test/pkg/UnitTestKotlin.kt line 6: Replace with @androidx.test.filters.SdkSuppress(minSdkVersion=29):
        @@ -0,0 +1 @@
        +import androidx.test.filters.SdkSuppress
        @@ -6 +7 @@
        -@RequiresApi(29) // ERROR 1: don't use in tests, use @SdkSuppress instead
        +@SdkSuppress(minSdkVersion=29) // ERROR 1: don't use in tests, use @SdkSuppress instead
        Fix for src/test/java/test/pkg/UnitTestKotlin.kt line 8: Replace with @androidx.test.filters.SdkSuppress(minSdkVersion=30):
        @@ -0,0 +1 @@
        +import androidx.test.filters.SdkSuppress
        @@ -8 +9 @@
        -  @RequiresApi(30) // ERROR 2: don't use in tests, use @SdkSuppress instead
        +  @SdkSuppress(minSdkVersion=30) // ERROR 2: don't use in tests, use @SdkSuppress instead
        """
        )
  }

  companion object {
    private val junitTestStub =
        kotlin(
                """
          package org.junit
          annotation class Test
          """
            )
            .indented()

    private val sdkSuppressStub =
        java(
                """
          /*HIDE-FROM-DOCUMENTATION*/package androidx.test.filters;
          import java.lang.annotation.ElementType;
          import java.lang.annotation.Retention;
          import java.lang.annotation.RetentionPolicy;
          import java.lang.annotation.Target;
          @Retention(RetentionPolicy.RUNTIME)
          @Target({ElementType.TYPE, ElementType.METHOD})
          public @interface SdkSuppress {
            int minSdkVersion() default 1;
            int maxSdkVersion() default Integer.MAX_VALUE;
            int[] excludedSdks() default {};
            String codeName() default "unset";
          }
          """
            )
            .indented()

    private val junitTestCaseStub =
        kotlin(
                """
          package junit.framework
          open class TestCase
          """
            )
            .indented()
  }
}
