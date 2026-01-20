/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.build.gradle.internal.tasks

import com.android.build.gradle.internal.AvdComponentsBuildService
import com.android.build.gradle.internal.SdkComponentsBuildService
import com.android.build.gradle.internal.SdkComponentsBuildService.VersionedSdkLoader
import com.android.build.gradle.internal.dsl.ManagedVirtualDevice
import com.android.build.gradle.internal.fixtures.FakeGradleProperty
import com.android.build.gradle.internal.fixtures.FakeGradleWorkExecutor
import com.android.build.gradle.internal.profile.AnalyticsService
import com.android.build.gradle.internal.tasks.factory.GlobalTaskCreationConfigImpl
import com.android.build.gradle.options.StringOption
import com.android.repository.Revision
import com.android.testutils.SystemPropertyOverrides
import com.android.utils.Environment
import com.google.common.truth.Truth.assertThat
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.internal.TaskOutputsInternal
import org.gradle.api.logging.Logger
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.services.BuildServiceRegistration
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Answers.CALLS_REAL_METHODS
import org.mockito.Answers.RETURNS_DEEP_STUBS
import org.mockito.junit.MockitoJUnit
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever

class ManagedDeviceInstrumentationTestSetupTaskTest {
    private lateinit var mockVersionedSdkLoader: VersionedSdkLoader

    @get:Rule
    val mockitoRule = MockitoJUnit.rule()

    @get:Rule
    val temporaryFolderRule = TemporaryFolder()

    private val globalConfig: GlobalTaskCreationConfigImpl = mock(defaultAnswer = RETURNS_DEEP_STUBS)

    private val avdService: AvdComponentsBuildService = mock()

    private val sdkService: SdkComponentsBuildService = mock()

    private val emulatorProvider: Provider<Directory> = mock()

    private lateinit var project: Project

    @Before
    fun setup() {
        Environment.initialize()

        mockVersionedSdkLoader = mock<VersionedSdkLoader>(defaultAnswer = RETURNS_DEEP_STUBS)
        whenever(mockVersionedSdkLoader.emulatorDirectoryProvider).thenReturn(emulatorProvider)
        whenever(mockVersionedSdkLoader.offlineMode).thenReturn(false)

        project = ProjectBuilder.builder().withProjectDir(temporaryFolderRule.newFolder()).build()
        whenever(sdkService.sdkLoader(any(), any())).thenReturn(mockVersionedSdkLoader)

        // Setup Build Services for configuration.
        val mockGeneralRegistration = mock<BuildServiceRegistration<*, *>>(defaultAnswer = RETURNS_DEEP_STUBS)
        whenever(globalConfig.services.buildServiceRegistry.registrations.getByName(any()))
            .thenReturn(mockGeneralRegistration)
    }

    private fun basicTaskSetup(): ManagedDeviceInstrumentationTestSetupTask {
        val task = mock<ManagedDeviceInstrumentationTestSetupTask>(defaultAnswer = CALLS_REAL_METHODS)

        // Need to use a real property for all variables passed into the ManagedDeviceSetupRunnable
        // Because internal to Gradle's implementation of ProfileAwareWorkAction
        // a forced cast occurs to cast Provider to ProviderInternal which we
        // do not have access to directly.
        doReturn(realPropertyFor(mock<AnalyticsService>()))
            .whenever(task).analyticsService
        doReturn(realPropertyFor("project_path")).whenever(task).projectPath

        doReturn("path").whenever(task).path
        doReturn(mock<TaskOutputsInternal>(defaultAnswer = RETURNS_DEEP_STUBS))
            .whenever(task).outputs
        doReturn(mock<Logger>()).whenever(task).logger

        doReturn(realPropertyFor(sdkService)).whenever(task).sdkService
        doReturn(realPropertyFor(avdService)).whenever(task).avdService
        doReturn(realPropertyFor("sdkVersion")).whenever(task).compileSdkVersion
        doReturn(realPropertyFor(mock<Revision>()))
            .whenever(task).buildToolsRevision
        doReturn(realPropertyFor("x86_64")).whenever(task).abi
        doReturn(realPropertyFor(29)).whenever(task).sdkVersion
        doReturn(realPropertyFor(0)).whenever(task).sdkMinorVersion
        doReturn(realEmptyPropertyFor<Int>()).whenever(task).sdkExtensionVersion
        doReturn(realPropertyFor("")).whenever(task).pageAlignmentSuffix
        doReturn(realPropertyFor("aosp")).whenever(task).systemImageVendor
        doReturn(realEmptyPropertyFor<String>()).whenever(task).testedAbi
        doReturn(realPropertyFor("Pixel 2")).whenever(task).hardwareProfile
        doReturn(realPropertyFor("someDeviceName")).whenever(task).managedDeviceName
        doReturn(realPropertyFor(true)).whenever(task).require64Bit


        doReturn(FakeGradleWorkExecutor(project.objects, temporaryFolderRule.newFolder()))
            .whenever(task).workerExecutor
        return task
    }

