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

import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Issue

class ImplicitUriGrantDetectorTest : AbstractCheckTest() {

  override fun getDetector(): Detector = ImplicitUriGrantDetector()

  override fun getIssues(): List<Issue> = listOf(ImplicitUriGrantDetector.ISSUE)

  // ==========================================================================
  // Kotlin Tests
  // ==========================================================================

  fun testDocumentationExample() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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
        src/test/pkg/TestActivity.kt:12: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(intent)
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/test/pkg/TestActivity.kt line 12: Add explicit URI grant flags:
        @@ -11,0 +12 @@
        +        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        """
      )
  }

  fun testImageCaptureMissingWriteGrantKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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
        src/test/pkg/TestActivity.kt:12: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(intent)
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/test/pkg/TestActivity.kt line 12: Add explicit URI grant flags:
        @@ -11,0 +12 @@
        +        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        """
      )
  }

  fun testSendActionWithExternalAddFlagsCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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

  fun testIntraAppSetClassCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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

  fun testIntraAppSetPackageCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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

  fun testIntraAppSetClassNameWithContextCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

          import android.content.Context
          import android.content.Intent
          import android.net.Uri

          class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                  val intent = Intent(Intent.ACTION_SEND).apply {
                      type = "image/png"
                      putExtra(Intent.EXTRA_STREAM, fileUri)
                      setClassName(context, "test.pkg.ReceiverActivity")
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

  fun testIntraAppSetClassNameWithPackageCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

          import android.content.Context
          import android.content.Intent
          import android.net.Uri

          class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                  val intent = Intent(Intent.ACTION_SEND).apply {
                      type = "image/png"
                      putExtra(Intent.EXTRA_STREAM, fileUri)
                      setClassName(context.packageName, "test.pkg.ReceiverActivity")
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

  fun testIntraAppSetClassNameWithLocalVariableAliasCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

          import android.content.Context
          import android.content.Intent
          import android.net.Uri

          class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                  val intent = Intent(Intent.ACTION_SEND).apply {
                      type = "image/png"
                      putExtra(Intent.EXTRA_STREAM, fileUri)
                      val pn = context.packageName
                      setClassName(pn, "test.pkg.ReceiverActivity")
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

  fun testIntraAppSetComponentWithContextCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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

  fun testIntraAppSetComponentWithPackageNameCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

          import android.content.ComponentName
          import android.content.Context
          import android.content.Intent
          import android.net.Uri

          class TestActivity {
              fun shareInternal(context: Context, fileUri: Uri) {
                  val intent = Intent(Intent.ACTION_SEND).apply {
                      type = "image/png"
                      putExtra(Intent.EXTRA_STREAM, fileUri)
                      setComponent(ComponentName(context.packageName, "test.pkg.ReceiverActivity"))
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

  fun testIntraAppPropertyComponentCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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
      .run()
      .expectClean()
  }

  fun testIntraAppPropertyPackageCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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
      .run()
      .expectClean()
  }

  fun testIntraAppLocalVariableComponentCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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

  fun testIntraAppFieldComponentCleanKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        manifest().pkg("test.pkg"),
        kotlin(
            """
          package test.pkg

          import android.content.ComponentName
          import android.content.Context
          import android.content.Intent
          import android.net.Uri

          class TestActivity {
              private val localComp = ComponentName("test.pkg", "test.pkg.ReceiverActivity")

              fun shareInternal(context: Context, fileUri: Uri) {
                  val intent = Intent(Intent.ACTION_SEND).apply {
                      type = "image/png"
                      putExtra(Intent.EXTRA_STREAM, fileUri)
                      setComponent(localComp)
                  }
                  context.startActivity(intent)
              }
          }
          """
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  fun testExternalSetClassNameWarnsKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

          import android.content.Context
          import android.content.Intent
          import android.net.Uri

          class TestActivity {
              fun shareExternal(context: Context, fileUri: Uri) {
                  val intent = Intent(Intent.ACTION_SEND).apply {
                      type = "image/png"
                      putExtra(Intent.EXTRA_STREAM, fileUri)
                      setClassName("com.test", "com.test.ReceiverActivity")
                  }
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
        src/test/pkg/TestActivity.kt:14: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(intent)
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testExternalSetPackageWarnsKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

          import android.content.Context
          import android.content.Intent
          import android.net.Uri

          class TestActivity {
              fun shareExternal(context: Context, fileUri: Uri) {
                  val intent = Intent(Intent.ACTION_SEND).apply {
                      type = "image/png"
                      putExtra(Intent.EXTRA_STREAM, fileUri)
                      setPackage("com.test")
                  }
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
        src/test/pkg/TestActivity.kt:14: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(intent)
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testStringLiteralPackageNameWarnsKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

          import android.content.Context
          import android.content.Intent
          import android.net.Uri

          class TestActivity {
              fun shareExternal(context: Context, fileUri: Uri) {
                  val intent = Intent(Intent.ACTION_SEND).apply {
                      type = "image/png"
                      putExtra(Intent.EXTRA_STREAM, fileUri)
                      val pn = "packageName"
                      setClassName(pn, "test")
                  }
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
        src/test/pkg/TestActivity.kt:15: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(intent)
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testExternalSetComponentWarnsKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

          import android.content.ComponentName
          import android.content.Context
          import android.content.Intent
          import android.net.Uri

          class TestActivity {
              fun shareExternal(context: Context, fileUri: Uri) {
                  val intent = Intent(Intent.ACTION_SEND).apply {
                      type = "image/png"
                      putExtra(Intent.EXTRA_STREAM, fileUri)
                      setComponent(ComponentName("com.external.app", "com.external.app.ShareActivity"))
                  }
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
        src/test/pkg/TestActivity.kt:15: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(intent)
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testExternalLocalVariableComponentWarnsKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

          import android.content.ComponentName
          import android.content.Context
          import android.content.Intent
          import android.net.Uri

          class TestActivity {
              fun shareExternal(context: Context, fileUri: Uri) {
                  val comp = ComponentName("com.external.app", "com.external.app.ShareActivity")
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
      .expect(
        """
        src/test/pkg/TestActivity.kt:16: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(intent)
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testSendActionWithChooserWarnsOnceKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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
        src/test/pkg/TestActivity.kt:12: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(Intent.createChooser(share, "Share via"))
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/test/pkg/TestActivity.kt line 12: Add explicit URI grant flags:
        @@ -11,0 +12 @@
        +        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        """
      )
  }

  fun testDecoyIntentWithFlagsDoesNotSuppressWarningKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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
        src/test/pkg/TestActivity.kt:14: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(share)
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/test/pkg/TestActivity.kt line 14: Add explicit URI grant flags:
        @@ -13,0 +14 @@
        +        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        """
      )
  }

  fun testAnonymousInlineIntentWarnsWithNoFixKotlin() {
    lint()
      .allowMissingSdk()
      .files(
        kotlin(
            """
          package test.pkg

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
        src/test/pkg/TestActivity.kt:9: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(
                ^
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs("")
  }

  // ==========================================================================
  // Java Tests
  // ==========================================================================

  fun testSendActionMissingReadGrantJava() {
    lint()
      .allowMissingSdk()
      .files(
        java(
            """
          package test.pkg;

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
        src/test/pkg/TestActivity.java:12: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(intent);
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/test/pkg/TestActivity.java line 12: Add explicit URI grant flags:
        @@ -11,0 +12 @@
        +        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        """
      )
  }

  fun testSendActionWithExplicitGrantFlagCleanJava() {
    lint()
      .allowMissingSdk()
      .files(
        java(
            """
          package test.pkg;

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

  fun testImageCaptureMissingWriteGrantJava() {
    lint()
      .allowMissingSdk()
      .files(
        java(
            """
          package test.pkg;

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
        src/test/pkg/TestActivity.java:12: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(intent);
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/test/pkg/TestActivity.java line 12: Add explicit URI grant flags:
        @@ -11,0 +12 @@
        +        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        """
      )
  }

  fun testIntraAppExplicitIntentCleanJava() {
    lint()
      .allowMissingSdk()
      .files(
        java(
            """
          package test.pkg;

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

  fun testIntraAppSetPackageCleanJava() {
    lint()
      .allowMissingSdk()
      .files(
        java(
            """
          package test.pkg;

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

  fun testExternalSetPackageWarnsJava() {
    lint()
      .allowMissingSdk()
      .files(
        java(
            """
          package test.pkg;

          import android.content.Context;
          import android.content.Intent;
          import android.net.Uri;

          public class TestActivity {
              public void shareExternal(Context context, Uri fileUri) {
                  Intent intent = new Intent(Intent.ACTION_SEND);
                  intent.setType("image/png");
                  intent.putExtra(Intent.EXTRA_STREAM, fileUri);
                  intent.setPackage("com.test");
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
        src/test/pkg/TestActivity.java:13: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(intent);
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/test/pkg/TestActivity.java line 13: Add explicit URI grant flags:
        @@ -12,0 +13 @@
        +        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        """
      )
  }

  fun testIntraAppSetComponentCleanJava() {
    lint()
      .allowMissingSdk()
      .files(
        java(
            """
          package test.pkg;

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

  fun testExternalSetComponentWarnsJava() {
    lint()
      .allowMissingSdk()
      .files(
        java(
            """
          package test.pkg;

          import android.content.ComponentName;
          import android.content.Context;
          import android.content.Intent;
          import android.net.Uri;

          public class TestActivity {
              public void shareExternal(Context context, Uri fileUri) {
                  Intent intent = new Intent(Intent.ACTION_SEND);
                  intent.setType("image/png");
                  intent.putExtra(Intent.EXTRA_STREAM, fileUri);
                  intent.setComponent(new ComponentName("com.external.app", "com.external.app.ShareActivity"));
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
        src/test/pkg/TestActivity.java:14: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(intent);
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/test/pkg/TestActivity.java line 14: Add explicit URI grant flags:
        @@ -13,0 +14 @@
        +        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        """
      )
  }

  fun testUnrelatedStartActivityMethodIgnoredJava() {
    lint()
      .allowMissingSdk()
      .files(
        java(
            """
          package test.pkg;

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
        src/test/pkg/TestActivity.java:16: Warning: Implicit URI grants for this action are discontinued from Android 18 onwards. Please set the grant explicitly on the intent. See https://goo.gle/implicit-uri-grants for more info. [MissingExplicitUriGrant]
                context.startActivity(share);
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
      .expectFixDiffs(
        """
        Fix for src/test/pkg/TestActivity.java line 16: Add explicit URI grant flags:
        @@ -15,0 +16 @@
        +        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        """
      )
  }
}
