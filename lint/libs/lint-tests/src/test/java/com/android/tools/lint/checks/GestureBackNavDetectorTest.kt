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
import org.intellij.lang.annotations.Language

class GestureBackNavDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector {
    return GestureBackNavDetector()
  }

  fun testDocumentationExample() {
    lint()
        .files(
            manifest(MANIFEST_35_TRUE),
            java(
                    "src/test/pkg/KeyEventKeyCodeBackTest.java",
                    """
                package test.pkg;

                import android.app.Activity;
                import android.content.Context;
                import android.view.KeyEvent;

                @SuppressWarnings("unused")
                public class KeyEventKeyCodeBackTest extends Activity {

                    public boolean onKeyUp(int keyCode, KeyEvent event) {
                        if (KeyEvent.KEYCODE_BACK == keyCode) {
                          return true;
                        }
                    }

                    @Override
                    public void onBackPressed() {
                        // handle back
                    }
                }
                """,
                )
                .indented(),
        )
        .run()
        .expect(
            """
         src/test/pkg/KeyEventKeyCodeBackTest.java:11: Warning: If intercepting back events, this should be handled through the registration of callbacks; see https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture [GestureBackNavigation]
                if (KeyEvent.KEYCODE_BACK == keyCode) {
                    ~~~~~~~~~~~~~~~~~~~~~
        src/test/pkg/KeyEventKeyCodeBackTest.java:17: Warning: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            public void onBackPressed() {
                        ~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
        )
        .expectFixDiffs(
            """
        Show URL for src/test/pkg/KeyEventKeyCodeBackTest.java line 11: https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture
        Show URL for src/test/pkg/KeyEventKeyCodeBackTest.java line 17: https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture
        """
        )
  }

  fun testOverridesTrue() {
    lint()
        .files(
            manifest(MANIFEST_35_TRUE),
            java(JAVA_FILE_WITH_OVERRIDES),
            kotlin(KOTLIN_FILE_WITH_OVERRIDES),
        )
        .run()
        .expect(
            """
        src/com/example/MyActivity.java:12: Warning: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            public void onBackPressed() {
                        ~~~~~~~~~~~~~
        src/com/example/MyActivity.java:23: Warning: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            public void onBackPressed() {
                        ~~~~~~~~~~~~~
        src/com/example/kotlin/MyActivity.kt:9: Warning: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            override fun onBackPressed() {
                         ~~~~~~~~~~~~~
        src/com/example/kotlin/MyActivity.kt:15: Warning: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            override fun onBackPressed() {
                         ~~~~~~~~~~~~~
        0 errors, 4 warnings
        """
        )
  }

  fun testOverridesDefault() {
    lint()
        .files(
            manifest(MANIFEST_35_DEFAULT),
            java(JAVA_FILE_WITH_OVERRIDES),
            kotlin(KOTLIN_FILE_WITH_OVERRIDES),
        )
        .run()
        .expectClean()
  }

  fun testOverridesFalse() {
    lint()
        .files(
            manifest(MANIFEST_35_FALSE),
            java(JAVA_FILE_WITH_OVERRIDES),
            kotlin(KOTLIN_FILE_WITH_OVERRIDES),
        )
        .run()
        .expectClean()
  }

  fun testOverrides36True() {
    lint()
        .files(
            manifest(MANIFEST_36_TRUE),
            java(JAVA_FILE_WITH_OVERRIDES),
            kotlin(KOTLIN_FILE_WITH_OVERRIDES),
        )
        .run()
        .expect(
            """
        src/com/example/MyActivity.java:12: Error: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            public void onBackPressed() {
                        ~~~~~~~~~~~~~
        src/com/example/MyActivity.java:23: Error: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            public void onBackPressed() {
                        ~~~~~~~~~~~~~
        src/com/example/kotlin/MyActivity.kt:9: Error: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            override fun onBackPressed() {
                         ~~~~~~~~~~~~~
        src/com/example/kotlin/MyActivity.kt:15: Error: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            override fun onBackPressed() {
                         ~~~~~~~~~~~~~
        4 errors, 0 warnings
        """
        )
  }

  fun testOverrides36Default() {
    lint()
        .files(
            manifest(MANIFEST_36_DEFAULT),
            java(JAVA_FILE_WITH_OVERRIDES),
            kotlin(KOTLIN_FILE_WITH_OVERRIDES),
        )
        .run()
        .expect(
            """
        src/com/example/MyActivity.java:12: Error: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            public void onBackPressed() {
                        ~~~~~~~~~~~~~
        src/com/example/MyActivity.java:23: Error: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            public void onBackPressed() {
                        ~~~~~~~~~~~~~
        src/com/example/kotlin/MyActivity.kt:9: Error: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            override fun onBackPressed() {
                         ~~~~~~~~~~~~~
        src/com/example/kotlin/MyActivity.kt:15: Error: onBackPressed is no longer called for back gestures; migrate to AndroidX's backward compatible OnBackPressedDispatcher [GestureBackNavigation]
            override fun onBackPressed() {
                         ~~~~~~~~~~~~~
        4 errors, 0 warnings
        """
        )
  }

  fun testOverrides36False() {
    lint()
        .files(
            manifest(MANIFEST_36_FALSE),
            java(JAVA_FILE_WITH_OVERRIDES),
            kotlin(KOTLIN_FILE_WITH_OVERRIDES),
        )
        .run()
        .expectClean()
  }

  fun testKeyCodeBackSwitchJava() {
    val expected =
        """
            src/test/pkg/KeyEventKeyCodeBackTest.java:12: Warning: If intercepting back events, this should be handled through the registration of callbacks; see https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture [GestureBackNavigation]
                      case KeyEvent.KEYCODE_BACK:
                           ~~~~~~~~~~~~~~~~~~~~~
            0 errors, 1 warnings
            """
    lint()
        .files(
            manifest(MANIFEST_35_TRUE),
            java(
                    "src/test/pkg/KeyEventKeyCodeBackTest.java",
                    """
                package test.pkg;

                import android.app.Activity;
                import android.content.Context;
                import android.view.KeyEvent;

                @SuppressWarnings("unused")
                public class KeyEventKeyCodeBackTest extends Activity {

                    public boolean onKeyUp(int keyCode, KeyEvent event) {
                        switch (keyCode) {
                          case KeyEvent.KEYCODE_BACK:
                            break;
                          default:
                            break;
                        }
                    }
                }
                """,
                )
                .indented(),
        )
        .run()
        .expect(expected)
  }

  fun testKeyCodeIfStatementKotlin() {
    val expected =
        """
            src/test/pkg/KeyEventKeyCodeBackTest.kt:10: Warning: If intercepting back events, this should be handled through the registration of callbacks; see https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture [GestureBackNavigation]
                    if (KeyEvent.KEYCODE_BACK == keyCode) {
                        ~~~~~~~~~~~~~~~~~~~~~
            0 errors, 1 warnings
            """
    lint()
        .files(
            manifest(MANIFEST_35_TRUE),
            kotlin(
                    "src/test/pkg/KeyEventKeyCodeBackTest.kt",
                    """
                package test.pkg

                import android.app.Activity
                import android.content.Context
                import android.view.KeyEvent

                class KeyEventKeyCodeBackTest : Activity() {

                    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
                        if (KeyEvent.KEYCODE_BACK == keyCode) {
                          return true
                        }
                    }
                }
                """,
                )
                .indented(),
        )
        .run()
        .expect(expected)
  }

  fun testKeyCodeBackSwitchKotlin() {
    val expected =
        """
            src/test/pkg/KeyEventKeyCodeBackTest.kt:10: Warning: If intercepting back events, this should be handled through the registration of callbacks; see https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture [GestureBackNavigation]
                      KeyEvent.KEYCODE_BACK -> println("keycode back")
                      ~~~~~~~~~~~~~~~~~~~~~
            0 errors, 1 warnings
            """
    lint()
        .files(
            manifest(MANIFEST_35_TRUE),
            kotlin(
                    "src/test/pkg/KeyEventKeyCodeBackTest.kt",
                    """
                package test.pkg

                import android.app.Activity
                import android.view.KeyEvent

                class KeyEventKeyCodeBackTest : Activity() {

                    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
                        when (keyCode) {
                          KeyEvent.KEYCODE_BACK -> println("keycode back")
                          else -> println("else")
                        }
                    }
                }
                """,
                )
                .indented(),
        )
        .run()
        .expect(expected)
  }

  fun testDialogOnKeyListenerSwitchKotlin() {
    val expected =
        """
            src/test/pkg/KeyEventKeyCodeBackTest.kt:11: Warning: If intercepting back events, this should be handled through the registration of callbacks; see https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture [GestureBackNavigation]
                      KeyEvent.KEYCODE_BACK -> println("keycode back")
                      ~~~~~~~~~~~~~~~~~~~~~
            0 errors, 1 warnings
            """
    lint()
        .files(
            manifest(MANIFEST_35_TRUE),
            kotlin(
                    "src/test/pkg/KeyEventKeyCodeBackTest.kt",
                    """
                package test.pkg

                import android.view.KeyEvent
                import android.content.DialogInterface
                import android.content.DialogInterface.OnKeyListener

                class KeyEventKeyCodeBackTest : OnKeyListener() {

                    override fun onKey(dialog: DialogInterface, keyCode: Int, event: KeyEvent): Boolean {
                        when (keyCode) {
                          KeyEvent.KEYCODE_BACK -> println("keycode back")
                          else -> println("else")
                        }
                    }
                }
                """,
                )
                .indented(),
        )
        .run()
        .expect(expected)
  }

  fun testKeyCodeCheckInUtil() {
    // We do not report references to KEYCODE_BACK in utility methods.
    // We only report references that are trivially in an Activity or Dialog subclass.
    lint()
        .files(
            manifest(MANIFEST_35_TRUE),
            kotlin(
                    "src/test/pkg/KeyEventKeyCodeBackTest.kt",
                    """
                package test.pkg

                import android.app.Activity
                import android.view.KeyEvent

                class KeyEventKeyCodeBackTest : Activity() {
                   override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
                     Util.handleKeyCode(keyCode)
                   }
                }

                 class Util {
                     fun handleKeyCode(keyCode: Int) {
                        when (keyCode) {
                          KeyEvent.KEYCODE_BACK -> println("keycode back")
                          else -> println("else")
                        }
                     }
                 }
                """,
                )
                .indented(),
        )
        .run()
        .expectClean()
  }

  fun testKeyUpCleanKotlin() {
    lint()
        .files(
            manifest(MANIFEST_35_TRUE),
            kotlin(
                    "src/test/pkg/KeyEventKeyCodeBackTest.kt",
                    """
                package test.pkg

                import android.app.Activity
                import android.view.KeyEvent
                import android.util.Log

                class KeyEventKeyCodeBackTest : Activity() {

                   override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
                     Log.i("Is KEYCODE_BACK", KeyEvent.KEYCODE_BACK == keyCode)
                   }
                }
                """,
                )
                .indented(),
        )
        .run()
        .expectClean()
  }

  fun testEnableBackInvokeDisabled() {
    lint()
        .files(
            manifest(
                """
                <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    package="test.pkg">

                    <application
                        android:icon="@drawable/ic_launcher"
                        android:label="@string/app_name" >
                        <activity
                            android:name=".KeyEventKeyCodeBackTest"
                            android:label="@string/app_name" >
                            <intent-filter>
                                <action android:name="android.intent.action.MAIN" />

                                <category android:name="android.intent.category.LAUNCHER" />
                            </intent-filter>
                        </activity>
                    </application>

                </manifest>
            """
            ),
            kotlin(
                    "src/test/pkg/KeyEventKeyCodeBackTest.kt",
                    """
                package test.pkg

                import android.app.Activity
                import android.view.KeyEvent

                class KeyEventKeyCodeBackTest : Activity() {

                   override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
                     Util.handleKeyCode(keyCode)
                   }
                }

                 class Util {
                     fun handleKeyCode(keyCode: Int) {
                        when (keyCode) {
                          KeyEvent.KEYCODE_BACK -> println("keycode back")
                          else -> println("else")
                        }
                     }
                 }
                """,
                )
                .indented(),
        )
        .run()
        .expectClean()
  }

  fun testApi36() {
    lint()
        .files(
            manifest(MANIFEST_36_DEFAULT),
            kotlin(
                    "src/test/pkg/KeyEventKeyCodeBackTest.kt",
                    """
            package test.pkg

            import android.app.Activity
            import android.view.KeyEvent

            class KeyEventKeyCodeBackTest : Activity() {
              override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
                when (keyCode) {
                  KeyEvent.KEYCODE_BACK -> println("keycode back")
                  else -> println("else")
                }
              }
            }
            """,
                )
                .indented(),
        )
        .run()
        .expect(
            """
        src/test/pkg/KeyEventKeyCodeBackTest.kt:9: Error: If intercepting back events, this should be handled through the registration of callbacks; see https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture [GestureBackNavigation]
              KeyEvent.KEYCODE_BACK -> println("keycode back")
              ~~~~~~~~~~~~~~~~~~~~~
        1 error
        """
        )
  }

  fun testApi36OptOut() {
    lint()
        .files(
            manifest(MANIFEST_36_FALSE),
            kotlin(
                    "src/test/pkg/KeyEventKeyCodeBackTest.kt",
                    """
            package test.pkg

            import android.app.Activity
            import android.view.KeyEvent

            class KeyEventKeyCodeBackTest : Activity() {
              override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
                when (keyCode) {
                  KeyEvent.KEYCODE_BACK -> println("keycode back")
                  else -> println("else")
                }
              }
            }
            """,
                )
                .indented(),
        )
        .run()
        .expectClean()
  }
}

@Language("JAVA")
private const val JAVA_FILE_WITH_OVERRIDES =
    """
package com.example;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;

import androidx.annotation.NonNull;

public class MyActivity extends Activity {
    @Override
    public void onBackPressed() {
        // handle back
    }
}

class MyDialog extends Dialog {
    public MyDialog(@NonNull Context context) {
        super(context);
    }

    @Override
    public void onBackPressed() {
        // handle back
    }
}
"""

@Language("kotlin")
private const val KOTLIN_FILE_WITH_OVERRIDES =
    """
package com.example.kotlin

import android.app.Activity
import android.app.Dialog
import android.content.Context

class MyActivity : Activity() {
    override fun onBackPressed() {
        // handle back
    }
}

class MyDialog(context: Context) : Dialog(context) {
    override fun onBackPressed() {
        // handle back
    }
}
"""

@Language("XML")
private const val MANIFEST_35_DEFAULT =
    """
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="test.pkg">

    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35" />

    <application
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name" >
        <activity
            android:name=".KeyEventKeyCodeBackTest"
            android:label="@string/app_name"
            android:exported="true" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""

@Language("XML")
private const val MANIFEST_35_TRUE =
    """
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="test.pkg">

    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35" />

    <application
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:enableOnBackInvokedCallback="true" >
        <activity
            android:name=".KeyEventKeyCodeBackTest"
            android:label="@string/app_name"
            android:exported="true" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""

@Language("XML")
private const val MANIFEST_35_FALSE =
    """
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="test.pkg">

    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="35" />

    <application
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:enableOnBackInvokedCallback="false" >
        <activity
            android:name=".KeyEventKeyCodeBackTest"
            android:label="@string/app_name"
            android:exported="true" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""

@Language("XML")
private const val MANIFEST_36_DEFAULT =
    """
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="test.pkg">

    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="36" />

    <application
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name" >
        <activity
            android:name=".KeyEventKeyCodeBackTest"
            android:label="@string/app_name"
            android:exported="true" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""

@Language("XML")
private const val MANIFEST_36_TRUE =
    """
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="test.pkg">

    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="36" />

    <application
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:enableOnBackInvokedCallback="true" >
        <activity
            android:name=".KeyEventKeyCodeBackTest"
            android:label="@string/app_name"
            android:exported="true" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""

@Language("XML")
private const val MANIFEST_36_FALSE =
    """
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    package="test.pkg">

    <uses-sdk android:minSdkVersion="24" android:targetSdkVersion="36" />

    <application
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:enableOnBackInvokedCallback="false" >
        <activity
            android:name=".KeyEventKeyCodeBackTest"
            android:label="@string/app_name"
            android:exported="true" >
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />

                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
"""