    private inline fun <reified ValueClass> realEmptyPropertyFor(): Property<ValueClass> =
        project.objects.property(ValueClass::class.java)

    private inline fun <reified ValueClass> realPropertyFor(
        providedValue: ValueClass): Property<ValueClass> {

        val property = project.objects.property(ValueClass::class.java)
        property.set(providedValue)
        return property
    }

    private fun <T> mockEmptyProperty(): Property<T> {
        @Suppress("UNCHECKED_CAST")
        return mock<Property<T>>()
    }

    @Test
    fun taskAction_basicSetupPath() {
        val task = basicTaskSetup()

        val imageDirectory = mock<Directory>()
        whenever(mockVersionedSdkLoader.sdkImageDirectoryProvider(any()))
            .thenReturn(FakeGradleProperty(imageDirectory))
        whenever(avdService.avdProvider(any(), any(), any(), any()))
            .thenReturn(FakeGradleProperty(mock<Directory>()))

        task.taskAction()

        verify(mockVersionedSdkLoader).emulatorDirectoryProvider
        verify(mockVersionedSdkLoader)
            .sdkImageDirectoryProvider("system-images;android-29;default;x86_64")
        verifyNoMoreInteractions(mockVersionedSdkLoader)
        verify(emulatorProvider).get()

        verify(avdService)
            .avdProvider(
                argThat {
                    this is FakeGradleProperty && this.get() == imageDirectory
                },
                eq("system-images;android-29;default;x86_64"),
                eq("dev29_default_x86_64_Pixel_2"),
                eq("Pixel 2")
            )
        verify(avdService)
            .ensureLoadableSnapshot(
                "dev29_default_x86_64_Pixel_2"
            )
        verifyNoMoreInteractions(avdService)
    }

    @Test
    fun testTaskAction_armTranslationUnavailableOnX86() {
        val task = basicTaskSetup()

        doReturn(realPropertyFor("arm64-v8a")).whenever(task).testedAbi
        doReturn(realPropertyFor("x86")).whenever(task).abi

        val error = assertThrows(IllegalStateException::class.java) {
            task.taskAction()
        }

        assertThat(error.message).isEqualTo(
            """
                ARM translation is not available for x86 system images.
                An x86 image was selected because the image was available for the
                given sdkVersion and require64Bit = false for someDeviceName.
                This configuration may be intentional as someDeviceName may be configured for
                testing on a different machine.
                If ARM translation is not intended for this device, set testedAbi = "x86"
            """.trimIndent()
        )
    }

    @Test
    fun testTaskAction_armTranslationUnavailableOnIncompatibleSource() {
        val task = basicTaskSetup()

        doReturn(realPropertyFor("arm64-v8a")).whenever(task).testedAbi
        // Not available for google_atd, default, and aosp_atd sources.
        doReturn(realPropertyFor("google-atd")).whenever(task).systemImageVendor
        doReturn(realPropertyFor(35)).whenever(task).sdkVersion

        val wrongSourceError = assertThrows(IllegalStateException::class.java) {
            task.taskAction()
        }

        assertThat(wrongSourceError.message).isEqualTo(
            """
                ARM translation is only available for Google APIs or Play Store images
                with an API level of 30 or higher.
                someDeviceName has a systemImageSource = "google-atd"
                and sdkVersion = 35
                This may be intentional as someDeviceName may be configured for
                testing on an ARM system and not an x86_64 system.
                If ARM is not the intended ABI for this device, set testedAbi = "x86_64"
                If NDK translation is intended for this device, set
                systemImageSource = "google" and set the sdkVersion to 30 or higher.
            """.trimIndent()
        )

        doReturn(realPropertyFor("google")).whenever(task).systemImageVendor
        // Not available for api < 30
        doReturn(realPropertyFor(29)).whenever(task).sdkVersion

        val wrongSdkVersionError = assertThrows(IllegalStateException::class.java) {
            task.taskAction()
        }

        assertThat(wrongSdkVersionError.message).isEqualTo(
            """
                ARM translation is only available for Google APIs or Play Store images
                with an API level of 30 or higher.
                someDeviceName has a systemImageSource = "google"
                and sdkVersion = 29
                This may be intentional as someDeviceName may be configured for
                testing on an ARM system and not an x86_64 system.
                If ARM is not the intended ABI for this device, set testedAbi = "x86_64"
                If NDK translation is intended for this device, set
                systemImageSource = "google" and set the sdkVersion to 30 or higher.
            """.trimIndent()
        )
    }

