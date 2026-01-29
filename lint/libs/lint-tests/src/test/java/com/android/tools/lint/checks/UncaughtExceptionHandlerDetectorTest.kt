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

class UncaughtExceptionHandlerDetectorTest : AbstractCheckTest() {

  override fun getDetector(): Detector = UncaughtExceptionHandlerDetector()

  fun testDocumentationExample() {
    lint()
        .files(
            kotlin(
                    """
            package com.example.app

            import android.util.Log
            import java.lang.Thread.setDefaultUncaughtExceptionHandler

            fun foo() {
                setDefaultUncaughtExceptionHandler { thread, throwable ->
                    Log.e("foo", "Uncaught exception")
                }
            }
            """
                )
                .indented()
        )
        .run()
        .expect(
            """
        src/com/example/app/test.kt:7: Warning: Must call getDefaultUncaughtExceptionHandler() to get the existing handler, and call existingHandler.uncaughtException(thread, throwable) from your new handler [DefaultUncaughtExceptionDelegation]
            setDefaultUncaughtExceptionHandler { thread, throwable ->
            ^
        0 errors, 1 warning
        """
        )
  }

  fun testJustSet() {
    lint()
        .files(
            kotlin(
                    """
            package com.example.app

            import android.util.Log
            import java.lang.Thread.setDefaultUncaughtExceptionHandler

            fun foo() {
                setDefaultUncaughtExceptionHandler { thread, throwable ->
                    Log.e("foo", "Uncaught exception")
                }
            }
            """
                )
                .indented(),
            java(
                    """
            package com.example.app;

            import static java.lang.Thread.setDefaultUncaughtExceptionHandler;

            import android.util.Log;

            public class Foo {
                public static void setHandler() {
                    setDefaultUncaughtExceptionHandler((thread, throwable) -> Log.e("Foo", "Uncaught exception"));
                }
            }
            """
                )
                .indented(),
        )
        .run()
        .expect(
            """
        src/com/example/app/Foo.java:9: Warning: Must call getDefaultUncaughtExceptionHandler() to get the existing handler, and call existingHandler.uncaughtException(thread, throwable) from your new handler [DefaultUncaughtExceptionDelegation]
                setDefaultUncaughtExceptionHandler((thread, throwable) -> Log.e("Foo", "Uncaught exception"));
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/example/app/test.kt:7: Warning: Must call getDefaultUncaughtExceptionHandler() to get the existing handler, and call existingHandler.uncaughtException(thread, throwable) from your new handler [DefaultUncaughtExceptionDelegation]
            setDefaultUncaughtExceptionHandler { thread, throwable ->
            ^
        0 errors, 2 warnings
        """
        )
  }

  fun testGood() {
    lint()
        .files(
            kotlin(
                    """
            package com.example.app

            import android.util.Log
            import java.lang.Thread.getDefaultUncaughtExceptionHandler
            import java.lang.Thread.setDefaultUncaughtExceptionHandler

            fun foo() {
                val handler = getDefaultUncaughtExceptionHandler()
                setDefaultUncaughtExceptionHandler { thread, throwable ->
                    Log.e("foo", "Uncaught exception")
                    handler?.uncaughtException(thread, throwable)
                }
            }
            """
                )
                .indented(),
            java(
                    """
          package com.example.myapplication39;

          import static java.lang.Thread.getDefaultUncaughtExceptionHandler;
          import static java.lang.Thread.setDefaultUncaughtExceptionHandler;

          import android.util.Log;

          import java.lang.Thread.UncaughtExceptionHandler;

          public class Foo {
              public static void setHandler() {
                  UncaughtExceptionHandler handler = getDefaultUncaughtExceptionHandler();
                  setDefaultUncaughtExceptionHandler((thread, throwable) -> {
                      Log.e("Foo", "Uncaught exception");
                      if (handler != null) {
                          handler.uncaughtException(thread, throwable);
                      }
                  });
              }
          }
          """
                )
                .indented(),
        )
        .run()
        .expectClean()
  }
}
