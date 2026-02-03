/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.build.gradle.integration.manifest

import com.android.build.api.variant.GeneratesApkBuilder
import com.android.build.api.variant.HasUnitTest
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.android.build.gradle.integration.common.fixture.project.ApkSelector
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.LibraryComponentCallback
import com.android.build.gradle.integration.common.fixture.project.prebuilts.HelloWorldAndroid
import com.android.build.gradle.options.BooleanOption
import com.android.testutils.truth.PathSubject.assertThat
import kotlin.test.assertTrue
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test

class ProcessTestManifestTest {

  @get:Rule
  val rule =
    GradleRule.from {
      androidLibrary {
        android { namespace = "com.example.helloworld" }
        HelloWorldAndroid.setupJava(files)
      }
    }

  @Test
  fun testInstrumentationApkTargetSdk() {
    val build =
      rule.build {
        androidLibrary {
          android {
            flavorDimensions.add("targetSdk")

            productFlavors {
              create("sdk30") { it.dimension = "targetSdk" }

              create("sdk32") { it.dimension = "targetSdk" }
            }
          }
          pluginCallbacks += InstrumentationTargetSdkCallback::class.java
        }
      }
    val lib = build.androidLibrary()

    build.executor.run(":lib:assembleAndroidTest")
    val sdk30ManifestFile =
      lib.intermediatesDir.resolve("packaged_manifests/sdk30DebugAndroidTest/processSdk30DebugAndroidTestManifest/AndroidManifest.xml")
    assertThat(sdk30ManifestFile).contains("android:targetSdkVersion=\"30\"")

    val sdk32ManifestFile =
      lib.intermediatesDir.resolve("packaged_manifests/sdk32DebugAndroidTest/processSdk32DebugAndroidTestManifest/AndroidManifest.xml")
    assertThat(sdk32ManifestFile).contains("android:targetSdkVersion=\"32\"")
  }

  class InstrumentationTargetSdkCallback : LibraryComponentCallback {
    override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
      androidComponents.beforeVariants(androidComponents.selector().withFlavor("targetSdk", "sdk30")) { variant ->
        (variant.androidTest as GeneratesApkBuilder).targetSdk = 30
      }

