/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.build.gradle.integration.application

import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import org.junit.Rule
import org.junit.Test

/** Assemble tests for androidManifestInTest. */
class AndroidManifestInTestTest {
  @get:Rule
  val rule =
    GradleRule.from {
      androidApplication {
        android {
          namespace = "com.android.tests.basic"
          defaultConfig {
            versionCode = 12
            versionName = "2.0"
            minSdk = 16
            targetSdk = 16
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
          }
        }
        files {
          add(
            "src/androidTest/AndroidManifest.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                <instrumentation android:name="${"$"}{instrumentationRunner}">
                    <meta-data android:name="listener"
                               android:value="androidx.test.internal.runner.listener.ManifestListener"/>
                </instrumentation>

                <permission-group android:name="foo.permission-group.COST_MONEY"
                    android:label="@string/app_name"
                    android:description="@string/app_name" />

                <permission android:name="foo.permission.RECEIVED_SMS"
                    android:permissionGroup="foo.permission-group.COST_MONEY"
                    android:label="@string/app_name"
                    android:description="@string/app_name" />

            </manifest>
            """
              .trimIndent(),
          )

          add(
            "src/main/res/values/strings.xml",
            """
            <resources>
                <string name="app_name">ManifestInTest</string>
            </resources>
            """
              .trimIndent(),
          )

          add(
            "src/androidTest/res/values/strings.xml",
            """
            <?xml version="1.0" encoding="utf-8"?>
            <resources>
                <string name="app_name">_Test-Basic</string>
            </resources>
            """
              .trimIndent(),
          )

          add(
            "src/main/res/drawable/icon.xml",
            """
            <vector xmlns:android="http://schemas.android.com/apk/res/android"
                android:width="24dp"
                android:height="24dp"
                android:viewportWidth="24"
                android:viewportHeight="24">
                <path android:fillColor="#FF000000" android:pathData="M12,2L2,22h20L12,2z"/>
            </vector>
            """
              .trimIndent(),
          )
        }
      }
    }

  @Test
  fun testUserProvidedTestAndroidManifest() {
    rule.build.executor.run("assembleDebugAndroidTest")

    rule.build.androidApplication().assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
      manifestAsNodes().node("manifest").apply {
        node("permission-group")
          .containsAttributeAndValue("http://schemas.android.com/apk/res/android:name", "\"foo.permission-group.COST_MONEY\"")

        node("application").containsAttributeAndValue("http://schemas.android.com/apk/res/android:debuggable", "true")
        node("instrumentation").containsNode("meta-data")
      }
    }
  }
}
