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

import com.android.tools.lint.checks.infrastructure.TestMode

class ImplicitUriGrantDetectorTest : AbstractCheckTest() {

  override fun getDetector() = ImplicitUriGrantDetector()

  override fun getIssues() = listOf(ImplicitUriGrantDetector.ISSUE)

  fun testDocumentationExample() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "image/png"
                intent.putExtra(Intent.EXTRA_STREAM, fileUri)
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.kt:9: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            val intent = Intent(Intent.ACTION_SEND)
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.kt line 9: Add FLAG_GRANT_READ_URI_PERMISSION:
        @@ -9 +9 @@
        -    val intent = Intent(Intent.ACTION_SEND)
        +    val intent = Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        """
      )
  }

  fun testImageCaptureMissingReadWriteGrantKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri
            import android.provider.MediaStore

            class TestActivity {
              fun takePhoto(context: Context, photoUri: Uri) {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.kt:10: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION and FLAG_GRANT_WRITE_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.kt line 10: Add FLAG_GRANT_READ_URI_PERMISSION and FLAG_GRANT_WRITE_URI_PERMISSION:
        @@ -10 +10 @@
        -    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        +    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        """
      )
  }

  fun testImageCaptureMissingWriteGrantKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri
            import android.provider.MediaStore

            class TestActivity {
              fun takePhoto(context: Context, photoUri: Uri) {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.kt:10: Warning: This intent attaches a URI but is missing FLAG_GRANT_WRITE_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.kt line 10: Add FLAG_GRANT_WRITE_URI_PERMISSION:
        @@ -10 +10 @@
        -    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        +    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        """
      )
  }

  fun testSendActionWithSetFlagsNoFixKotlin() {
    // No addFlags quick-fix because the flags are overwritten via setFlags.
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "image/png"
                intent.putExtra(Intent.EXTRA_STREAM, fileUri)
                intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.kt:9: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            val intent = Intent(Intent.ACTION_SEND)
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs("")
  }

  fun testSendActionWithAddFlagsCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testIntentConstructorFromAnotherIntentKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                }
                val another = Intent(intent)
                context.startActivity(another)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.kt:9: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            val intent = Intent(Intent.ACTION_SEND).apply {
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.kt line 9: Add FLAG_GRANT_READ_URI_PERMISSION:
        @@ -9 +9 @@
        -    val intent = Intent(Intent.ACTION_SEND).apply {
        +    val intent = Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
        """
      )
  }

  fun testIntentConstructorFromAnotherIntentWithFlagCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "text/plain"
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                }

                val another = Intent(intent)
                with(another) {
                  addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                context.startActivity(another)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testSetClassCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "image/png"
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                  setClass(context, TestActivity::class.java)
                }
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testSetPackageCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "image/png"
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                  setPackage(context.packageName)
                }
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testSetClassNameWithContextCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "image/png"
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                  setClassName(context, "com.example.app.ReceiverActivity")
                }
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testSetClassNameWithPackageCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "image/png"
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                  setClassName(context.packageName, "com.example.app.ReceiverActivity")
                }
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testSetComponentWithContextCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.ComponentName
            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "image/png"
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                  setComponent(ComponentName(context, TestActivity::class.java))
                }
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testPropertyComponentCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.ComponentName
            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "image/png"
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                  component = ComponentName(context, TestActivity::class.java)
                }
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .skipTestModes(TestMode.PARENTHESIZED) // messes up the assignment to component
      .run()
      .expectClean()
  }

  fun testPropertyPackageCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "image/png"
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                  `package` = context.packageName
                }
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .skipTestModes(TestMode.PARENTHESIZED) // messes up the assignment to package
      .run()
      .expectClean()
  }

  fun testLocalVariableComponentCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.ComponentName
            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                val comp = ComponentName(context, TestActivity::class.java)
                val intent = Intent(Intent.ACTION_SEND).apply {
                  type = "image/png"
                  putExtra(Intent.EXTRA_STREAM, fileUri)
                  setComponent(comp)
                }
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testSendActionWithChooserWarnsOnceKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val share = Intent(Intent.ACTION_SEND)
                share.type = "image/png"
                share.putExtra(Intent.EXTRA_STREAM, fileUri)
                context.startActivity(Intent.createChooser(share, "Share via"))
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.kt:9: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            val share = Intent(Intent.ACTION_SEND)
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.kt line 9: Add FLAG_GRANT_READ_URI_PERMISSION:
        @@ -9 +9 @@
        -    val share = Intent(Intent.ACTION_SEND)
        +    val share = Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        """
      )
  }

  fun testDecoyIntentWithFlagsDoesNotSuppressWarningKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val decoy = Intent(Intent.ACTION_VIEW)
                decoy.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val share = Intent(Intent.ACTION_SEND)
                share.type = "image/png"
                share.putExtra(Intent.EXTRA_STREAM, fileUri)
                context.startActivity(share)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.kt:11: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            val share = Intent(Intent.ACTION_SEND)
                        ~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.kt line 11: Add FLAG_GRANT_READ_URI_PERMISSION:
        @@ -11 +11 @@
        -    val share = Intent(Intent.ACTION_SEND)
        +    val share = Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        """
      )
  }

  fun testIntentExpressionKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                context.startActivity(
                  Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                  }
                )
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.kt:10: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
              Intent(Intent.ACTION_SEND).apply {
              ~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.kt line 10: Add FLAG_GRANT_READ_URI_PERMISSION:
        @@ -10 +10 @@
        -      Intent(Intent.ACTION_SEND).apply {
        +      Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {
        """
      )
  }

  fun testIntentEscapesIntoHelperMethodCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.putExtra(Intent.EXTRA_STREAM, fileUri)
                configure(intent)
                context.startActivity(intent)
              }

              private fun configure(intent: Intent) {
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testIntentEscapesIntoExtensionFunctionCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            fun Intent.addSharingFlags() {
              addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.putExtra(Intent.EXTRA_STREAM, fileUri)
                intent.addSharingFlags()
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testFlagsPropertyCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.putExtra(Intent.EXTRA_STREAM, fileUri)
                intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testNonConstantFlagsCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.putExtra(Intent.EXTRA_STREAM, fileUri)
                intent.addFlags(computeFlags(fileUri))
                context.startActivity(intent)
              }

              private fun computeFlags(uri: Uri): Int {
                return if (uri.scheme == "content") Intent.FLAG_GRANT_READ_URI_PERMISSION else 0
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testImageCaptureSetFlagsCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri
            import android.provider.MediaStore

            class TestActivity {
              fun takePhoto(context: Context, photoUri: Uri) {
                val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testSendActionWithoutUriPayloadCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareText(context: Context) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "text/plain"
                intent.putExtra(Intent.EXTRA_TEXT, "Hello")
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testSendActionNeverLaunchedCleanKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun buildIntent(fileUri: Uri) {
                val intent = Intent(Intent.ACTION_SEND)
                intent.type = "image/png"
                intent.putExtra(Intent.EXTRA_STREAM, fileUri)
                // The intent is never launched, so no permission grant is needed.
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testSendMultipleActionKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFiles(context: Context, fileUris: ArrayList<Uri>) {
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
                intent.type = "image/png"
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, fileUris)
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.kt:9: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
                         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.kt line 9: Add FLAG_GRANT_READ_URI_PERMISSION:
        @@ -9 +9 @@
        -    val intent = Intent(Intent.ACTION_SEND_MULTIPLE)
        +    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        """
      )
  }

  fun testActionPropertyKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.Intent
            import android.net.Uri

            class TestActivity {
              fun shareFile(context: Context, fileUri: Uri) {
                val intent = Intent()
                intent.action = Intent.ACTION_SEND
                intent.putExtra(Intent.EXTRA_STREAM, fileUri)
                context.startActivity(intent)
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.kt:9: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            val intent = Intent()
                         ~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.kt line 9: Add FLAG_GRANT_READ_URI_PERMISSION:
        @@ -9 +9 @@
        -    val intent = Intent()
        +    val intent = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        """
      )
  }

  // ==========================================================================
  // Java Tests
  // ==========================================================================

  fun testSendActionMissingReadGrantJava() {
    lint()
      .files(
        java(
            """
            package com.example.app;

            import android.content.Context;
            import android.content.Intent;
            import android.net.Uri;

            public class TestActivity {
              public void shareFile(Context context, Uri fileUri) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/png");
                intent.putExtra(Intent.EXTRA_STREAM, fileUri);
                context.startActivity(intent);
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.java:9: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            Intent intent = new Intent(Intent.ACTION_SEND);
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.java line 9: Add FLAG_GRANT_READ_URI_PERMISSION:
        @@ -9 +9 @@
        -    Intent intent = new Intent(Intent.ACTION_SEND);
        +    Intent intent = new Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        """
      )
  }

  fun testSendActionWithExplicitGrantFlagCleanJava() {
    lint()
      .files(
        java(
            """
            package com.example.app;

            import android.content.Context;
            import android.content.Intent;
            import android.net.Uri;

            public class TestActivity {
              public void shareFile(Context context, Uri fileUri) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/png");
                intent.putExtra(Intent.EXTRA_STREAM, fileUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(intent);
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testImageCaptureMissingReadWriteGrantJava() {
    lint()
      .files(
        java(
            """
            package com.example.app;

            import android.content.Context;
            import android.content.Intent;
            import android.net.Uri;
            import android.provider.MediaStore;

            public class TestActivity {
              public void takePhoto(Context context, Uri photoUri) {
                Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                context.startActivity(intent);
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.java:10: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION and FLAG_GRANT_WRITE_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.java line 10: Add FLAG_GRANT_READ_URI_PERMISSION and FLAG_GRANT_WRITE_URI_PERMISSION:
        @@ -10 +10 @@
        -    Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        +    Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        """
      )
  }

  fun testContextConstructorCleanJava() {
    lint()
      .files(
        java(
            """
            package com.example.app;

            import android.content.Context;
            import android.content.Intent;
            import android.net.Uri;

            public class TestActivity {
              public void startInternal(Context context, Uri fileUri) {
                Intent intent = new Intent(context, TestActivity.class);
                intent.setAction(Intent.ACTION_SEND);
                intent.putExtra(Intent.EXTRA_STREAM, fileUri);
                context.startActivity(intent);
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testSetPackageCleanJava() {
    lint()
      .files(
        java(
            """
            package com.example.app;

            import android.content.Context;
            import android.content.Intent;
            import android.net.Uri;

            public class TestActivity {
              public void shareInternal(Context context, Uri fileUri) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/png");
                intent.putExtra(Intent.EXTRA_STREAM, fileUri);
                intent.setPackage(context.getPackageName());
                context.startActivity(intent);
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testSetComponentCleanJava() {
    lint()
      .files(
        java(
            """
            package com.example.app;

            import android.content.ComponentName;
            import android.content.Context;
            import android.content.Intent;
            import android.net.Uri;

            public class TestActivity {
              public void shareInternal(Context context, Uri fileUri) {
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("image/png");
                intent.putExtra(Intent.EXTRA_STREAM, fileUri);
                intent.setComponent(new ComponentName(context, TestActivity.class));
                context.startActivity(intent);
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testUnrelatedStartActivityMethodIgnoredJava() {
    lint()
      .files(
        java(
            """
            package com.example.app;

            import android.app.PendingIntent;
            import android.content.Context;
            import android.content.Intent;
            import android.net.Uri;

            public class TestActivity {
              public static class Robot {
                public void startActivity(PendingIntent plan) {}
              }

              public void run(Context context, Robot robot, PendingIntent plan, Uri fileUri) {
                Intent share = new Intent(Intent.ACTION_SEND);
                share.putExtra(Intent.EXTRA_STREAM, fileUri);
                context.startActivity(share);
                robot.startActivity(plan);
              }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/TestActivity.java:14: Warning: This intent attaches a URI but is missing FLAG_GRANT_READ_URI_PERMISSION; the receiving app will not be granted access to the URI on Android 18 and higher [MissingExplicitUriGrant]
            Intent share = new Intent(Intent.ACTION_SEND);
                           ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
      .expectFixDiffs(
        """
        Fix for src/com/example/app/TestActivity.java line 14: Add FLAG_GRANT_READ_URI_PERMISSION:
        @@ -14 +14 @@
        -    Intent share = new Intent(Intent.ACTION_SEND);
        +    Intent share = new Intent(Intent.ACTION_SEND).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        """
      )
  }
}
