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

import com.android.SdkConstants.ANDROID_URI
import com.android.SdkConstants.ATTR_NAME
import com.android.tools.apk.analyzer.BinaryXmlParser
import com.android.tools.lint.detector.api.Detector
import com.android.utils.XmlUtils
import com.android.utils.iterator
import com.google.common.io.ByteStreams
import java.net.URLClassLoader
import kotlin.io.path.Path
import kotlin.text.Charsets.UTF_8

class PrivateComputeCoreDetectorTest : AbstractCheckTest() {
  override fun getDetector(): Detector = PrivateComputeCoreDetector()

  fun testDocumentationExample() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
              package="com.example.app"
              android:versionCode="1"
              android:versionName="1.0">

              <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="37" />

              <uses-permission android:name="android.permission.READ_SMS" />
              <uses-permission android:name="android.permission.INTERNET" />

              <application android:icon="@drawable/ic_launcher" android:label="@string/app_name">
                <activity android:name="com.example.app.MyActivity" android:label="@string/app_name" android:isPrivateComputeCoreProcess="true">
                </activity>
              </application>

            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        AndroidManifest.xml:9: Warning: Permission "android.permission.INTERNET" is not allowed in private compute core process [PrivateComputePermission]
          <uses-permission android:name="android.permission.INTERNET" />
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
  }

  fun testServicesAndReceiversAlsoFine() {
    // ...as long as they are ALL marked as android:isPrivateComputeCoreProcess="true"
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
              package="com.example.app"
              android:versionCode="1"
              android:versionName="1.0">

              <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="37" />

              <uses-permission android:name="android.permission.READ_SMS" />
              <uses-permission android:name="android.permission.INTERNET" />

              <application android:icon="@drawable/ic_launcher" android:label="@string/app_name">
                <activity android:name="com.example.app.MyActivity" android:label="@string/app_name" android:isPrivateComputeCoreProcess="true" />
                <service android:name="com.example.app.MyService" android:isPrivateComputeCoreProcess="true" />
                <receiver android:name="com.example.app.MyService" android:isPrivateComputeCoreProcess="true" />
              </application>

            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expect(
        """
        AndroidManifest.xml:9: Warning: Permission "android.permission.INTERNET" is not allowed in private compute core process [PrivateComputePermission]
          <uses-permission android:name="android.permission.INTERNET" />
          ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        0 errors, 1 warning
        """
      )
  }

  fun testNoWarningsIfNoComponents() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
              package="com.example.app"
              android:versionCode="1"
              android:versionName="1.0">

              <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="37" />

              <uses-permission android:name="android.permission.READ_SMS" />
              <uses-permission android:name="android.permission.INTERNET" />

              <application android:icon="@drawable/ic_launcher" android:label="@string/app_name">
              </application>

            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testNoWarningsIfSomeComponentsNotPcc() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
              package="com.example.app"
              android:versionCode="1"
              android:versionName="1.0">

              <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="37" />

              <uses-permission android:name="android.permission.READ_SMS" />
              <uses-permission android:name="android.permission.INTERNET" />

              <application android:icon="@drawable/ic_launcher" android:label="@string/app_name">
                <activity android:name="com.example.app.MyActivity" android:label="@string/app_name" android:isPrivateComputeCoreProcess="true">
                </activity>
                <activity android:name="com.example.app.MyActivity2" android:label="@string/activity2">
                </activity>
              </application>

            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testNoWarningsForOlderTargetSdk() {
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
              package="com.example.app"
              android:versionCode="1"
              android:versionName="1.0">

              <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="36" />

              <uses-permission android:name="android.permission.READ_SMS" />
              <uses-permission android:name="android.permission.INTERNET" />

              <application android:icon="@drawable/ic_launcher" android:label="@string/app_name">
                <activity android:name="com.example.app.MyActivity" android:label="@string/app_name" android:isPrivateComputeCoreProcess="true">
                </activity>
              </application>

            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun testNoWarningsForNewerTargetSdk() {
    // It is not currently clear how permissions that later become "allowed in private compute core" will work,
    // so we currently don't warn for future target SDK versions (until this is worked out by the feature team).
    lint()
      .files(
        manifest(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android"
              package="com.example.app"
              android:versionCode="1"
              android:versionName="1.0">

              <uses-sdk android:minSdkVersion="33" android:targetSdkVersion="38" />

              <uses-permission android:name="android.permission.READ_SMS" />
              <uses-permission android:name="android.permission.INTERNET" />

              <application android:icon="@drawable/ic_launcher" android:label="@string/app_name">
                <activity android:name="com.example.app.MyActivity" android:label="@string/app_name" android:isPrivateComputeCoreProcess="true">
                </activity>
              </application>

            </manifest>
            """
          )
          .indented()
      )
      .run()
      .expectClean()
  }

  fun disabled_testGenerateBlockedPermissions() {
    val androidJar = Path("/data2/temp-37-sdk/android.jar")
    val loader = URLClassLoader(arrayOf(androidJar.toUri().toURL()))
    val bytes = loader.getResourceAsStream("AndroidManifest.xml")!!.use { inputStream -> ByteStreams.toByteArray(inputStream) }
    val xml = String(BinaryXmlParser.decodeXml(bytes), UTF_8)
    val manifest = XmlUtils.parseDocumentSilently(xml, true)!!.documentElement
    val blockedPermissions = HashSet<String>()
    for (permission in manifest.iterator()) {
      if (permission.tagName != "permission") continue
      val permissionName = permission.getAttributeNodeNS(ANDROID_URI, ATTR_NAME)?.value ?: continue
      val flags = permission.getAttributeNodeNS(ANDROID_URI, "permissionFlags")?.value
      if (flags == null) {
        // No flags, so not allowed in private compute process.
        blockedPermissions.add(permissionName)
        continue
      }
      val flagsInt = Integer.decode(flags)

      if (flagsInt and FLAG_ALLOWED_IN_PRIVATE_COMPUTE_CORE == 0) {
        // Has flags, but does not have the PCC flag, so not allowed in private compute process.
        blockedPermissions.add(permissionName)
        continue
      }
      // Otherwise, we loop. Permission is allowed in private compute process.
    }

    print("      \"${blockedPermissions.first()}\"")
    for (permission in blockedPermissions.drop(1)) {
      print(",\n      \"$permission\"")
    }
    println(" -> true")
    println("      else -> false")
  }
}

private const val FLAG_ALLOWED_IN_PRIVATE_COMPUTE_CORE = 1 shl 5
