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

import com.android.tools.lint.checks.infrastructure.ProjectDescription
import com.android.tools.lint.checks.infrastructure.TestMode
import com.android.tools.lint.detector.api.Detector

class MultiAppFunctionServiceDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector = MultiAppFunctionServiceDetector()

  fun testDocumentationExample() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36" />
                <application>
                    <service
                        android:name=".FirstAppFunctionService"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".SecondAppFunctionService"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        AndroidManifest.xml:5: Error: Multiple AppFunctionService declarations are enabled prior to API level 37, where the platform only indexes a single AppFunctionService [MultiAppFunctionService]
                <service
                 ~~~~~~~
            AndroidManifest.xml:13: Also enabled here
                <service
                 ~~~~~~~
        1 error
        """
      )
  }

  fun testSingleService_belowSdk37_clean() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="36" />
                <application>
                    <service
                        android:name=".MyAppFunctionService"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testMultipleServices_atOrAboveSdk37_clean() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="37" android:targetSdkVersion="37" />
                <application>
                    <service
                        android:name=".FirstAppFunctionService"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".SecondAppFunctionService"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testMultipleServices_arbitratedWithV36Resource_clean() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="36" />
                <application>
                    <service
                        android:name=".SidecarAppFunctionService"
                        android:enabled="@bool/enable_sidecar_service"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".PlatformAppFunctionService"
                        android:enabled="@bool/enable_platform_service"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented(),
        xml(
            "res/values/bools.xml",
            """
            <resources>
                <bool name="enable_sidecar_service">true</bool>
                <bool name="enable_platform_service">false</bool>
            </resources>
            """,
          )
          .indented(),
        xml(
            "res/values-v36/bools.xml",
            """
            <resources>
                <bool name="enable_sidecar_service">false</bool>
                <bool name="enable_platform_service">true</bool>
            </resources>
            """,
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  fun testMultipleServices_arbitratedWithV36AndV37Resources_clean() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="37" />
                <application>
                    <service
                        android:name=".SidecarAppFunctionService"
                        android:enabled="@bool/enable_sidecar"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".PrimaryPlatformAppFunctionService"
                        android:enabled="@bool/enable_platform_v36"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".SecondaryPlatformAppFunctionService"
                        android:enabled="@bool/enable_platform_v37"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented(),
        xml(
            "res/values/bools.xml",
            """
            <resources>
                <bool name="enable_sidecar">true</bool>
                <bool name="enable_platform_v36">false</bool>
                <bool name="enable_platform_v37">false</bool>
            </resources>
            """,
          )
          .indented(),
        xml(
            "res/values-v36/bools.xml",
            """
            <resources>
                <bool name="enable_sidecar">false</bool>
                <bool name="enable_platform_v36">true</bool>
            </resources>
            """,
          )
          .indented(),
        xml(
            "res/values-v37/bools.xml",
            """
            <resources>
                <bool name="enable_platform_v37">true</bool>
            </resources>
            """,
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  fun testMultipleServices_onlyOneLiteralEnabled_clean() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="36" />
                <application>
                    <service
                        android:name=".EnabledAppFunctionService"
                        android:enabled="true"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".DisabledAppFunctionService"
                        android:enabled="false"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testMultipleServices_nonVersionQualifiedBoolResource_clean() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="36" />
                <application>
                    <service
                        android:name=".FirstAppFunctionService"
                        android:enabled="true"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".SecondAppFunctionService"
                        android:enabled="@bool/enable_tablet_service"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented(),
        xml(
            "res/values/bools.xml",
            """
            <resources>
                <bool name="enable_tablet_service">false</bool>
            </resources>
            """,
          )
          .indented(),
        xml(
            "res/values-sw600dp-v36/bools.xml",
            """
            <resources>
                <bool name="enable_tablet_service">true</bool>
            </resources>
            """,
          )
          .indented(),
      )
      .run()
      .expectClean()
  }

  fun testMultipleServices_overriddenBoolResourceAtSameApiVersion_clean() {
    val lib =
      project(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.lib">
                <uses-sdk android:minSdkVersion="34" />
            </manifest>
            """
          )
          .indented(),
        xml(
            "res/values/bools.xml",
            """
            <resources>
                <bool name="enable_lib_service">true</bool>
            </resources>
            """,
          )
          .indented(),
      )

    val app =
      project(
          manifest(
              """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="com.example.app">
                  <uses-sdk android:minSdkVersion="34" android:targetSdkVersion="36" />
                  <application>
                      <service
                          android:name=".AppFunctionService1"
                          android:enabled="true"
                          android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                          android:exported="true">
                          <intent-filter>
                              <action android:name="android.app.appfunctions.AppFunctionService" />
                          </intent-filter>
                      </service>
                      <service
                          android:name=".LibAppFunctionService"
                          android:enabled="@bool/enable_lib_service"
                          android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                          android:exported="true">
                          <intent-filter>
                              <action android:name="android.app.appfunctions.AppFunctionService" />
                          </intent-filter>
                      </service>
                  </application>
              </manifest>
              """
            )
            .indented(),
          xml(
              "res/values/bools.xml",
              """
              <resources>
                  <bool name="enable_lib_service">false</bool>
              </resources>
              """,
            )
            .indented(),
        )
        .dependsOn(lib)

    lint()
      .projects(lib, app)
      // TestLintClient's TestMode.RESOURCE_REPOSITORIES merges library resources in reverse
      // priority order rather than returning all declarations like LintResourceRepository.
      .skipTestModes(TestMode.RESOURCE_REPOSITORIES)
      .run()
      .expectClean()
  }

  fun testMultipleServices_boolResourceIndirection_resolvesUpToDepthLimit() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36" />
                <application>
                    <service
                        android:name=".FirstAppFunctionService"
                        android:enabled="true"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".SecondAppFunctionService"
                        android:enabled="@bool/hop_1"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".ThirdAppFunctionService"
                        android:enabled="@bool/too_deep_1"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented(),
        xml(
            "res/values/bools.xml",
            """
            <resources>
                <bool name="hop_1">@bool/hop_2</bool>
                <bool name="hop_2">true</bool>
                <bool name="too_deep_1">@bool/too_deep_2</bool>
                <bool name="too_deep_2">@bool/too_deep_3</bool>
                <bool name="too_deep_3">true</bool>
            </resources>
            """,
          )
          .indented(),
      )
      .run()
      .expect(
        """
        AndroidManifest.xml:5: Error: Multiple AppFunctionService declarations are enabled prior to API level 37, where the platform only indexes a single AppFunctionService [MultiAppFunctionService]
                <service
                 ~~~~~~~
            AndroidManifest.xml:14: Also enabled here
                <service
                 ~~~~~~~
        1 error
        """
      )
  }

  fun testMultipleServices_inLibraryProject_clean() {
    val lib =
      project(
          manifest(
              """
              <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  package="com.example.lib">
                  <uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36" />
                  <application>
                      <service
                          android:name=".FirstAppFunctionService"
                          android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                          android:exported="true">
                          <intent-filter>
                              <action android:name="android.app.appfunctions.AppFunctionService" />
                          </intent-filter>
                      </service>
                      <service
                          android:name=".SecondAppFunctionService"
                          android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                          android:exported="true">
                          <intent-filter>
                              <action android:name="android.app.appfunctions.AppFunctionService" />
                          </intent-filter>
                      </service>
                  </application>
              </manifest>
              """
            )
            .indented()
        )
        .type(ProjectDescription.Type.LIBRARY)

    lint().projects(lib).run().expectClean()
  }

  fun testMultipleServices_disabledApplication_clean() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36" />
                <application android:enabled="false">
                    <service
                        android:name=".FirstAppFunctionService"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".SecondAppFunctionService"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testMultipleServices_minSdkBelow34_reportsError() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="21" android:targetSdkVersion="36" />
                <application>
                    <service
                        android:name=".FirstAppFunctionService"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".SecondAppFunctionService"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        AndroidManifest.xml:5: Error: Multiple AppFunctionService declarations are enabled prior to API level 37, where the platform only indexes a single AppFunctionService [MultiAppFunctionService]
                <service
                 ~~~~~~~
            AndroidManifest.xml:13: Also enabled here
                <service
                 ~~~~~~~
        1 error
        """
      )
  }

  fun testMultipleServices_unresolvableBoolResource_clean() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36" />
                <application>
                    <service
                        android:name=".FirstAppFunctionService"
                        android:enabled="true"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".SecondAppFunctionService"
                        android:enabled="@bool/missing_bool"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testMultipleServices_withoutAppFunctionAction_clean() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                package="com.example.app">
                <uses-sdk android:minSdkVersion="36" android:targetSdkVersion="36" />
                <application>
                    <service
                        android:name=".MyAppFunctionService"
                        android:permission="android.permission.BIND_APP_FUNCTION_SERVICE"
                        android:exported="true">
                        <intent-filter>
                            <action android:name="android.app.appfunctions.AppFunctionService" />
                        </intent-filter>
                    </service>
                    <service
                        android:name=".OtherService"
                        android:exported="false">
                        <intent-filter>
                            <action android:name="com.example.app.OTHER_ACTION" />
                        </intent-filter>
                    </service>
                </application>
            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }
}