      androidComponents.beforeVariants(androidComponents.selector().withFlavor("targetSdk", "sdk32")) { variant ->
        (variant.androidTest as GeneratesApkBuilder).targetSdk = 32
      }
    }
  }

  @Test
  fun testInstrumentationApkTargetSdkPreview() {
    val build = rule.build { androidLibrary { pluginCallbacks += InstrumentationTargetSdkPreviewCallback::class.java } }
    val lib = build.androidLibrary()

    build.executor.run(":lib:assembleDebugAndroidTest")
    val debugManifestFile =
      lib.intermediatesDir.resolve("packaged_manifests/debugAndroidTest/processDebugAndroidTestManifest/AndroidManifest.xml")
    assertThat(debugManifestFile).contains("android:targetSdkVersion=\"M\"")
  }

  class InstrumentationTargetSdkPreviewCallback : LibraryComponentCallback {
    override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
      androidComponents.beforeVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        (variant.androidTest as GeneratesApkBuilder).targetSdk = 32
      }
      androidComponents.beforeVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        (variant.androidTest as GeneratesApkBuilder).targetSdkPreview = "M"
      }
    }
  }

  @Test
  fun build() {
    val build =
      rule.build {
        androidLibrary {
          android { packaging { jniLibs { useLegacyPackaging = false } } }
          pluginCallbacks += BuildInstrumentationCallback::class.java
        }
      }
    val lib = build.androidLibrary()

    lib.files.add(
      "src/androidTest/java/com/example/helloworld/TestReceiver.java",
      """
      package com.example.helloworld;

      import android.content.BroadcastReceiver;
      import android.content.Context;
      import android.content.Intent;

      public class TestReceiver extends BroadcastReceiver {
          @Override
          public void onReceive(Context context, Intent intent) {
          }
      }
      """
        .trimIndent(),
    )

    lib.files.add(
      "src/main/java/com/example/helloworld/MainReceiver.java",
      """
      package com.example.helloworld;

      import android.content.BroadcastReceiver;
      import android.content.Context;
      import android.content.Intent;

      public class MainReceiver extends BroadcastReceiver {
          @Override
          public void onReceive(Context context, Intent intent) {
          }
      }
      """
        .trimIndent(),
    )

    lib.files.add(
      "src/androidTest/AndroidManifest.xml",
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application>
              <receiver android:name="com.example.helloworld.TestReceiver" />
          </application>
      </manifest>
      """
        .trimIndent(),
    )

    // Replace android manifest with the one containing a receiver reference
    lib.files
      .update("src/main/AndroidManifest.xml")
      .replaceWith(
        """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                    android:versionCode="1"
                    android:versionName="1.0">

            <application android:label="@string/app_name">
                <activity android:name=".HelloWorld"
                            android:label="@string/app_name"
                            android:exported="true">
                    <intent-filter>
                        <action android:name="android.intent.action.MAIN" />
                        <category android:name="android.intent.category.LAUNCHER" />
                    </intent-filter>
                </activity>

                <receiver android:name="com.example.helloworld.MainReceiver" />
            </application>
        </manifest>
        """
          .trimIndent()
      )

    build.executor.run(":lib:assembleDebugAndroidTest")

    lib.assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
      manifestAsNodes().node("manifest").apply {
        node("application").apply {
          nodeByNameAndAttribute("receiver", "com.example.helloworld.TestReceiver")
          nodeByNameAndAttribute("receiver", "com.example.helloworld.MainReceiver")
          containsAtLeastAttributesAndValues(
            "http://schemas.android.com/apk/res/android:extractNativeLibs=false",
            "http://schemas.android.com/apk/res/android:debuggable=true",
          )
        }
        node("uses-sdk")
          .containsExactlyAttributesAndValues(
            "http://schemas.android.com/apk/res/android:minSdkVersion=21",
            "http://schemas.android.com/apk/res/android:targetSdkVersion=22",
            "http://schemas.android.com/apk/res/android:maxSdkVersion=29",
          )
      }
    }

    // The manifest shouldn't contain android:debuggable if we set the testBuildType to release.
    lib.reconfigure { android { testBuildType = "release" } }
    build.executor.run(":lib:assembleReleaseAndroidTest")

    lib.assertApk(ApkSelector.RELEASE_SIGNED.forTestSuite("androidTest")) {
      manifestAsNodes()
        .node("manifest")
        .node("application")
        .containsExactlyAttributesAndValues(
          "http://schemas.android.com/apk/res/android:extractNativeLibs=false",
          "http://schemas.android.com/apk/res/android:label=@0x7f030000",
        )
    }
  }

  class BuildInstrumentationCallback : LibraryComponentCallback {
    override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
      androidComponents.beforeVariants(androidComponents.selector().all()) { variant ->
        variant.minSdk = 21
        variant.maxSdk = 29
        (variant.androidTest as GeneratesApkBuilder).targetSdk = 22
      }
    }
  }

  @Test
  fun testDebuggingFlagCanBeSet() {
    val build =
      rule.build {
        androidLibrary {
          android { testBuildType = "release" }
          pluginCallbacks += DebuggingFlagCallback::class.java
        }
      }
    val lib = build.androidLibrary()
    build.executor.run(":lib:assembleReleaseAndroidTest")

    lib.assertApk(ApkSelector.RELEASE_SIGNED.forTestSuite("androidTest")) {
      manifestAsNodes()
        .node("manifest")
        .node("application")
        .containsAttributeAndValue("http://schemas.android.com/apk/res/android:debuggable", "true")
    }
  }

  class DebuggingFlagCallback : LibraryComponentCallback {
    override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
      androidComponents.beforeVariants(androidComponents.selector().withBuildType("release")) { variantBuilder ->
        variantBuilder.deviceTests["AndroidTest"]?.debuggable = true
      }
      androidComponents.onVariants(androidComponents.selector().withBuildType("release")) { variant ->
        if (variant.deviceTests["AndroidTest"]?.debuggable != true) {
          throw RuntimeException("DeviceTest.debuggable value not set to true")
        }
      }
    }
  }

  @Test
  fun testManifestOverlays() {
    val build = rule.build
    val lib = build.androidLibrary()
    lib.reconfigure {
      android {
        flavorDimensions.add("app")
        flavorDimensions.add("recents")

        productFlavors {
          create("flavor1") { it.dimension = "app" }

          create("flavor2") { it.dimension = "recents" }
        }
      }
    }
    lib.files.add(
      "src/androidTest/AndroidManifest.xml",
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application>
              <receiver android:name="com.example.helloworld.TestReceiver" />
          </application>
      </manifest>
      """
        .trimIndent(),
    )
    lib.files.add(
      "src/androidTestFlavor1/AndroidManifest.xml",
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application
              android:allowBackup="true">
          </application>
      </manifest>
      """
        .trimIndent(),
    )
    lib.files.add(
      "src/androidTestFlavor2/AndroidManifest.xml",
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application
              android:supportsRtl="true">
          </application>
      </manifest>
      """
        .trimIndent(),
    )
    lib.files.add(
      "src/androidTestDebug/AndroidManifest.xml",
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application
              android:isGame="false">
          </application>
      </manifest>
      """
        .trimIndent(),
    )
    build.executor.run(":lib:assembleFlavor1Flavor2DebugAndroidTest")
    val manifestContent =
      lib.intermediatesDir.resolve(
        "packaged_manifests/flavor1Flavor2DebugAndroidTest/processFlavor1Flavor2DebugAndroidTestManifest/AndroidManifest.xml"
      )
    // merged from androidTestDebug
    assertThat(manifestContent).contains("android:isGame=\"false\"")
    // merged from androidTestFlavor2
    assertThat(manifestContent).contains("android:supportsRtl=\"true\"")
    // merged from androidTestFlavor1
    assertThat(manifestContent).contains("android:allowBackup=\"true\"")
  }

  @Test
  fun testNonUniqueNamespaces() {
    val build = rule.build
    val lib = build.androidLibrary()
    lib.reconfigure { android { namespace = "allowedNonUnique" } }
    lib.files.add(
      "src/androidTest/AndroidManifest.xml",
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application
              android:isGame="false">
          </application>
      </manifest>
      """
        .trimIndent(),
    )
    val result = build.executor.run(":lib:processDebugAndroidTestManifest")
    result.assertOutputDoesNotContain("Namespace 'allowedNonUnique.test' used in:")
  }

  @Test
  fun testWarningForExtractNativeLibsAttribute() {
    val build = rule.build
    val lib = build.androidLibrary()
    lib.files.add(
      "src/androidTest/AndroidManifest.xml",
      """
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">
          <application android:extractNativeLibs="true"/>
      </manifest>
      """
        .trimIndent(),
    )
    val result = build.executor.run(":lib:assembleDebugAndroidTest")
    result.assertOutputContains("android:extractNativeLibs should not be specified")
  }

  @Test
  fun testUnitTestManifestPlaceholdersFromTestedVariant() {
    val build = rule.build
    val lib = build.androidLibrary()
    lib.reconfigure {
      android {
        testBuildType = "release"
        buildTypes { named("release") { it.manifestPlaceholders["label"] = "unit test from tested variant" } }
        testOptions { unitTests { isIncludeAndroidResources = true } }
      }
    }
    lib.files.remove("src/main/AndroidManifest.xml")
    lib.files.add(
      "src/main/AndroidManifest.xml",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  android:versionCode="1"
                  android:versionName="1.0">

          <application android:label="${'$'}{label}">
              <activity android:name=".HelloWorld"
                          android:label="@string/app_name"
                          android:exported="true">
                  <intent-filter>
                      <action android:name="android.intent.action.MAIN" />
                      <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
              </activity>

              <receiver android:name="com.example.helloworld.MainReceiver" />
          </application>
      </manifest>
      """
        .trimIndent(),
    )
    val result = build.executor.run(":lib:processReleaseUnitTestManifest")
    assertTrue { result.failedTasks.isEmpty() }
    val manifestFile = lib.intermediatesDir.resolve("packaged_manifests/releaseUnitTest/processReleaseUnitTestManifest/AndroidManifest.xml")
    assertThat(manifestFile).contains("android:label=\"unit test from tested variant\"")
  }

  @Test
  fun testUnitTestManifestPlaceholdersFromVariantApi() {
    val build =
      rule.build {
        androidLibrary {
          android {
            testBuildType = "release"
            testOptions { unitTests { isIncludeAndroidResources = true } }
          }
          pluginCallbacks += UnitTestManifestPlaceholdersCallback::class.java
        }
      }
    val lib = build.androidLibrary()
    lib.files.remove("src/main/AndroidManifest.xml")
    lib.files.add(
      "src/main/AndroidManifest.xml",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  android:versionCode="1"
                  android:versionName="1.0">

          <application android:label="${'$'}{label}">
              <activity android:name=".HelloWorld"
                          android:label="@string/app_name"
                          android:exported="true">
                  <intent-filter>
                      <action android:name="android.intent.action.MAIN" />
                      <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
              </activity>

              <receiver android:name="com.example.helloworld.MainReceiver" />
          </application>
      </manifest>
      """
        .trimIndent(),
    )
    val result = build.executor.run(":lib:processReleaseUnitTestManifest")
    assertTrue { result.failedTasks.isEmpty() }
    val manifestFile = lib.intermediatesDir.resolve("packaged_manifests/releaseUnitTest/processReleaseUnitTestManifest/AndroidManifest.xml")
    assertThat(manifestFile).contains("android:label=\"unit test from tested variant\"")
  }

  class UnitTestManifestPlaceholdersCallback : LibraryComponentCallback {
    override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
      androidComponents.onVariants(androidComponents.selector().all()) { variant ->
        (variant as HasUnitTest).unitTest?.manifestPlaceholders?.put("label", "unit test from tested variant")
      }
    }
  }

  @Test
  fun testUnitTestManifestContainsTargetSdkVersion() {
    val build =
      rule.build {
        androidLibrary {
          android {
            testBuildType = "release"
            testOptions { unitTests { isIncludeAndroidResources = true } }
          }
          pluginCallbacks += UnitTestManifestTargetSdkCallback::class.java
        }
      }
    val lib = build.androidLibrary()
    val result = build.executor.run(":lib:processReleaseUnitTestManifest")
    assertTrue { result.failedTasks.isEmpty() }
    val manifestFile = lib.intermediatesDir.resolve("packaged_manifests/releaseUnitTest/processReleaseUnitTestManifest/AndroidManifest.xml")
    assertThat(manifestFile).contains("android:targetSdkVersion=\"22\"")
  }

  class UnitTestManifestTargetSdkCallback : LibraryComponentCallback {
    override fun handleExtension(project: Project, androidComponents: LibraryAndroidComponentsExtension) {
      androidComponents.beforeVariants(androidComponents.selector().all()) { variant ->
        val method = variant.javaClass.methods.find { it.name == "setTargetSdk" }
        method?.invoke(variant, 22)
      }
    }
  }

  @Test
  fun testLibraryUnitTestManifestContainsTargetSdkVersionFromOptions() {
    val build = rule.build
    val lib = build.androidLibrary()
    lib.reconfigure {
      android {
        testBuildType = "release"
        testOptions {
          targetSdk = 22
          unitTests { isIncludeAndroidResources = true }
        }
      }
    }
    val result = build.executor.run(":lib:processReleaseUnitTestManifest")
    assertTrue { result.failedTasks.isEmpty() }
    val manifestFile = lib.intermediatesDir.resolve("packaged_manifests/releaseUnitTest/processReleaseUnitTestManifest/AndroidManifest.xml")
    assertThat(manifestFile).contains("android:targetSdkVersion=\"22\"")
  }

  @Test
  fun testUnitTestManifestTargetSdkDefaultsToCompileSdk() {
    val build = rule.build
    val lib = build.androidLibrary()
    lib.reconfigure {
      android {
        testBuildType = "release"
        compileSdk = 36
        testOptions { unitTests { isIncludeAndroidResources = true } }
      }
    }
    val manifestFile = lib.intermediatesDir.resolve("packaged_manifests/releaseUnitTest/processReleaseUnitTestManifest/AndroidManifest.xml")

    val result =
      build.executor.with(BooleanOption.DEFAULT_TARGET_SDK_TO_COMPILE_SDK_IF_UNSET, true).run(":lib:processReleaseUnitTestManifest")
    assertTrue { result.failedTasks.isEmpty() }
    assertThat(manifestFile).exists()
    assertThat(manifestFile).contains("android:targetSdkVersion=\"36\"")

    val resultWithLegacyTargetSdkDefault =
      build.executor.with(BooleanOption.DEFAULT_TARGET_SDK_TO_COMPILE_SDK_IF_UNSET, false).run(":lib:processReleaseUnitTestManifest")
    assertTrue { resultWithLegacyTargetSdkDefault.failedTasks.isEmpty() }
    assertThat(manifestFile).exists()
    assertThat(manifestFile).contains("android:targetSdkVersion=\"1\"")
  }

  @Test
  fun testUnitTestManifestDefaultsTargetSdkToCompileSdkWithMinorRelease() {
    val build = rule.build
    val lib = build.androidLibrary()
    lib.reconfigure {
      android {
        testBuildType = "release"
        compileSdk { version = release(36) { minorApiLevel = 1 } }
        testOptions { unitTests { isIncludeAndroidResources = true } }
      }
    }
    val manifestFile = lib.intermediatesDir.resolve("packaged_manifests/releaseUnitTest/processReleaseUnitTestManifest/AndroidManifest.xml")

    val result2 =
      build.executor.with(BooleanOption.DEFAULT_TARGET_SDK_TO_COMPILE_SDK_IF_UNSET, true).run(":lib:processReleaseUnitTestManifest")
    assertTrue { result2.failedTasks.isEmpty() }
    assertThat(manifestFile).exists()
    assertThat(manifestFile).contains("android:targetSdkVersion=\"36\"")

    val resultWithLegacyTargetSdkDefault =
      build.executor.with(BooleanOption.DEFAULT_TARGET_SDK_TO_COMPILE_SDK_IF_UNSET, false).run(":lib:processReleaseUnitTestManifest")
    assertTrue { resultWithLegacyTargetSdkDefault.failedTasks.isEmpty() }
    assertThat(manifestFile).exists()
    assertThat(manifestFile).contains("android:targetSdkVersion=\"1\"")
  }

  @Test
  fun testUnitTestManifestTargetSdkDefaultsToCompileSdkPreview() {
    val build = rule.build
    val lib = build.androidLibrary()
    lib.reconfigure {
      android {
        testBuildType = "release"
        compileSdkPreview = "Baklava"
        testOptions { unitTests { isIncludeAndroidResources = true } }
      }
    }
    val manifestFile = lib.intermediatesDir.resolve("packaged_manifests/releaseUnitTest/processReleaseUnitTestManifest/AndroidManifest.xml")

    val result =
      build.executor.with(BooleanOption.DEFAULT_TARGET_SDK_TO_COMPILE_SDK_IF_UNSET, true).run(":lib:processReleaseUnitTestManifest")
    assertTrue { result.failedTasks.isEmpty() }
    assertThat(manifestFile).exists()
    assertThat(manifestFile).contains("android:targetSdkVersion=\"Baklava\"")

    val resultWithLegacyTargetSdkDefault =
      build.executor.with(BooleanOption.DEFAULT_TARGET_SDK_TO_COMPILE_SDK_IF_UNSET, false).run(":lib:processReleaseUnitTestManifest")
    assertTrue { resultWithLegacyTargetSdkDefault.failedTasks.isEmpty() }
    assertThat(manifestFile).exists()
    assertThat(manifestFile).contains("android:targetSdkVersion=\"1\"")
  }

  @Test
  fun testClassShorthandInTestManifest() {
    val build = rule.build
    val lib = build.androidLibrary()
    lib.files.add(
      "src/androidTest/AndroidManifest.xml",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android">

          <application>
              <activity
                  android:name=".TestActivity"
                  android:exported="true" />
          </application>
      </manifest>
      """
        .trimIndent(),
    )

    lib.files.remove("src/main/AndroidManifest.xml")
    lib.files.add(
      "src/main/AndroidManifest.xml",
      """
      <?xml version="1.0" encoding="utf-8"?>
      <manifest xmlns:android="http://schemas.android.com/apk/res/android"
                  android:versionCode="1"
                  android:versionName="1.0">

          <application android:label="@string/app_name">
              <activity android:name=".HelloWorld"
                          android:label="@string/app_name"
                          android:exported="true">
                  <intent-filter>
                      <action android:name="android.intent.action.MAIN" />
                      <category android:name="android.intent.category.LAUNCHER" />
                  </intent-filter>
              </activity>
          </application>
      </manifest>
      """
        .trimIndent(),
    )

    build.executor.run(":lib:assembleDebugAndroidTest")

    lib.assertApk(ApkSelector.ANDROIDTEST_DEBUG) {
      manifestAsNodes().node("manifest").node("application").apply {
        nodeByNameAndAttribute("activity", "com.example.helloworld.HelloWorld")
        nodeByNameAndAttribute("activity", "com.example.helloworld.test.TestActivity")
      }
    }
  }
}
