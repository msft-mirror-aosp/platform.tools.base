/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.prefs

import com.android.testutils.truth.PathSubject
import com.android.utils.EnvironmentProvider
import com.android.utils.FileUtils
import com.android.utils.ILogger
import com.google.common.truth.Truth
import java.io.File
import kotlin.io.path.createDirectories
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExpectedException
import org.junit.rules.TemporaryFolder

class AbstractAndroidLocationsTest {

  @get:Rule val folder = TemporaryFolder()

  @get:Rule val exceptionRule: ExpectedException = ExpectedException.none()

  @Test
  fun `ANDROID_USER_HOME usage`() {
    val testLocation = folder.newFolder()
    val provider = FakeProvider(sysProp = mapOf(AbstractAndroidLocations.ANDROID_USER_HOME to testLocation.absolutePath), envVar = mapOf())
    val logger = RecordingLogger()

    val locationProvider: AndroidLocationsProvider = AndroidLocations(provider, logger)
    val result = locationProvider.prefsLocation

    Truth.assertWithMessage("Test Location").that(result).isEqualTo(testLocation.toPath())
    PathSubject.assertThat(result).isDirectory()

    Truth.assertWithMessage("Emitted Warnings").that(logger.warnings).isEmpty()

    val result2 = locationProvider.prefsLocation
    Truth.assertWithMessage("Test Memoization").that(result2).isSameAs(result)
  }

  @Test
  fun `ANDROID_PREFS_ROOT usage`() {
    val testLocation = folder.newFolder()
    val provider = FakeProvider(sysProp = mapOf(AbstractAndroidLocations.ANDROID_PREFS_ROOT to testLocation.absolutePath), envVar = mapOf())
    val logger = RecordingLogger()

    val locationProvider: AndroidLocationsProvider = AndroidLocations(provider, logger)
    val result = locationProvider.prefsLocation

    val expected = testLocation.toPath().resolve(".android")
    Truth.assertWithMessage("Test Location").that(result).isEqualTo(expected)

    Truth.assertWithMessage("Emitted Warnings").that(logger.warnings).isEmpty()
  }

  @Test
  fun `ANDROID_SDK_HOME usage`() {
    val testLocation = folder.newFolder()
    val provider = FakeProvider(sysProp = mapOf("ANDROID_SDK_HOME" to testLocation.absolutePath), envVar = mapOf())
    val expected = testLocation.toPath().resolve(".android")

    val logger = RecordingLogger()

    Truth.assertWithMessage("Test Location").that(AndroidLocations(provider, logger).prefsLocation).isEqualTo(expected)

    Truth.assertWithMessage("Emitted Warnings").that(logger.warnings).isEmpty()
  }

  @Test
  fun `XDG_CONFIG_HOME usage`() {
    val testLocation = folder.newFolder()
    val provider = FakeProvider(sysProp = mapOf(), envVar = mapOf("XDG_CONFIG_HOME" to "do/not/use", "HOME" to testLocation.absolutePath))
    val logger = RecordingLogger()

    val locationProvider: AndroidLocationsProvider = AndroidLocations(provider, logger)
    val result = locationProvider.prefsLocation

    val expected = testLocation.toPath().resolve(".android")
    Truth.assertWithMessage("Test Location").that(result).isEqualTo(expected)

    Truth.assertWithMessage("Emitted Warnings").that(logger.warnings).isEmpty()
  }

  @Test
  fun `existing XDG_CONFIG_HOME usage`() {
    val testLocation = folder.newFolder()
    // If it already exists use it
    testLocation.toPath().resolve(".android").createDirectories()
    val provider = FakeProvider(sysProp = mapOf(), envVar = mapOf("XDG_CONFIG_HOME" to testLocation.absolutePath, "HOME" to "do/not/use"))
    val logger = RecordingLogger()

    val locationProvider: AndroidLocationsProvider = AndroidLocations(provider, logger)
    val result = locationProvider.prefsLocation

    val expected = testLocation.toPath().resolve(".android")
    Truth.assertWithMessage("Test Location").that(result).isEqualTo(expected)

    Truth.assertWithMessage("Emitted Warnings").that(logger.warnings).isEmpty()
  }