    @Test
    fun testTaskAction_armTranslationWorksForValidTargets() {
        val task = basicTaskSetup()

        val imageDirectory = mock<Directory>()
        whenever(mockVersionedSdkLoader.sdkImageDirectoryProvider(any()))
            .thenReturn(FakeGradleProperty(imageDirectory))
        whenever(avdService.avdProvider(any(), any(), any(), any()))
            .thenReturn(FakeGradleProperty(mock<Directory>()))

        // abi == testedAbi -> no issue
        doReturn(realPropertyFor("arm64-v8a")).whenever(task).testedAbi
        doReturn(realPropertyFor("arm64-v8a")).whenever(task).abi

        task.taskAction()

        // x86 tested on x86_64 -> no issue
        doReturn(realPropertyFor("x86")).whenever(task).testedAbi
        doReturn(realPropertyFor("x86_64")).whenever(task).abi

        task.taskAction()

        // null works but warns when not on arm64_v8a
        doReturn(realEmptyPropertyFor<String>()).whenever(task).testedAbi
        doReturn(realPropertyFor("x86_64")).whenever(task).abi
        doReturn(realPropertyFor("google")).whenever(task).systemImageVendor
        doReturn(realPropertyFor(35)).whenever(task).sdkVersion

        task.taskAction()

        verify(task.logger).warn(
            """
                The device "someDeviceName" does not specify a "testedAbi".
                This currently defaults to "x86_64", but will change to "arm64-v8a" in AGP 10.0.

                In AGP 10.0, this device will rely on NDK translation to run tests.
                To keep the current behavior (avoiding NDK translation),
                explicitly set the ABI: testedAbi = "x86_64"
            """.trimIndent()
        )

        // warning changes if the managed device will no longer be usable, when ARM
        // is selected by default.
        doReturn(realPropertyFor(29)).whenever(task).sdkVersion

        task.taskAction()

        verify(task.logger).warn(
            """
                The device "someDeviceName" does not specify a "testedAbi".
                This currently defaults to "x86_64", but will change to "arm64-v8a" in AGP 10.0.

                The system image configured for "someDeviceName" does not support NDK translation,
                so it will be unable to run tests built for "arm64-v8a".

                To keep the current behavior and prevent test failures in AGP 10.0,
                explicitly set the ABI: testedAbi = "x86_64"
            """.trimIndent()
        )

    }

    @Test
    fun testTaskAction_missingImage() {
        val task = basicTaskSetup()

        whenever(mockVersionedSdkLoader.offlineMode).thenReturn(true)
        whenever(mockVersionedSdkLoader.sdkImageDirectoryProvider(any()))
            .thenReturn(FakeGradleProperty(null))

        val error = assertThrows(IllegalStateException::class.java) {
            task.taskAction()
        }
        assertThat(error.message).isEqualTo(
            """
                The system image for someDeviceName is not available and Gradle is in offline mode.
                Could not download the image or find other compatible images.
            """.trimIndent()
        )

        verify(mockVersionedSdkLoader)
            .sdkImageDirectoryProvider("system-images;android-29;default;x86_64")
        verify(mockVersionedSdkLoader)
            .offlineMode
        verifyNoMoreInteractions(mockVersionedSdkLoader)
        verifyNoInteractions(avdService)
    }

    @Test
    fun testTaskAction_failsWhenMinorVersionOnOldApi() {
        val task = basicTaskSetup()

        doReturn(realPropertyFor(35)).whenever(task).sdkVersion
        doReturn(realPropertyFor(1)).whenever(task).sdkMinorVersion
        whenever(mockVersionedSdkLoader.offlineMode).thenReturn(true)
        whenever(mockVersionedSdkLoader.sdkImageDirectoryProvider(any()))
            .thenReturn(FakeGradleProperty(null))

        val error = assertThrows(IllegalStateException::class.java) {
            task.taskAction()
        }
        assertThat(error.message).isEqualTo(
            """
                someDeviceName has a minor version specified for
                sdkVersion = 35. The minimum api version that supports minor
                versions is 36.
            """.trimIndent()
        )
    }

