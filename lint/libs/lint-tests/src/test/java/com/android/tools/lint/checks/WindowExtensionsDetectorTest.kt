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

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.TextFormat

class WindowExtensionsDetectorTest : AbstractCheckTest() {
  override fun lint(): TestLintTask {
    return super.lint()
      .textFormat(TextFormat.RAW)
      // We deliberately haven't added support for possible but unlikely scenarios like
      // this one:
      //    when {
      //       WindowSdkExtensions.getInstance().extensionVersion < 6 -> { ... }
      //       else -> <safe code here>
      //    }
      .skipTestModes(TestMode.IF_TO_WHEN)
  }

  override fun getDetector(): Detector {
    return WindowExtensionsDetector()
  }

  fun testDocumentationExample() {
    lint()
      .files(
        kotlin(
            """
            @file:Suppress("unused", "UnusedVariable", "ControlFlowWithEmptyBody")

            package test.pkg

            import android.content.Context
            import androidx.window.RequiresWindowSdkExtension
            import androidx.window.WindowSdkExtensions
            import androidx.window.layout.WindowInfoTracker

            fun testPositive(context: Context) {
                val windowInfoTracker = WindowInfoTracker.getOrCreate(context)
                val supportedPostures = windowInfoTracker.supportedPostures // ERROR 1

            }
            """
          )
          .indented(),
        *stubs,
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:12: Error: Field requires window SDK extension level 6: `androidx.window.layout.WindowInfoTracker#getSupportedPostures` [RequiresWindowSdk]
            val supportedPostures = windowInfoTracker.supportedPostures // ERROR 1
                                                      ~~~~~~~~~~~~~~~~~
        1 error
        """
      )
  }

  fun testNegative() {
    lint()
      .files(
        kotlin(
            """
            @file:Suppress("unused", "UnusedVariable", "ControlFlowWithEmptyBody")

            package test.pkg

            import android.content.Context
            import androidx.window.RequiresWindowSdkExtension
            import androidx.window.WindowSdkExtensions
            import androidx.window.layout.WindowInfoTracker

            fun testNegative1(context: Context) {
                val windowInfoTracker = WindowInfoTracker.getOrCreate(context)
                if (WindowSdkExtensions.getInstance().extensionVersion >= 6) {
                    val supportedPostures = windowInfoTracker.supportedPostures // OK 1
                }
            }

            @RequiresWindowSdkExtension(6)
            fun testNegative2(context: Context) {
                val windowInfoTracker = WindowInfoTracker.getOrCreate(context)
                val supportedPostures = windowInfoTracker.supportedPostures // OK 2
            }

            fun testNegative3(context: Context) {
                val windowInfoTracker = WindowInfoTracker.getOrCreate(context)
                if (WindowSdkExtensions.getInstance().extensionVersion > 5) {
                    val supportedPostures = windowInfoTracker.supportedPostures // OK 3
                }
            }

            fun testNegative4(context: Context) {
                val windowInfoTracker = WindowInfoTracker.getOrCreate(context)
                if (WindowSdkExtensions.getInstance().extensionVersion > 5) {
                    val supportedPostures = windowInfoTracker.supportedPostures // OK 4
                }
            }
            """
          )
          .indented(),
        kotlin(
            """
            @file:RequiresWindowSdkExtension(6)

            package test.pkg

            import android.content.Context
            import androidx.window.RequiresWindowSdkExtension
            import androidx.window.WindowSdkExtensions

            fun testNegativePackage(context: Context) {
                val windowInfoTracker = WindowInfoTracker.getOrCreate(context)
                val supportedPostures = windowInfoTracker.supportedPostures // OK 5 -- suppressed on file
            }
            """
          )
          .indented(),
        java(
            """
            package test.pkg;

            import android.content.Context;

            import androidx.window.WindowSdkExtensions;
            import androidx.window.layout.SupportedPosture;
            import androidx.window.layout.WindowInfoTracker;

            import java.util.List;

            public class JavaTest {
                public void test(Context context) {
                    WindowInfoTracker windowInfoTracker = WindowInfoTracker.getOrCreate(context);
                    if (WindowSdkExtensions.getInstance().getExtensionVersion() >= 6) {
                        List<SupportedPosture> supportedPostures = windowInfoTracker.getSupportedPostures(); // OK 6
                    }
                }
            }
            """
          )
          .indented(),
        *stubs,
      )
      .run()
      .expectClean()
  }

  fun testPositive() {
    lint()
      .files(
        kotlin(
            """
            @file:Suppress("unused", "UnusedVariable", "ControlFlowWithEmptyBody")

            package test.pkg

            import android.content.Context
            import androidx.window.RequiresWindowSdkExtension
            import androidx.window.WindowSdkExtensions
            import androidx.window.layout.WindowInfoTracker

            @RequiresWindowSdkExtension(5) // Inadequate version
            fun testPositive(context: Context) {
                val windowInfoTracker = WindowInfoTracker.getOrCreate(context)
                val supportedPostures = windowInfoTracker.supportedPostures // ERROR 1

            }

            fun testIfElse(context: Context) {
                val windowInfoTracker = WindowInfoTracker.getOrCreate(context)
                if (WindowSdkExtensions.getInstance().extensionVersion < 6) {
                    val supportedPostures = windowInfoTracker.supportedPostures // ERROR 2
                } else {
                    val supportedPostures = windowInfoTracker.supportedPostures // OK 1
                }
            }
            """
          )
          .indented(),
        *stubs,
      )
      .run()
      .expect(
        """
        src/test/pkg/test.kt:13: Error: Field requires window SDK extension level 6 (current is 5): `androidx.window.layout.WindowInfoTracker#getSupportedPostures` [RequiresWindowSdk]
            val supportedPostures = windowInfoTracker.supportedPostures // ERROR 1
                                                      ~~~~~~~~~~~~~~~~~
        src/test/pkg/test.kt:20: Error: Field requires window SDK extension level 6 (current is 1): `androidx.window.layout.WindowInfoTracker#getSupportedPostures` [RequiresWindowSdk]
                val supportedPostures = windowInfoTracker.supportedPostures // ERROR 2
                                                          ~~~~~~~~~~~~~~~~~
        2 errors
        """
      )
  }

  @Suppress("ControlFlowWithEmptyBody")
  fun testBinaryAndPolyadicExpressions() {
    lint()
      .files(
        kotlin(
            """
            package test.pkg

            import android.content.Context
            import androidx.window.RequiresWindowSdkExtension
            import androidx.window.WindowSdkExtensions

            fun check(): Boolean = true
            @RequiresWindowSdkExtension(5) fun requires5(): Boolean = true

            fun testAnd(extensions: WindowSdkExtensions) {
                if (extensions.extensionVersion >= 5 && requires5()) { // OK 1
                    // something
                }

                if (check() && extensions.extensionVersion >= 5 && requires5()) { // OK 2
                    // something
                }
            }

            fun testOr(extensions: WindowSdkExtensions) {
                if (extensions.extensionVersion < 5 || requires5()) { // OK 3
                    // something
                }

                if (extensions.extensionVersion < 5 || check() || requires5()) { // OK 4
                    // something
                }
            }

            fun testVariables(extensions: WindowSdkExtensions) {
                val ok1 = extensions.extensionVersion >= 5 && requires5() // OK 5
                val ok2 = extensions.extensionVersion < 5 || requires5()  // OK 6
            }
            """
          )
          .indented(),
        *stubs,
      )
      .run()
      .expectClean()
  }

  fun testBaselineMatching() {
    val detector = WindowExtensionsDetector()
    assertTrue(
      detector.sameMessage(
        WindowExtensionsDetector.ISSUE,
        "Field requires window SDK extension level 6 (current is 3): `androidx.window.layout.WindowInfoTracker#getSupportedPostures` [RequiresWindowSdk]",
        "Field requires window SDK extension level 6: `androidx.window.layout.WindowInfoTracker#getSupportedPostures` [RequiresWindowSdk]",
      )
    )
    assertFalse(
      detector.sameMessage(
        WindowExtensionsDetector.ISSUE,
        "Field requires window SDK extension level 6: `androidx.window.layout.WindowInfoTracker#getSupportedPostures` [RequiresWindowSdk]",
        "Field requires window SDK extension level 6: `androidx.window.layout.WindowInfoTracker#getPostures` [RequiresWindowSdk]",
      )
    )
    assertTrue(
      detector.sameMessage(
        WindowExtensionsDetector.ISSUE,
        "Field requires window SDK extension level 6 (current is 3): `androidx.window.layout.WindowInfoTracker#getSupportedPostures` [RequiresWindowSdk]",
        "Field requires window SDK extension level 6 (current is 2): `androidx.window.layout.WindowInfoTracker#getSupportedPostures` [RequiresWindowSdk]",
      )
    )
  }
}

private val stubs: Array<TestFile> =
  arrayOf(
    kotlin(
        """
        // HIDE-FROM-DOCUMENTATION
        package androidx.window

        @MustBeDocumented
        @Retention(value = AnnotationRetention.BINARY)
        @Target(
            AnnotationTarget.CLASS,
            AnnotationTarget.FUNCTION,
            AnnotationTarget.PROPERTY_GETTER,
            AnnotationTarget.PROPERTY_SETTER,
            AnnotationTarget.CONSTRUCTOR,
            AnnotationTarget.FIELD,
            AnnotationTarget.PROPERTY,
        )
        annotation class RequiresWindowSdkExtension(
            val version: Int
        )
        """
      )
      .indented(),
    kotlin(
        """
        // HIDE-FROM-DOCUMENTATION
        package androidx.window

        abstract class WindowSdkExtensions {
            open val extensionVersion: Int = TODO()

            companion object {
                @JvmStatic
                fun getInstance(): WindowSdkExtensions = TODO()
            }
        }
        """
      )
      .indented(),
    kotlin(
        """
        // HIDE-FROM-DOCUMENTATION
        package androidx.window.layout

        import android.content.Context
        import androidx.window.RequiresWindowSdkExtension
        interface WindowInfoTracker {
            // Note -- this is not the same as the current annotation on this API; see b/391938429#comment4
            @get:RequiresWindowSdkExtension(version = 6)
            val supportedPostures: List<SupportedPosture>
                get() {
                    throw NotImplementedError("Method was not implemented.")
                }
            companion object {
                @JvmName("getOrCreate")
                @JvmStatic
                fun getOrCreate(context: Context): WindowInfoTracker {
                  throw NotImplementedError("Method was not implemented.")
                }
            }
        }

        class SupportedPosture
        """
      )
      .indented(),
  )