  @Test
  fun `ANDROID_PREFS_ROOT and ANDROID_SDK_HOME with different values`() {
    val androidSdkHomeLocation = folder.newFolder().absolutePath
    val androidPrefsRootLocation = folder.newFolder().absolutePath
    val provider =
      FakeProvider(
        sysProp = mapOf("ANDROID_SDK_HOME" to androidSdkHomeLocation, "ANDROID_PREFS_ROOT" to androidPrefsRootLocation),
        envVar = mapOf(),
      )
    val logger = RecordingLogger()

    checkException(
      """
                Several environment variables and/or system properties contain different paths to the Android Preferences folder.
                Please correct and use only one way to inject the preference location.

                - ANDROID_PREFS_ROOT(system property): $androidPrefsRootLocation -> resolves to ${File(androidPrefsRootLocation, ".android").path}
                - ANDROID_SDK_HOME(system property): $androidSdkHomeLocation -> resolves to ${File(androidSdkHomeLocation, ".android").path}

                Note that ANDROID_USER_HOME is the .android folder itself, while the deprecated ANDROID_PREFS_ROOT and ANDROID_SDK_HOME
                are its *parent* folder. Setting them to the same value therefore points to two different folders.
                It is recommended to unset the deprecated variables and use only ANDROID_USER_HOME.
                These values may also be injected by your IDE or build environment, and not only by your system settings.
            """
        .trimIndent()
    ) {
      AndroidLocations(provider, logger).prefsLocation
    }
  }

  /**
   * Regression test for b/555796808.
   *
   * Setting ANDROID_USER_HOME and ANDROID_PREFS_ROOT to the same literal value is a conflict, because the latter has ".android" appended.
   * The resulting message must disclose the resolved paths, otherwise the two entries render identically and the conflict looks
   * nonsensical.
   */
  @Test
  fun `ANDROID_USER_HOME and ANDROID_PREFS_ROOT with the same literal value`() {
    val androidFolder = folder.newFolder(".android")
    val location = androidFolder.absolutePath
    val provider =
      FakeProvider(
        sysProp = mapOf(),
        envVar =
          mapOf(
            AbstractAndroidLocations.ANDROID_USER_HOME to location,
            AbstractAndroidLocations.ANDROID_PREFS_ROOT to location,
          ),
      )
    val logger = RecordingLogger()

    checkException(
      """
                Several environment variables and/or system properties contain different paths to the Android Preferences folder.
                Please correct and use only one way to inject the preference location.

                - ANDROID_PREFS_ROOT(environment variable): $location -> resolves to ${File(location, ".android").path}
                - ANDROID_USER_HOME(environment variable): $location

                Note that ANDROID_USER_HOME is the .android folder itself, while the deprecated ANDROID_PREFS_ROOT and ANDROID_SDK_HOME
                are its *parent* folder. Setting them to the same value therefore points to two different folders.
                It is recommended to unset the deprecated variables and use only ANDROID_USER_HOME.
                These values may also be injected by your IDE or build environment, and not only by your system settings.
            """
        .trimIndent()
    ) {
      AndroidLocations(provider, logger).prefsLocation
    }
  }

  /** Companion to the above: pointing ANDROID_PREFS_ROOT at the *parent* agrees with ANDROID_USER_HOME and must resolve cleanly. */
  @Test
  fun `ANDROID_USER_HOME and ANDROID_PREFS_ROOT that agree`() {
    val parent = folder.newFolder()
    val androidFolder = File(parent, ".android").also { it.mkdirs() }
    val provider =
      FakeProvider(
        sysProp = mapOf(),
        envVar =
          mapOf(
            AbstractAndroidLocations.ANDROID_USER_HOME to androidFolder.absolutePath,
            AbstractAndroidLocations.ANDROID_PREFS_ROOT to parent.absolutePath,
          ),
      )
    val logger = RecordingLogger()

    val result = AndroidLocations(provider, logger).prefsLocation

    // Use PathSubject rather than Truth.that(): a Path is also an Iterable, so Truth.that() would bind to the Iterable overload
    // and trip the PathAsIterable lint check.
    PathSubject.assertThat(result).isEqualTo(androidFolder.toPath())
    Truth.assertWithMessage("Emitted Warnings").that(logger.warnings).isEmpty()
  }