    @Test
    fun testTaskAction_selectsImageBasedOnMinorVersion() {
        val task = basicTaskSetup()

        doReturn(realPropertyFor(36)).whenever(task).sdkVersion
        doReturn(realPropertyFor(1)).whenever(task).sdkMinorVersion
        whenever(mockVersionedSdkLoader.offlineMode).thenReturn(true)
        whenever(mockVersionedSdkLoader.sdkImageDirectoryProvider(any()))
            .thenReturn(FakeGradleProperty(null))

        val error = assertThrows(IllegalStateException::class.java) {
            task.taskAction()
        }
        assertThat(error.message).isEqualTo(
            """
                The system image for someDeviceName is not available and Gradle is in offline mode.
                Could not download the image or find other compatible images.
            """.trimIndent()
        )

        verify(mockVersionedSdkLoader)
            .sdkImageDirectoryProvider("system-images;android-36.1;default;x86_64")
        verify(mockVersionedSdkLoader)
            .offlineMode
        verifyNoMoreInteractions(mockVersionedSdkLoader)
        verifyNoInteractions(avdService)
    }

    @Test
    fun testTaskAction_errorOnAutoProfile() {
        val task = basicTaskSetup()
        doReturn(realPropertyFor("Automotive (1024p landscape)"))
            .whenever(task).hardwareProfile

        val error = assertThrows(IllegalStateException::class.java) {
            task.taskAction()
        }
        assertThat(error.message).isEqualTo(
            """
                someDeviceName has a device profile of Automotive (1024p landscape).
                TV and Auto devices are presently not supported with Gradle Managed Devices.
            """.trimIndent()
        )
    }

    @Test
    fun creationAction_configureTask() {
        try {
            // Need to use a custom set up environment to ensure deterministic behavior.
            SystemPropertyOverrides().use { systemPropertyOverrides ->
                // This will ensure the config believes we are running on an x86_64 Linux machine.
                // This will guarantee the x86 system-images are selected.
                systemPropertyOverrides.setProperty("os.name", "Linux")
                Environment.instance = object : Environment() {
                    override fun getVariable(name: EnvironmentVariable): String? =
                        if (name.key == "HOSTTYPE") "x86_64" else null
                }
                systemPropertyOverrides.setProperty("os.arch", "x86_64")

                val config = ManagedDeviceInstrumentationTestSetupTask.CreationAction(
                    "setupTaskName",
                    ManagedVirtualDevice("testName").also {
                        it.device = "Pixel 3"
                        it.sdkVersion = 27
                        it.systemImageSource = "aosp"
                    },
                    globalConfig
                )

                val task = mock<ManagedDeviceInstrumentationTestSetupTask>(defaultAnswer = RETURNS_DEEP_STUBS)

                // default path for emulator mode ("auto-no-window")
                whenever(
                    globalConfig.services.projectOptions[
                            StringOption.GRADLE_MANAGED_DEVICE_EMULATOR_GPU_MODE])
                    .thenReturn(null)

                whenever(globalConfig.compileSdkHashString).thenReturn("some_version")
                whenever(globalConfig.buildToolsRevision).thenReturn(Revision.parseRevision("5.1"))

                // We need to create mock properties to verify/capture values in the task as
                // RETURNS_DEEP_STUBS does not work as expected with verify. Also, we can't use
                // FakeGradleProperties because they do not support disallowChanges().
                val sdkProperty = mockEmptyProperty<SdkComponentsBuildService>()
                val avdProperty = mockEmptyProperty<AvdComponentsBuildService>()
                val compileSdkVersion = mockEmptyProperty<String>()
                val buildToolsRevision = mockEmptyProperty<Revision>()
                val abiProperty = mockEmptyProperty<String>()
                val sdkVersion = mockEmptyProperty<Int>()
                val sdkMinorVersion = mockEmptyProperty<Int>()
                val systemImageVendor = mockEmptyProperty<String>()
                val hardwareProfile = mockEmptyProperty<String>()
                val managedDeviceName = mockEmptyProperty<String>()
                val require64Bit = mockEmptyProperty<Boolean>()

                whenever(task.sdkService).thenReturn(sdkProperty)
                whenever(task.avdService).thenReturn(avdProperty)
                whenever(task.compileSdkVersion).thenReturn(compileSdkVersion)
                whenever(task.buildToolsRevision).thenReturn(buildToolsRevision)
                whenever(task.abi).thenReturn(abiProperty)
                whenever(task.sdkVersion).thenReturn(sdkVersion)
                whenever(task.sdkMinorVersion).thenReturn(sdkMinorVersion)
                whenever(task.systemImageVendor).thenReturn(systemImageVendor)
                whenever(task.hardwareProfile).thenReturn(hardwareProfile)
                whenever(task.managedDeviceName).thenReturn(managedDeviceName)
                whenever(task.require64Bit).thenReturn(require64Bit)

                config.configure(task)

                verify(sdkProperty).set(any<Provider<SdkComponentsBuildService>>())
                verify(sdkProperty).disallowChanges()
                verifyNoMoreInteractions(sdkProperty)

                verify(avdProperty).set(any<Provider<AvdComponentsBuildService>>())
                verify(avdProperty).disallowChanges()
                verifyNoMoreInteractions(avdProperty)

                verify(compileSdkVersion).set("some_version")
                verify(compileSdkVersion).disallowChanges()
                verifyNoMoreInteractions(compileSdkVersion)

                verify(buildToolsRevision).set(Revision.parseRevision("5.1"))
                verify(buildToolsRevision).disallowChanges()
                verifyNoMoreInteractions(buildToolsRevision)

                verify(abiProperty).set("x86")
                verify(abiProperty).disallowChanges()
                verifyNoMoreInteractions(abiProperty)

                verify(sdkVersion).set(27)
                verify(sdkVersion).disallowChanges()
                verifyNoMoreInteractions(sdkVersion)

                verify(sdkMinorVersion).set(0)
                verify(sdkMinorVersion).disallowChanges()
                verifyNoMoreInteractions(sdkVersion)

                verify(systemImageVendor).set("aosp")
                verify(systemImageVendor).disallowChanges()
                verifyNoMoreInteractions(systemImageVendor)

                verify(hardwareProfile).set("Pixel 3")
                verify(hardwareProfile).disallowChanges()
                verifyNoMoreInteractions(hardwareProfile)

                verify(managedDeviceName).set("testName")
                verify(managedDeviceName).disallowChanges()
                verifyNoMoreInteractions(managedDeviceName)

                verify(require64Bit).set(false)
                verify(require64Bit).disallowChanges()
                verifyNoMoreInteractions(require64Bit)
            }
        } finally {
            Environment.instance = Environment.SYSTEM
        }
    }

