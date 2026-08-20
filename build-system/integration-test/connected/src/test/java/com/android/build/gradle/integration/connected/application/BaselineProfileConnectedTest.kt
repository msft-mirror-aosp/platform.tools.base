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

package com.android.build.gradle.integration.connected.application

import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.builder.GradleBuildDefinition
import com.android.build.gradle.integration.common.fixture.project.builder.PluginType
import com.android.build.gradle.integration.connected.utils.getEmulator
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import kotlin.io.path.exists
import kotlin.io.path.readText
import org.junit.Assume
import org.junit.ClassRule
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class BaselineProfileConnectedTest(val runWithBuiltInPlatform: Boolean) {
  companion object {
    @JvmField @ClassRule val emulator: ExternalResource = getEmulator()

    @JvmStatic
    @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}")
    fun parameters(): Collection<Array<Any>> = listOf(arrayOf(false), arrayOf(true))
  }

  // Since the androidx.baselineprofile plugin is not on the classpath of the
  // integration test framework, we cannot use its extension class directly.
  // Instead, we define this dummy interface. DslProxy will create a dynamic proxy
  // for it and record setter calls, which are then written to the build.gradle file.
  // It must be an interface (not a class) because JDK dynamic proxies only support
  // interfaces.
  interface DummyBaselineProfileExtension {
    var saveInSrc: Boolean?
    var mergeIntoMain: Boolean?
    var automaticGenerationDuringBuild: Boolean?
    var useConnectedDevices: Boolean?
  }

  val baselineProfilePlugin =
    object :
      PluginType.PluginTypeWithExtension<DummyBaselineProfileExtension>(
        id = "androidx.baselineprofile",
        artifact = "androidx.baselineprofile:androidx.baselineprofile.gradle.plugin",
        version = "1.5.0-beta01",
        extensionType = DummyBaselineProfileExtension::class.java,
        extensionName = "baselineProfile",
      ) {}

  @get:Rule
  val rule = GradleRule.from {
    gradleProperties { add(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform) }
    androidApplication(":app", createMinimumProject = false) {
      applyPlugin(baselineProfilePlugin) {
        saveInSrc = true
        mergeIntoMain = true
        automaticGenerationDuringBuild = false
      }
      android {
        namespace = "com.example.repro"
        compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
        defaultConfig {
          applicationId = "com.example.repro"
          minSdk = 29
          targetSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
        }
      }
      dependencies {
        add("baselineProfile", project(":baselineprofile"))
        implementation("androidx.profileinstaller:profileinstaller:1.4.1")
      }
      files {
        add(
          "src/main/AndroidManifest.xml",
          """
          <?xml version="1.0" encoding="utf-8"?>
          <manifest xmlns:android="http://schemas.android.com/apk/res/android">
              <application
                  android:label="bprepro"
                  android:allowBackup="false">
                  <activity
                      android:name=".MainActivity"
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
        add(
          "src/main/java/com/example/repro/MainActivity.java",
          """
          package com.example.repro;

          import android.app.Activity;
          import android.os.Bundle;
          import android.widget.TextView;

          public class MainActivity extends Activity {
              @Override
              protected void onCreate(Bundle savedInstanceState) {
                  super.onCreate(savedInstanceState);
                  TextView text = new TextView(this);
                  text.setText("bprepro");
                  setContentView(text);
              }
          }
          """
            .trimIndent(),
        )
      }
    }
    androidTest(":baselineprofile") {
      applyPlugin(baselineProfilePlugin) { useConnectedDevices = true }
      android {
        namespace = "com.example.repro.bp"
        compileSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
        defaultConfig {
          minSdk = 29
          targetSdk = GradleBuildDefinition.DEFAULT_COMPILE_SDK_VERSION
          testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
        targetProjectPath = ":app"
      }
      dependencies {
        implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0-beta01")
        implementation("androidx.test.ext:junit:1.3.0")
        implementation("androidx.test.uiautomator:uiautomator:2.4.0")
      }
      files {
        add(
          "src/main/kotlin/com/example/repro/bp/StartupGenerator.kt",
          """
          package com.example.repro.bp

          import androidx.benchmark.macro.junit4.BaselineProfileRule
          import androidx.test.ext.junit.runners.AndroidJUnit4
          import org.junit.Rule
          import org.junit.Test
          import org.junit.runner.RunWith

          @RunWith(AndroidJUnit4::class)
          class StartupGenerator {
              @get:Rule
              val rule = BaselineProfileRule()

              @Test
              fun startup() = rule.collect(
                  packageName = "com.example.repro",
                  includeInStartupProfile = true,
              ) {
                  pressHome()
                  startActivityAndWait()
              }
          }
          """
            .trimIndent(),
        )
      }
    }
  }

  @Test
  fun generateBaselineProfile() {
    // TODO: Re-enable this test with the built-in platform once the androidx.baselineprofile plugin
    // is updated to support it. Currently, CollectBaselineProfileTask in the plugin expects a merged
    // 'test-result.pb' file to be present directly at the root of the test results directory
    // (e.g. 'build/outputs/androidTest-results/connected/nonMinifiedRelease/test-result.pb').
    // However, when running with the built-in test platform, results are written to device-specific
    // subdirectories (e.g. '.../nonMinifiedRelease/emulator-5554 - 17/test-result.pb') and are not
    // merged into the root directory, causing the collection task to fail.

    Assume.assumeFalse(runWithBuiltInPlatform)

    val build = rule.build
    // Run the task to generate baseline profile
    build.executor.run(":app:generateBaselineProfile")

    // Verify the file was generated and copied to src
    val appProject = build.androidApplication(":app")
    val profileFile = appProject.resolve("src/main/generated/baselineProfiles/baseline-prof.txt")
    assertThat(profileFile.exists()).isTrue()
    assertThat(profileFile.readText().trim()).isNotEmpty()

    val startupProfileFile = appProject.resolve("src/main/generated/baselineProfiles/startup-prof.txt")
    assertThat(startupProfileFile.exists()).isTrue()
    assertThat(startupProfileFile.readText().trim()).isNotEmpty()
  }
}