  @Test
  fun `ANDROID_PREFS_ROOT and ANDROID_SDK_HOME with same values`() {
    val testLocation = folder.newFolder()
    val provider =
      FakeProvider(
        sysProp = mapOf("ANDROID_SDK_HOME" to testLocation.absolutePath, "ANDROID_PREFS_ROOT" to testLocation.absolutePath),
        envVar = mapOf(),
      )
    val expected = testLocation.toPath().resolve(".android")

    val logger = RecordingLogger()

    Truth.assertWithMessage("Test Location").that(AndroidLocations(provider, logger).prefsLocation).isEqualTo(expected)

    Truth.assertWithMessage("Warnings").that(logger.warnings).isEmpty()
  }

  @Test
  fun `ANDROID_SDK_HOME points to SDK`() {
    val testLocation = folder.newFolder()
    // create SDK folders under this
    FileUtils.mkdirs(File(testLocation, "platforms"))
    FileUtils.mkdirs(File(testLocation, "platform-tools"))

    val provider = FakeProvider(sysProp = mapOf("ANDROID_SDK_HOME" to testLocation.absolutePath), envVar = mapOf())
    val expected = testLocation.toPath().resolve(".android")

    val logger = RecordingLogger()

    AndroidLocations(provider, logger, silent = false).prefsLocation
    Truth.assertWithMessage("Expected warning is missing")
      .that(logger.warnings)
      .containsExactly(
        """
                    ANDROID_SDK_HOME is set to the root of your SDK: $testLocation
                    ANDROID_SDK_HOME was meant to be the parent path of the preference folder expected by the Android tools.
                    It is now deprecated.

                    To set a custom preference folder location, use ANDROID_USER_HOME.

                    It should NOT be set to the same directory as the root of your SDK.
                    To set a custom SDK location, use ANDROID_HOME.
                """
          .trimIndent()
      )
  }

  @Test
  fun `No valid paths, no injected paths`() {
    val userHomePath = "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}user.home"
    val testTempDir = "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}TEST_TMPDIR"
    val home = "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}HOME"
    val provider = FakeProvider(sysProp = mapOf("user.home" to userHomePath), envVar = mapOf("TEST_TMPDIR" to testTempDir, "HOME" to home))
    val logger = RecordingLogger()

    checkException(
      """
                Unable to find the location for the android preferences.
                The following locations have been checked, but they do not exist:

                - HOME(environment variable): $home
                - TEST_TMPDIR(environment variable): $testTempDir
                - user.home(system property): $userHomePath
                """
        .trimIndent()
    ) {
      AndroidLocations(provider, logger).prefsLocation
    }
  }

  @Test
  fun `No valid paths, with old injected paths`() {
    val androidSdkHomeSysProp =
      "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}android_sdk_home${File.separatorChar}sys-prop"
    val androidSdkHomeEnvVar =
      "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}android_sdk_home${File.separatorChar}env-var"
    val userHomePath = "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}user.home"
    val testTempDir = "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}TEST_TMPDIR"
    val home = "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}HOME"
    val provider =
      FakeProvider(
        sysProp = mapOf("ANDROID_SDK_HOME" to androidSdkHomeSysProp, "user.home" to userHomePath),
        envVar = mapOf("ANDROID_SDK_HOME" to androidSdkHomeEnvVar, "TEST_TMPDIR" to testTempDir, "HOME" to home),
      )
    val logger = RecordingLogger()

    checkException(
      """
                Unable to find the location for the android preferences.
                The following locations have been checked, but they do not exist:

                - ANDROID_SDK_HOME(environment variable): $androidSdkHomeEnvVar
                - ANDROID_SDK_HOME(system property): $androidSdkHomeSysProp
                - HOME(environment variable): $home
                - TEST_TMPDIR(environment variable): $testTempDir
                - user.home(system property): $userHomePath
                """
        .trimIndent()
    ) {
      AndroidLocations(provider, logger).prefsLocation
    }
  }