    @Test
    fun creationAction_configureTaskWithPreview() {
        try {
            // Need to use a custom set up environment to ensure deterministic behavior.
            SystemPropertyOverrides().use { systemPropertyOverrides ->
                // This will ensure the config believes we are running on an x86_64 Linux machine.
                // This will guarantee the x86 system-images are selected.
                systemPropertyOverrides.setProperty("os.name", "Linux")
                Environment.instance = object : Environment() {
                    override fun getVariable(name: EnvironmentVariable): String? =
                        if (name.key == "HOSTTYPE") "x86_64" else null
                }
                systemPropertyOverrides.setProperty("os.arch", "x86_64")

                val config = ManagedDeviceInstrumentationTestSetupTask.CreationAction(
                    "setupTaskName",
                    ManagedVirtualDevice("testName").also {
                        it.device = "Pixel 3"
                        it.apiPreview = "Q"
                        it.systemImageSource = "aosp"
                    },
                    globalConfig
                )

                val task = mock<ManagedDeviceInstrumentationTestSetupTask>(defaultAnswer = RETURNS_DEEP_STUBS)

                // default path for emulator mode ("auto-no-window")
                whenever(
                    globalConfig.services.projectOptions[
                            StringOption.GRADLE_MANAGED_DEVICE_EMULATOR_GPU_MODE])
                    .thenReturn(null)

                whenever(globalConfig.compileSdkHashString).thenReturn("some_version")
                whenever(globalConfig.buildToolsRevision).thenReturn(Revision.parseRevision("5.1"))

                // We need to create mock properties to verify/capture values in the task as
                // RETURNS_DEEP_STUBS does not work as expected with verify. Also, we can't use
                // FakeGradleProperties because they do not support disallowChanges().
                val sdkProperty = mockEmptyProperty<SdkComponentsBuildService>()
                val avdProperty = mockEmptyProperty<AvdComponentsBuildService>()
                val compileSdkVersion = mockEmptyProperty<String>()
                val buildToolsRevision = mockEmptyProperty<Revision>()
                val abiProperty = mockEmptyProperty<String>()
                val sdkVersion = mockEmptyProperty<Int>()
                val sdkMinorVersion = mockEmptyProperty<Int>()
                val systemImageVendor = mockEmptyProperty<String>()
                val hardwareProfile = mockEmptyProperty<String>()
                val managedDeviceName = mockEmptyProperty<String>()
                val require64Bit = mockEmptyProperty<Boolean>()

                whenever(task.sdkService).thenReturn(sdkProperty)
                whenever(task.avdService).thenReturn(avdProperty)
                whenever(task.compileSdkVersion).thenReturn(compileSdkVersion)
                whenever(task.buildToolsRevision).thenReturn(buildToolsRevision)
                whenever(task.abi).thenReturn(abiProperty)
                whenever(task.sdkVersion).thenReturn(sdkVersion)
                whenever(task.sdkMinorVersion).thenReturn(sdkMinorVersion)
                whenever(task.systemImageVendor).thenReturn(systemImageVendor)
                whenever(task.hardwareProfile).thenReturn(hardwareProfile)
                whenever(task.managedDeviceName).thenReturn(managedDeviceName)
                whenever(task.require64Bit).thenReturn(require64Bit)

                config.configure(task)

                verify(sdkProperty).set(any<Provider<SdkComponentsBuildService>>())
                verify(sdkProperty).disallowChanges()
                verifyNoMoreInteractions(sdkProperty)

                verify(avdProperty).set(any<Provider<AvdComponentsBuildService>>())
                verify(avdProperty).disallowChanges()
                verifyNoMoreInteractions(avdProperty)

                verify(compileSdkVersion).set("some_version")
                verify(compileSdkVersion).disallowChanges()
                verifyNoMoreInteractions(compileSdkVersion)

                verify(buildToolsRevision).set(Revision.parseRevision("5.1"))
                verify(buildToolsRevision).disallowChanges()
                verifyNoMoreInteractions(buildToolsRevision)

                verify(abiProperty).set("x86")
                verify(abiProperty).disallowChanges()
                verifyNoMoreInteractions(abiProperty)

                verify(sdkVersion).set(28)
                verify(sdkVersion).disallowChanges()
                verifyNoMoreInteractions(sdkVersion)

                verify(sdkMinorVersion).set(0)
                verify(sdkMinorVersion).disallowChanges()
                verifyNoMoreInteractions(sdkVersion)

                verify(systemImageVendor).set("aosp")
                verify(systemImageVendor).disallowChanges()
                verifyNoMoreInteractions(systemImageVendor)

                verify(hardwareProfile).set("Pixel 3")
                verify(hardwareProfile).disallowChanges()
                verifyNoMoreInteractions(hardwareProfile)

                verify(managedDeviceName).set("testName")
                verify(managedDeviceName).disallowChanges()
                verifyNoMoreInteractions(managedDeviceName)

                verify(require64Bit).set(false)
                verify(require64Bit).disallowChanges()
                verifyNoMoreInteractions(require64Bit)
            }
        } finally {
            Environment.instance = Environment.SYSTEM
        }
    }

    @Test
    fun generateSystemErrorMessage_offlineMode() {
        whenever(mockVersionedSdkLoader.offlineMode).thenReturn(true)

        val result = ManagedDeviceInstrumentationTestSetupTask.generateSystemImageErrorMessage(
            "test_device_name",
            28,
            0,
            null,
            "aosp",
            "",
            true,
            mockVersionedSdkLoader
        )

        assertThat(result).isEqualTo(
            """
                The system image for test_device_name is not available and Gradle is in offline mode.
                Could not download the image or find other compatible images.
            """.trimIndent()
        )
    }

    @Test
    fun generateSystemErrorMessage_onlineMode() {
        whenever(mockVersionedSdkLoader.offlineMode).thenReturn(false)
        whenever(mockVersionedSdkLoader.allSystemImageHashes()).thenReturn(listOf())

        val result = ManagedDeviceInstrumentationTestSetupTask.generateSystemImageErrorMessage(
            "some_test_device",
            28,
            0,
            null,
            "aosp",
            "",
            true,
            mockVersionedSdkLoader
        )

        assertThat(result).isEqualTo(
            "System Image specified by some_test_device does not exist.\n\n" +
                    "Try one of the following fixes:"
        )
    }
}
