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

package com.android.build.gradle.integration.manageddevice.application

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.api.variant.FilterConfiguration
import com.android.build.gradle.integration.common.fixture.GradleTaskExecutor
import com.android.build.gradle.integration.common.fixture.project.GradleRule
import com.android.build.gradle.integration.common.fixture.project.plugins.ApplicationComponentCallback
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomAndroidSdk
import com.android.build.gradle.integration.manageddevice.utils.CustomAndroidSdkRule.Companion.withCustomSdkDir
import com.android.build.gradle.integration.manageddevice.utils.simpleProject
import com.android.build.gradle.options.BooleanOption
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * End-to-end tests for the `testedAbi` property of a Gradle Managed Device.
 *
 * The app is built with ABI splits for "x86" and "x86_64", each with a distinct version code. The instrumented test asserts the version
 * code of the installed app, which identifies the split APK selected for the configured `testedAbi`.
 */
@RunWith(Parameterized::class)
class ManagedDeviceTestedAbiTest(private val runWithBuiltInPlatform: Boolean) {

  @get:Rule val customAndroidSdkRule = CustomAndroidSdkRule()

  @get:Rule
  val rule =
    GradleRule.configure().withCustomSdkDir(customAndroidSdkRule).from {
      simpleProject()
      gradleProperties { add(BooleanOption.ANDROID_BUILTIN_TEST_PLATFORM, runWithBuiltInPlatform) }
      androidApplication {
        android {
          defaultConfig { versionCode = 1 }
          splits {
            abi {
              isEnable = true
              reset()
              include(ABI_X86, ABI_X86_64)
              isUniversalApk = false
            }
          }
        }
        pluginCallbacks += AbiVersionCodeCallback::class.java
      }
    }

  private val executor: GradleTaskExecutor
    get() = rule.build.executor.withCustomAndroidSdk(customAndroidSdkRule).withEnableInfoLogging(false)

  /** Gives each ABI split APK a distinct version code so the installed APK can be identified on device. */
  class AbiVersionCodeCallback : ApplicationComponentCallback {
    override fun handleExtension(project: Project, androidComponents: ApplicationAndroidComponentsExtension) {
      androidComponents.onVariants { variant ->
        variant.outputs.forEach { output ->
          val abi = output.filters.find { it.filterType == FilterConfiguration.FilterType.ABI }?.identifier
          ABI_VERSION_CODES[abi]?.let { output.versionCode.set(it) }
        }
      }
    }
  }

  private fun addManagedDevice(testedAbi: String?) {
    rule.build.androidApplication().reconfigure {
      android {
        testOptions.managedDevices {
          localDevices.create(DEVICE_NAME) {
            it.device = "Pixel 2"
            it.sdkVersion = System.getProperty("sdk.repo.sysimage.apiLevel").toInt()
            it.systemImageSource = System.getProperty("sdk.repo.sysimage.source")
            it.require64Bit = true
            if (testedAbi != null) {
              it.testedAbi = testedAbi
            }
          }
        }
      }
    }
  }

  private fun writeInstrumentedTestExpectingVersionCode(expectedVersionCode: Int) {
    rule.build
      .androidApplication()
      .files
      .update("src/androidTest/java/com/example/android/kotlin/InstrumentedTest.kt")
      .replaceWith(
        // language=kotlin
        """
        package com.example.android.kotlin

        import androidx.test.ext.junit.runners.AndroidJUnit4
        import androidx.test.platform.app.InstrumentationRegistry
        import org.junit.Assert.assertEquals
        import org.junit.Test
        import org.junit.runner.RunWith

        @RunWith(AndroidJUnit4::class)
        class ExampleInstrumentedTest {
            @Test
            @Suppress("DEPRECATION")
            fun installedApkMatchesTestedAbi() {
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                assertEquals($expectedVersionCode, packageInfo.versionCode)
            }
        }
        """
          .trimIndent()
      )
  }

  @Test
  fun testedAbiSelectsMatchingSplitApk() {
    // The system image is x86_64, so without testedAbi the x86_64 split would be installed.
    addManagedDevice(testedAbi = ABI_X86)
    writeInstrumentedTestExpectingVersionCode(ABI_VERSION_CODES.getValue(ABI_X86))

    val result = executor.run(":app:${DEVICE_NAME}DebugAndroidTest")

    // testedAbi is explicitly specified, so the setup task must not warn about it being unset.
    result.assertOutputDoesNotContain(UNSPECIFIED_TESTED_ABI_WARNING)
    result.assertErrorDoesNotContain(UNSPECIFIED_TESTED_ABI_WARNING)
  }

  @Test
  fun unspecifiedTestedAbiDefaultsToSystemImageAbiAndWarns() {
    addManagedDevice(testedAbi = null)
    writeInstrumentedTestExpectingVersionCode(ABI_VERSION_CODES.getValue(ABI_X86_64))

    val result = executor.run(":app:${DEVICE_NAME}DebugAndroidTest")

    result.assertOutputContains(UNSPECIFIED_TESTED_ABI_WARNING)
  }

  @Test
  fun incompatibleTestedAbiFailsSetup() {
    // The aosp-atd system image does not support NDK translation, so ARM cannot be tested on it.
    addManagedDevice(testedAbi = ABI_ARM64)

    val result = executor.expectFailure().run(":app:${DEVICE_NAME}Setup")

    assertThat(result.failedTasks).containsExactly(":app:${DEVICE_NAME}Setup")
    result.assertErrorContains("ARM translation is only available for Google APIs or Play Store images")
    result.assertErrorContains("If ARM is not the intended ABI for this device, set testedAbi = \"$ABI_X86_64\"")
  }

  companion object {
    @JvmStatic @Parameterized.Parameters(name = "runWithBuiltInPlatform={0}") fun parameters() = listOf(false, true)

    private const val DEVICE_NAME = "device1"
    private const val ABI_X86 = "x86"
    private const val ABI_X86_64 = "x86_64"
    private const val ABI_ARM64 = "arm64-v8a"
    private val ABI_VERSION_CODES = mapOf(ABI_X86 to 1008, ABI_X86_64 to 1009)
    private const val UNSPECIFIED_TESTED_ABI_WARNING = "The device \"$DEVICE_NAME\" does not specify a \"testedAbi\"."
  }
}