  @Test
  fun `No valid paths, both ANDROID_PREFS_ROOT and ANDROID_SDK_HOME`() {
    val userHomePath = "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}user.home"
    val testTempDir = "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}TEST_TMPDIR"
    val home = "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}HOME"
    val androidFolderPathSysProp =
      "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}.android${File.separatorChar}sys-prop"
    val androidFolderPathEnvVar =
      "${File.separatorChar}path${File.separatorChar}to${File.separatorChar}.android${File.separatorChar}env-var"
    val provider =
      FakeProvider(
        sysProp =
          mapOf(
            AbstractAndroidLocations.ANDROID_PREFS_ROOT to androidFolderPathSysProp,
            "ANDROID_SDK_HOME" to androidFolderPathSysProp,
            "user.home" to userHomePath,
          ),
        envVar =
          mapOf(
            AbstractAndroidLocations.ANDROID_PREFS_ROOT to androidFolderPathEnvVar,
            "ANDROID_SDK_HOME" to androidFolderPathEnvVar,
            "TEST_TMPDIR" to testTempDir,
            "HOME" to home,
          ),
      )
    val logger = RecordingLogger()

    checkException(
      """
                Unable to find the location for the android preferences.
                The following locations have been checked, but they do not exist:

                - ANDROID_PREFS_ROOT(environment variable): $androidFolderPathEnvVar
                - ANDROID_PREFS_ROOT(system property): $androidFolderPathSysProp
                - ANDROID_SDK_HOME(environment variable): $androidFolderPathEnvVar
                - ANDROID_SDK_HOME(system property): $androidFolderPathSysProp
                - HOME(environment variable): $home
                - TEST_TMPDIR(environment variable): $testTempDir
                - user.home(system property): $userHomePath
                """
        .trimIndent()
    ) {
      AndroidLocations(provider, logger).prefsLocation
    }
  }

  @Test
  fun `Check failure to create location`() {
    val testLocation = folder.newFolder()
    val provider = FakeProvider(sysProp = mapOf(AbstractAndroidLocations.ANDROID_PREFS_ROOT to testLocation.absolutePath), envVar = mapOf())
    val logger = RecordingLogger()

    val locationProvider: AndroidLocationsProvider = AndroidLocations(provider, logger)

    // write a file where it's expected that the folder is.
    val expected = File(testLocation, ".android")
    expected.writeText("foo")

    checkException(
      """
                ${expected.absolutePath} is not a directory!
                This is the path of preference folder expected by the Android tools."""
        .trimIndent()
    ) {
      locationProvider.prefsLocation
    }
  }

  @Test
  fun `AVD Location inside prefsLocation`() {
    val testLocation = folder.newFolder()
    val provider = FakeProvider(sysProp = mapOf("ANDROID_PREFS_ROOT" to testLocation.absolutePath), envVar = mapOf())
    val logger = RecordingLogger()

    val locationProvider = AndroidLocations(provider, logger)
    val prefsLocation = locationProvider.prefsLocation
    val expected = prefsLocation.resolve("avd")

    val result = locationProvider.avdLocation
    Truth.assertWithMessage("Test Location").that(result).isEqualTo(expected)
  }

