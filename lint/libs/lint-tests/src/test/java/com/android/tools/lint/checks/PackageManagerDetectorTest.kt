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

class PackageManagerDetectorTest : AbstractCheckTest() {

  override fun getDetector() = PackageManagerDetector()

  fun testDocumentationExample() {
    lint()
      .files(
        java(
            """
            package com.example.app;

            import android.content.Context;
            import android.content.pm.PackageManager;
            import android.Manifest;

            public class MyApp {
                public void foo(Context context) {
                    PackageManager pm = context.getPackageManager();

                    // WRONG: Using permission name as the second argument
                    pm.checkPermission("com.example.app", "android.permission.CAMERA");
                    pm.checkPermission("com.example.app", Manifest.permission.CAMERA);

                    // OK: Using a package name
                    pm.checkPermission(Manifest.permission.CAMERA, "com.example.app");
                }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/MyApp.java:12: Error: Argument is supposed to be a package name, not a permission [PackageManagerCheckPermission]
                pm.checkPermission("com.example.app", "android.permission.CAMERA");
                                                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/example/app/MyApp.java:13: Error: Argument is supposed to be a package name, not a permission [PackageManagerCheckPermission]
                pm.checkPermission("com.example.app", Manifest.permission.CAMERA);
                                                      ~~~~~~~~~~~~~~~~~~~~~~~~~~
        2 errors
        """
      )
  }

  fun testSimpleKotlin() {
    lint()
      .files(
        kotlin(
            """
            package com.example.app

            import android.content.Context
            import android.content.pm.PackageManager
            import android.Manifest

            class MyApp {
                fun foo(context: Context) {
                    val pm = context.packageManager

                    // WRONG: Using permission name as the second argument
                    pm.checkPermission("com.example.app", "android.permission.CAMERA")
                    pm.checkPermission("com.example.app", Manifest.permission.CAMERA)

                    // OK: Using a package name
                    pm.checkPermission(Manifest.permission.CAMERA, "com.example.app")
                }
            }
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        src/com/example/app/MyApp.kt:12: Error: Argument is supposed to be a package name, not a permission [PackageManagerCheckPermission]
                pm.checkPermission("com.example.app", "android.permission.CAMERA")
                                                      ~~~~~~~~~~~~~~~~~~~~~~~~~~~
        src/com/example/app/MyApp.kt:13: Error: Argument is supposed to be a package name, not a permission [PackageManagerCheckPermission]
                pm.checkPermission("com.example.app", Manifest.permission.CAMERA)
                                                      ~~~~~~~~~~~~~~~~~~~~~~~~~~
        2 errors
        """
      )
  }
}
