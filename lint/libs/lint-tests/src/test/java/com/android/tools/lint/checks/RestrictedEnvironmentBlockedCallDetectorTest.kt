/*
 * Copyright (C) 2024 The Android Open Source Project
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

import com.android.tools.lint.checks.infrastructure.LintDetectorTest
import com.android.tools.lint.checks.infrastructure.ProjectDescription
import com.android.tools.lint.checks.infrastructure.ProjectDescription.Type.APP
import com.android.tools.lint.checks.infrastructure.ProjectDescription.Type.LIBRARY
import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.detector.api.Detector

class RestrictedEnvironmentBlockedCallDetectorTest : AbstractCheckTest() {

  override fun getDetector(): Detector = RestrictedEnvironmentBlockedCallDetector()

  fun testDocumentationExample() {
    val lib =
      ProjectDescription()
        .name("lib")
        .type(LIBRARY)
        .files(
          kotlin(
              """
              package com.example

              import android.content.Context
              import android.net.wifi.WifiManager
              import android.os.Process
              import androidx.annotation.ChecksRestrictedEnvironment
              import androidx.annotation.RestrictedForEnvironment
              import androidx.annotation.RestrictedForEnvironment.Environment.SDK_SANDBOX

              class MyApp6 {
                  fun foo(context: Context) {
                      // Getting the service class is OK.
                      val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

                      val state = wifiManager?.wifiState // Bad - calling getWifiState()

                      getNotMuch() // OK - annotated, but empty environments.
                      getData() // Bad
                      if (!isSandboxed()) {
                          getData() // OK
                      }
                  }

                  fun foo2() {
                      if (!Process.isSdkSandbox()) {
                          getData() // OK
                      }
                  }

                  @RestrictedForEnvironment([SDK_SANDBOX], 31)
                  fun getData() {
                      getMoreData() // OK - the containing method is annotated
                      // (and the outer "from" targetSdk is <= the blocked call "from" targetSdk)
                  }

                  @RestrictedForEnvironment([SDK_SANDBOX], 32)
                  fun getMoreData() {}

                  @RestrictedForEnvironment([], 31) // No environments
                  fun getNotMuch() {}

                  @ChecksRestrictedEnvironment([SDK_SANDBOX])
                  fun isSandboxed(): Boolean = TODO()
              }
              """
            )
            .indented(),
          RESTRICTED_ANNOTATION,
          CHECKS_ANNOTATION,
        )

    val app =
      ProjectDescription()
        .name("my_sdk_app")
        .type(APP)
        .dependsOn(lib)
        .files(
          manifest().targetSdk(35),
          gradle(
            """
            /* HIDE-FROM-DOCUMENTATION */
            apply plugin: 'com.android.privacy-sandbox-sdk'
            """
          ),
        )

    lint()
      .projects(app)
      .run()
      .expect(
        """
        ../lib/src/com/example/MyApp6.kt:15: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 34 or above [PrivacySandboxBlockedCall]
                val state = wifiManager?.wifiState // Bad - calling getWifiState()
                            ~~~~~~~~~~~~~~~~~~~~~~
        ../lib/src/com/example/MyApp6.kt:18: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 31 or above [PrivacySandboxBlockedCall]
                getData() // Bad
                ~~~~~~~~~
        0 errors, 2 warnings
        """
      )
  }

  fun testDocumentationExampleJava() {
    val lib =
      ProjectDescription()
        .name("lib")
        .type(LIBRARY)
        .files(
          java(
              """
              package com.example;

              import static androidx.annotation.RestrictedForEnvironment.Environment.SDK_SANDBOX;

              import android.content.Context;
              import android.net.wifi.WifiManager;
              import android.os.Process;

              import androidx.annotation.ChecksRestrictedEnvironment;
              import androidx.annotation.RestrictedForEnvironment;

              public class MyApp7 {
                  public void foo(Context context) {
                      // Getting the service class is OK.
                      WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);

                      int state = wifiManager.getWifiState(); // Bad - calling getWifiState()

                      getNotMuch(); // OK - annotated, but empty environments.
                      getData(); // Bad
                      if (!isSandboxed()) {
                          getData(); // OK
                      }
                  }

                  public void foo2() {
                      if (!Process.isSdkSandbox()) {
                          getData(); // OK
                      }
                  }

                  @RestrictedForEnvironment(environments = { SDK_SANDBOX }, from = 31)
                  public void getData() {
                      getMoreData(); // OK - the containing method is annotated
                      // (and the outer "from" targetSdk is <= the blocked call "from" targetSdk)
                  }

                  @RestrictedForEnvironment(environments = { SDK_SANDBOX }, from = 32)
                  public void getMoreData() {}

                  @RestrictedForEnvironment(environments = {}, from = 31) // No environments
                  public void getNotMuch() {}

                  @ChecksRestrictedEnvironment(environments = { SDK_SANDBOX })
                  public boolean isSandboxed() {
                      return true;
                  }
              }
              """
            )
            .indented(),
          RESTRICTED_ANNOTATION,
          CHECKS_ANNOTATION,
        )

    val app =
      ProjectDescription()
        .name("my_sdk_app")
        .type(APP)
        .dependsOn(lib)
        .files(
          manifest().targetSdk(35),
          gradle(
              """
              /* HIDE-FROM-DOCUMENTATION */
              apply plugin: 'com.android.privacy-sandbox-sdk'
              """
            )
            .indented(),
        )

    lint()
      .projects(app)
      .run()
      .expect(
        """
        ../lib/src/com/example/MyApp7.java:17: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 34 or above [PrivacySandboxBlockedCall]
                int state = wifiManager.getWifiState(); // Bad - calling getWifiState()
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~
        ../lib/src/com/example/MyApp7.java:20: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 31 or above [PrivacySandboxBlockedCall]
                getData(); // Bad
                ~~~~~~~~~
        0 errors, 2 warnings
        """
      )
  }

  fun testGetter() {
    val lib =
      ProjectDescription()
        .name("lib")
        .type(LIBRARY)
        .files(
          kotlin(
              """
              package com.example

              import android.hardware.biometrics.BiometricManager
              import android.hardware.biometrics.BiometricManager.Authenticators
              import android.os.Process

              class MyApp2(
                  private val biometricManager: BiometricManager,
              ) {
                  val hasPin: Boolean
                      get() {
                          biometricManager.canAuthenticate(Authenticators.DEVICE_CREDENTIAL) // Bad
                          return if (Process.isSdkSandbox()) {
                              false
                          } else {
                              biometricManager.canAuthenticate(Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS // OK
                          }
                      }
              }
              """
            )
            .indented(),
          RESTRICTED_ANNOTATION,
          CHECKS_ANNOTATION,
        )

    val app =
      ProjectDescription()
        .name("my_sdk_app")
        .type(APP)
        .dependsOn(lib)
        .files(
          manifest().targetSdk(35),
          gradle(
            """
            /* HIDE-FROM-DOCUMENTATION */
            apply plugin: 'com.android.privacy-sandbox-sdk'
            """
          ),
        )

    lint()
      .projects(app)
      .run()
      .expect(
        """
        ../lib/src/com/example/MyApp2.kt:12: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 34 or above [PrivacySandboxBlockedCall]
                    biometricManager.canAuthenticate(Authenticators.DEVICE_CREDENTIAL) // Bad
                    ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testRunLambda() {
    val lib =
      ProjectDescription()
        .name("lib")
        .type(LIBRARY)
        .files(
          kotlin(
              """
              package com.example

              import android.hardware.biometrics.BiometricManager
              import android.hardware.biometrics.BiometricManager.Authenticators
              import android.os.Process

              class MyApp3(
                  private val biometricManager: BiometricManager,
              ) {
                  val hasPin: Boolean = run {
                      if (Process.isSdkSandbox()) {
                          false
                      } else {
                          biometricManager.canAuthenticate(Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS // OK
                      }
                  }

                  val hasPin2: Boolean = run {
                      biometricManager.canAuthenticate(Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS // Bad
                  }
              }
              """
            )
            .indented(),
          RESTRICTED_ANNOTATION,
          CHECKS_ANNOTATION,
        )

    val app =
      ProjectDescription()
        .name("my_sdk_app")
        .type(APP)
        .dependsOn(lib)
        .files(
          manifest().targetSdk(35),
          gradle(
            """
            /* HIDE-FROM-DOCUMENTATION */
            apply plugin: 'com.android.privacy-sandbox-sdk'
            """
          ),
        )

    lint()
      .projects(app)
      .run()
      .expect(
        """
        ../lib/src/com/example/MyApp3.kt:19: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 34 or above [PrivacySandboxBlockedCall]
                biometricManager.canAuthenticate(Authenticators.DEVICE_CREDENTIAL) == BiometricManager.BIOMETRIC_SUCCESS // Bad
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warnings
        """
      )
  }

  fun testOuterAnnotation() {
    val lib =
      ProjectDescription()
        .name("lib")
        .type(LIBRARY)
        .files(
          kotlin(
              """
              package com.example

              import android.hardware.biometrics.BiometricManager
              import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
              import android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
              import androidx.annotation.RestrictedForEnvironment
              import androidx.annotation.RestrictedForEnvironment.Environment.SDK_SANDBOX

              class MyApp4(
                  private val biometricManager: BiometricManager,
              ) {
                  @RestrictedForEnvironment(environments = [SDK_SANDBOX], from = 33)
                  fun foo(): Boolean =
                      biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // OK

                  @RestrictedForEnvironment(environments = [SDK_SANDBOX], from = 34)
                  fun foo2(): Boolean =
                      biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // OK

                  @RestrictedForEnvironment(environments = [SDK_SANDBOX], from = 35)
                  fun foo3(): Boolean =
                      biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // Bad - 35 is too high

                  @RestrictedForEnvironment(environments = [], from = 34)
                  fun foo4(): Boolean =
                      biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // Bad - missing environment

                  val foo5
                      @RestrictedForEnvironment(environments = [SDK_SANDBOX], from = 34)
                      get() = biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // OK
              }
              """
            )
            .indented(),
          RESTRICTED_ANNOTATION,
          CHECKS_ANNOTATION,
        )

    val app =
      ProjectDescription()
        .name("my_sdk_app")
        .type(APP)
        .dependsOn(lib)
        .files(
          manifest().targetSdk(35),
          gradle(
            """
            /* HIDE-FROM-DOCUMENTATION */
            apply plugin: 'com.android.privacy-sandbox-sdk'
            """
          ),
        )

    lint()
      .projects(app)
      .run()
      .expect(
        """
        ../lib/src/com/example/MyApp4.kt:22: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 34 or above [PrivacySandboxBlockedCall]
                biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // Bad - 35 is too high
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        ../lib/src/com/example/MyApp4.kt:26: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 34 or above [PrivacySandboxBlockedCall]
                biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // Bad - missing environment
                ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
      )
  }

  fun testCustomCheckField() {
    val lib =
      ProjectDescription()
        .name("lib")
        .type(LIBRARY)
        .files(
          kotlin(
              """
              package com.example

              import android.hardware.biometrics.BiometricManager
              import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
              import android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS
              import android.os.Build
              import android.os.Process
              import androidx.annotation.ChecksRestrictedEnvironment
              import androidx.annotation.RestrictedForEnvironment.Environment.SDK_SANDBOX

              class MyApp5(
                  private val biometricManager: BiometricManager,
              ) {
                  @ChecksRestrictedEnvironment(environments = [SDK_SANDBOX])
                  val isSandboxed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Process.isSdkSandbox()

                  fun foo(): Boolean {
                      if (isSandboxed) {
                          return false
                      } else {
                          return biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // OK
                      }
                  }

                  fun foo2(): Boolean {
                      return biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // Bad foo2
                  }

                  val foo3 = if (isSandboxed) { biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS } else false  // OK

                  val foo4 = if (isSandboxed) biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS else false  // OK

                  val foo5 = !isSandboxed && biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // OK

                  val foo6 = biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // Bad - foo5
              }
              """
            )
            .indented(),
          RESTRICTED_ANNOTATION,
          CHECKS_ANNOTATION,
        )

    val app =
      ProjectDescription()
        .name("my_sdk_app")
        .type(APP)
        .dependsOn(lib)
        .files(
          manifest().targetSdk(35),
          gradle(
            """
            /* HIDE-FROM-DOCUMENTATION */
            apply plugin: 'com.android.privacy-sandbox-sdk'
            """
          ),
        )

    lint()
      .projects(app)
      .run()
      .expect(
        """
        ../lib/src/com/example/MyApp5.kt:26: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 34 or above [PrivacySandboxBlockedCall]
                return biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // Bad foo2
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        ../lib/src/com/example/MyApp5.kt:35: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 34 or above [PrivacySandboxBlockedCall]
            val foo6 = biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS // Bad - foo5
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
      )
  }

  fun testCustomCheckFieldJava() {
    val lib =
      ProjectDescription()
        .name("lib")
        .type(LIBRARY)
        .files(
          java(
              """
              package com.example;

              import static android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL;
              import static android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS;
              import static androidx.annotation.RestrictedForEnvironment.Environment.SDK_SANDBOX;

              import android.hardware.biometrics.BiometricManager;
              import android.os.Build;
              import android.os.Process;

              import androidx.annotation.ChecksRestrictedEnvironment;

              public class MyApp8 {

                  @ChecksRestrictedEnvironment(environments = {SDK_SANDBOX})
                  private final boolean isSandboxed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && Process.isSdkSandbox();

                  private BiometricManager biometricManager;

                  private boolean foo1() {
                      if (isSandboxed) {
                          return false;
                      } else {
                          return biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS; // OK
                      }
                  }

                  private boolean foo2() {
                      return biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS; // Bad - foo2
                  }

                  private final boolean foo3 = !isSandboxed && biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS; // OK

                  private final boolean foo4 = biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS; // Bad - foo4
              }
              """
            )
            .indented(),
          RESTRICTED_ANNOTATION,
          CHECKS_ANNOTATION,
        )

    val app =
      ProjectDescription()
        .name("my_sdk_app")
        .type(APP)
        .dependsOn(lib)
        .files(
          manifest().targetSdk(35),
          gradle(
            """
            /* HIDE-FROM-DOCUMENTATION */
            apply plugin: 'com.android.privacy-sandbox-sdk'
            """
          ),
        )

    lint()
      .projects(app)
      .run()
      .expect(
        """
        ../lib/src/com/example/MyApp8.java:29: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 34 or above [PrivacySandboxBlockedCall]
                return biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS; // Bad - foo2
                       ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        ../lib/src/com/example/MyApp8.java:34: Warning: Call is blocked in the Privacy Sandbox when targetSdk is 34 or above [PrivacySandboxBlockedCall]
            private final boolean foo4 = biometricManager.canAuthenticate(DEVICE_CREDENTIAL) == BIOMETRIC_SUCCESS; // Bad - foo4
                                         ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 2 warnings
        """
      )
  }

  companion object {

    val RESTRICTED_ANNOTATION: TestFile =
      LintDetectorTest.kotlin(
          """
          package androidx.annotation

          /* HIDE-FROM-DOCUMENTATION */

          @MustBeDocumented
          @Retention(AnnotationRetention.BINARY)
          @Repeatable
          @Target(
              AnnotationTarget.CLASS,
              AnnotationTarget.FUNCTION,
              AnnotationTarget.PROPERTY_GETTER,
              AnnotationTarget.PROPERTY_SETTER,
              AnnotationTarget.CONSTRUCTOR,
              AnnotationTarget.FIELD,
          )
          annotation class RestrictedForEnvironment(
              val environments: Array<Environment>,
              val from: Int
          ) {
              enum class Environment {
                  SDK_SANDBOX,
                  SOME_OTHER_SANDBOX_ENVIRONMENT,
              }
          }
          """
        )
        .indented()

    val CHECKS_ANNOTATION: TestFile =
      LintDetectorTest.kotlin(
          """
          package androidx.annotation

          /* HIDE-FROM-DOCUMENTATION */

          import androidx.annotation.RestrictedForEnvironment.Environment

          @MustBeDocumented
          @Retention(AnnotationRetention.BINARY)
          @Target(
              AnnotationTarget.CLASS,
              AnnotationTarget.FUNCTION,
              AnnotationTarget.PROPERTY_GETTER,
              AnnotationTarget.PROPERTY_SETTER,
              AnnotationTarget.CONSTRUCTOR,
              AnnotationTarget.FIELD,
          )
          annotation class ChecksRestrictedEnvironment(
              val environments: Array<Environment>,
          )
          """
        )
        .indented()
  }
}
