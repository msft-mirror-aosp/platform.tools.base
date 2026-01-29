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
import com.android.tools.lint.checks.infrastructure.TestFiles.java

class InstantAppDetectorTest : AbstractCheckTest() {
  override fun getDetector() = InstantAppDetector()

  fun testDocumentationExample() {
    lint()
        .files(
            manifest().targetSdk(31),
            kotlin(
                    """
            package com.example.app

            import android.app.Activity
            import com.google.android.gms.instantapps.InstantApps

            class MyApp {
              fun go(activity: Activity) {
                InstantApps.showInstallPrompt(activity, null, 0, null)
              }
            }
            """
                )
                .indented(),
            instantAppsStub,
        )
        .run()
        .expect(
            """
        src/com/example/app/MyApp.kt:8: Warning: Instant Apps support will be removed by Google Play in December 2025 [InstantAppCall]
            InstantApps.showInstallPrompt(activity, null, 0, null)
            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
        )
  }

  fun testNoWarningWhenDeprecated() {
    lint()
        .files(
            manifest().targetSdk(31),
            kotlin(
                    """
            package com.example.app

            import android.app.Activity
            import com.google.android.gms.instantapps.InstantApps

            class MyApp {
              fun go(activity: Activity) {
                InstantApps.showInstallPrompt(activity, null, 0, null)
              }
            }
            """
                )
                .indented(),
            instantAppsStubDeprecated,
        )
        .run()
        .expectClean()
  }
}

private val instantAppsStub: TestFile =
    java(
            """
      /* HIDE-FROM-DOCUMENTATION */
      package com.google.android.gms.instantapps;

      import android.app.Activity;
      import android.content.Intent;

      public final class InstantApps {
        public static boolean showInstallPrompt(
          Activity activity, // non-null
          Intent postInstallIntent, // nullable
          int requestCode,
          String referrer // nullable
        ) {}
        private InstantApps() {}
      }
      """
        )
        .indented()

private val instantAppsStubDeprecated: TestFile =
    java(
            """
      /* HIDE-FROM-DOCUMENTATION */
      package com.google.android.gms.instantapps;

      import android.app.Activity;
      import android.content.Intent;

      @Deprecated
      public final class InstantApps {
        @Deprecated
        public static boolean showInstallPrompt(
          Activity activity, // non-null
          Intent postInstallIntent, // nullable
          int requestCode,
          String referrer // nullable
        ) {}
        private InstantApps() {}
      }
      """
        )
        .indented()