  @Test
  fun `AVD Location via ANDROID_AVD_HOME`() {
    val testLocation = folder.newFolder()
    val expectedAvdLocation = folder.newFolder()
    val provider =
      FakeProvider(
        sysProp = mapOf("ANDROID_PREFS_ROOT" to testLocation.absolutePath, "ANDROID_AVD_HOME" to expectedAvdLocation.absolutePath),
        envVar = mapOf(),
      )
    val logger = RecordingLogger()

    Truth.assertWithMessage("Test Location").that(AndroidLocations(provider, logger).avdLocation).isEqualTo(expectedAvdLocation.toPath())
  }

  @Test
  fun `userHomeLocation via TEST_TMPDIR`() {
    val testLocation = folder.newFolder()
    val provider =
      FakeProvider(
        sysProp = mapOf("user.home" to "overridden user.home"),
        envVar = mapOf("TEST_TMPDIR" to testLocation.absolutePath, "HOME" to "overriden HOME"),
      )
    val logger = RecordingLogger()

    Truth.assertWithMessage("Test Location").that(AndroidLocations(provider, logger).userHomeLocation).isEqualTo(testLocation.toPath())
  }

  @Test
  fun `userHomeLocation via USER_HOME`() {
    val testLocation = folder.newFolder()
    val provider = FakeProvider(sysProp = mapOf("user.home" to testLocation.absolutePath), envVar = mapOf("HOME" to "overriden HOME"))
    val logger = RecordingLogger()

    val result = AndroidLocations(provider, logger).userHomeLocation

    Truth.assertWithMessage("Test Location").that(result).isEqualTo(testLocation.toPath())
  }

  @Test
  fun `userHomeLocation via HOME`() {
    val testLocation = folder.newFolder()
    val provider = FakeProvider(sysProp = mapOf(), envVar = mapOf("HOME" to testLocation.absolutePath))
    val logger = RecordingLogger()

    Truth.assertWithMessage("Test Location").that(AndroidLocations(provider, logger).userHomeLocation).isEqualTo(testLocation.toPath())
  }

  @Test
  fun `userHomeLocation via XDG_CONFIG_HOME`() {
    val testLocation = folder.newFolder()
    val provider = FakeProvider(sysProp = mapOf(), envVar = mapOf("HOME" to testLocation.absolutePath, "XDG_CONFIG_HOME" to "do/not/use"))
    val logger = RecordingLogger()

    Truth.assertWithMessage("Test Location").that(AndroidLocations(provider, logger).userHomeLocation).isEqualTo(testLocation.toPath())
  }

  /**
   * Quick helper method to get the output of an exception.
   *
   * Changing to message comparison via truth allows for easier string comparison than using the ExpectedException
   */
  private fun <T> checkException(message: String, action: () -> T): T? {
    return if (true) {
      exceptionRule.expect(AndroidLocationsException::class.java)
      exceptionRule.expectMessage(message)

      action()
    } else {
      try {
        action()
        throw RuntimeException("No exception thrown")
      } catch (e: Throwable) {
        Truth.assertThat(e.message).isEqualTo(message)
        null
      }
    }
  }
}

internal class FakeProvider(private val sysProp: Map<String, String>, private val envVar: Map<String, String>) : EnvironmentProvider {
  override fun getSystemProperty(key: String): String? = sysProp[key]

  override fun getEnvVariable(key: String): String? = envVar[key]
}

internal class RecordingLogger : ILogger {
  val warnings = mutableListOf<String>()

  override fun error(t: Throwable?, msgFormat: String?, vararg args: Any?) {
    throw RuntimeException("Unexpected call to errors()")
  }

  override fun warning(msgFormat: String, vararg args: Any?) {
    warnings.add(String.format(msgFormat, *args))
  }

  override fun info(msgFormat: String, vararg args: Any?) {
    throw RuntimeException("Unexpected call to info()")
  }

  override fun verbose(msgFormat: String, vararg args: Any?) {
    throw RuntimeException("Unexpected call to verbose()")
  }
}

private class AndroidLocations(environmentProvider: EnvironmentProvider, logger: ILogger, silent: Boolean = true) :
  AbstractAndroidLocations(environmentProvider, logger, silent = silent)
