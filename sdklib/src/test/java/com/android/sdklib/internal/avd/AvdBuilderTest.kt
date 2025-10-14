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
package com.android.sdklib.internal.avd

import com.android.prefs.AbstractAndroidLocations
import com.android.resources.ScreenOrientation
import com.android.sdklib.devices.Abi
import com.android.sdklib.devices.DeviceManager
import com.android.sdklib.devices.Storage
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.sdklib.testing.TestSystemImages
import com.android.testutils.file.createInMemoryFileSystem
import com.android.testutils.file.recordExistingFile
import com.android.testutils.file.someRoot
import com.android.utils.NullLogger
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.io.path.exists
import kotlin.reflect.full.memberProperties
import org.junit.Test

class AvdBuilderTest {

  private val fileSystem = createInMemoryFileSystem()
  val root = fileSystem.someRoot
  val prefsRoot = root.resolve("android")
  val avdFolder = prefsRoot.resolve(AbstractAndroidLocations.FOLDER_AVD)
  val sdkHandler = AndroidSdkHandler(root.resolve("sdk"), prefsRoot)
  val deviceManager = DeviceManager.createInstance(sdkHandler, NullLogger.getLogger())
  val avdManager =
    AvdManager.createInstance(sdkHandler, avdFolder, deviceManager, NullLogger.getLogger())

  private fun createPixel8Builder(): AvdBuilder {
    val pixel8 = deviceManager.getDevice("pixel_8", "Google")!!
    return avdManager.createAvdBuilder(pixel8)
  }

  @Test
  fun createAvdBuilder() {
    val avdBuilder = createPixel8Builder()

    with(avdBuilder) {
      assertThat(displayName).isEqualTo("Pixel 8")
      assertThat(systemImage).isNull()
      assertThat(sdCard).isNull()
      assertThat(skin).isNotNull()
      assertThat(showDeviceFrame).isTrue()
      assertThat(screenOrientation).isEqualTo(ScreenOrientation.PORTRAIT)

      assertThat(cpuCoreCount).isAtLeast(2)  // depends on what machine this test runs on
      assertThat(ram).isEqualTo(EmulatedProperties.MAX_DEFAULT_RAM_SIZE)
      assertThat(vmHeap.size).isGreaterThan(0)
      assertThat(internalStorage).isEqualTo(EmulatedProperties.DEFAULT_INTERNAL_STORAGE)

      assertThat(frontCamera).isEqualTo(AvdCamera.NONE)
      assertThat(backCamera).isEqualTo(AvdCamera.NONE)

      assertThat(gpuMode).isEqualTo(GpuMode.OFF)
      assertThat(enableKeyboard).isTrue()

      assertThat(networkLatency).isEqualTo(AvdNetworkLatency.NONE)
      assertThat(networkSpeed).isEqualTo(AvdNetworkSpeed.FULL)

      assertThat(bootMode).isEqualTo(QuickBoot)
    }
  }

  @Test
  fun memory() {
    val builder = createPixel8Builder()
    builder.ram = Storage(4, Storage.Unit.GiB)
    builder.vmHeap = Storage(1, Storage.Unit.GiB)

    assertThat(builder.configProperties()).containsEntry(ConfigKey.RAM_SIZE, "4096")
    assertThat(builder.configProperties()).containsEntry(ConfigKey.VM_HEAP_SIZE, "1024")
  }

  @Test
  fun gpuMode() {
    val builder = createPixel8Builder()

    builder.gpuMode = GpuMode.OFF
    assertThat(builder.configProperties()).containsEntry(ConfigKey.GPU_EMULATION, "no")

    builder.gpuMode = GpuMode.AUTO
    assertThat(builder.configProperties()).containsEntry(ConfigKey.GPU_EMULATION, "yes")
  }

  @Test
  fun createForExistingDevice() {
    val testSystemImages = TestSystemImages(sdkHandler)
    val android33ext4 = testSystemImages.api33ext4.image

    val pixel8 = deviceManager.getDevice("pixel_8", "Google")!!
    val avdBuilder = avdManager.createAvdBuilder(pixel8)
    avdBuilder.metadataIniPath = avdFolder.resolve("Pixel_8.ini")
    avdBuilder.avdFolder = avdFolder.resolve("Pixel_8.avd")
    avdBuilder.systemImage = android33ext4

    val createdAvd = avdManager.createAvd(avdBuilder)
    checkNotNull(createdAvd)

    avdManager.reloadAvds()
    assertThat(avdManager.allAvds).hasLength(1)

    val avd = avdManager.allAvds[0]
    val builderFromDisk = AvdBuilder.createForExistingDevice(pixel8, avd)

    assertThat(avdBuilder.systemImage).isEqualTo(builderFromDisk.systemImage)
    assertThat(avdBuilder.skin).isEqualTo(builderFromDisk.skin)
    assertThat(avdBuilder.sdCard).isEqualTo(builderFromDisk.sdCard)
    assertThat(avdBuilder.androidVersion).isEqualTo(builderFromDisk.androidVersion)

    for (property in AvdBuilder::class.memberProperties) {
      assertWithMessage(property.name)
        .that(property.get(builderFromDisk))
        .isEqualTo(property.get(avdBuilder))
    }
  }

  @Test
  fun createResizableAvd() {
    val testSystemImages = TestSystemImages(sdkHandler)
    val android33ext4 = testSystemImages.api33ext4.image

    val avdBuilder = avdManager.createAvdBuilder(deviceManager.getDevice("resizable", "Generic")!!)
    avdBuilder.systemImage = android33ext4

    val avdInfo = avdManager.createAvd(avdBuilder)

    val avdConfig = avdInfo.properties
    assertThat(avdConfig[HardwareProperties.HW_LCD_FOLDED_WIDTH]).isEqualTo("1080")
    assertThat(avdConfig[HardwareProperties.HW_LCD_FOLDED_HEIGHT]).isEqualTo("2092")
    assertThat(avdConfig[HardwareProperties.HW_LCD_FOLDED_X_OFFSET]).isEqualTo("0")
    assertThat(avdConfig[HardwareProperties.HW_LCD_FOLDED_Y_OFFSET]).isEqualTo("0")
    assertThat(avdConfig[ConfigKey.HINGE]).isEqualTo("yes")
    assertThat(avdConfig[ConfigKey.HINGE_COUNT]).isEqualTo("1")
    assertThat(avdConfig[ConfigKey.HINGE_TYPE]).isEqualTo("1")
    assertThat(avdConfig[ConfigKey.HINGE_SUB_TYPE]).isEqualTo("1")
    assertThat(avdConfig[ConfigKey.HINGE_RANGES]).isEqualTo("0-180")
    assertThat(avdConfig[ConfigKey.HINGE_DEFAULTS]).isEqualTo("180")
    assertThat(avdConfig[ConfigKey.HINGE_AREAS]).isEqualTo("1080-0-0-1840")
    assertThat(avdConfig[ConfigKey.POSTURE_LISTS]).isEqualTo("1, 2, 3")
    assertThat(avdConfig[ConfigKey.HINGE_ANGLES_POSTURE_DEFINITIONS])
      .isEqualTo("0-30, 30-150, 150-180")
    assertThat(avdConfig[ConfigKey.HINGE_ANGLES_POSTURE_DEFINITIONS])
      .isEqualTo("0-30, 30-150, 150-180")
    assertThat(avdConfig[ConfigKey.RESIZABLE_CONFIG])
      .isEqualTo(
        "phone-0-1080-2400-420, foldable-1-2208-1840-420, tablet-2-1920-1200-240, desktop-3-1920-1080-160"
      )
    assertThat(avdConfig[ConfigKey.SKIN_NAME]).isEqualTo("1080x2400")
    assertThat(avdConfig[ConfigKey.SKIN_PATH]).isEqualTo("1080x2400")
  }

  @Test
  fun createFoldableAvd() {
    val testSystemImages = TestSystemImages(sdkHandler)
    val android33ext4 = testSystemImages.api33ext4.image

    val avdBuilder = avdManager.createAvdBuilder(deviceManager.getDevice("pixel_fold", "Google")!!)
    avdBuilder.systemImage = android33ext4

    val avdInfo = avdManager.createAvd(avdBuilder)

    val avdConfig = avdInfo.properties
    assertThat(avdConfig[HardwareProperties.HW_LCD_FOLDED_WIDTH]).isEqualTo("1080")
    assertThat(avdConfig[HardwareProperties.HW_LCD_FOLDED_HEIGHT]).isEqualTo("2092")
    assertThat(avdConfig[HardwareProperties.HW_LCD_FOLDED_X_OFFSET]).isEqualTo("0")
    assertThat(avdConfig[HardwareProperties.HW_LCD_FOLDED_Y_OFFSET]).isEqualTo("0")
    assertThat(avdConfig[ConfigKey.HINGE]).isEqualTo("yes")
    assertThat(avdConfig[ConfigKey.HINGE_COUNT]).isEqualTo("1")
    assertThat(avdConfig[ConfigKey.HINGE_TYPE]).isEqualTo("1")
    assertThat(avdConfig[ConfigKey.HINGE_SUB_TYPE]).isEqualTo("1")
    assertThat(avdConfig[ConfigKey.HINGE_RANGES]).isEqualTo("0-180")
    assertThat(avdConfig[ConfigKey.HINGE_DEFAULTS]).isEqualTo("180")
    assertThat(avdConfig[ConfigKey.HINGE_AREAS]).isEqualTo("1080-0-0-1840")
    assertThat(avdConfig[ConfigKey.POSTURE_LISTS]).isEqualTo("1, 2, 3")
    assertThat(avdConfig[ConfigKey.HINGE_ANGLES_POSTURE_DEFINITIONS])
      .isEqualTo("0-30, 30-150, 150-180")
    assertThat(avdConfig[ConfigKey.HINGE_ANGLES_POSTURE_DEFINITIONS])
      .isEqualTo("0-30, 30-150, 150-180")
    assertThat(avdConfig[ConfigKey.SKIN_NAME]).isEqualTo("1840x2208")
    assertThat(avdConfig[ConfigKey.SKIN_PATH]).isEqualTo("1840x2208")
  }

  @Test
  fun createAvdWithPreferredAbi() {
    val testSystemImages = TestSystemImages(sdkHandler)
    val android33ext4 = testSystemImages.api33ext4.image
    val device = deviceManager.getDevice("medium_phone", "Generic")!!
    val avdBuilder = avdManager.createAvdBuilder(device)
    avdBuilder.systemImage = android33ext4
    avdBuilder.userSettings[UserSettingsKey.PREFERRED_ABI] = Abi.RISCV64.toString()

    val avdInfo = avdManager.createAvd(avdBuilder)

    assertThat(avdInfo.userSettings[UserSettingsKey.PREFERRED_ABI])
      .isEqualTo(Abi.RISCV64.toString())

    val avdBuilder2 = AvdBuilder.createForExistingDevice(device, avdInfo)
    avdBuilder2.userSettings[UserSettingsKey.PREFERRED_ABI] = Abi.X86_64.toString()
    val avdInfo2 = avdManager.editAvd(avdInfo, avdBuilder2)

    assertThat(avdInfo2.userSettings[UserSettingsKey.PREFERRED_ABI])
      .isEqualTo(Abi.X86_64.toString())
  }

    @Test
    fun createAvdWithBackground() {
        val testSystemImages = TestSystemImages(sdkHandler)
        val android33ext4 = testSystemImages.api33ext4.image

        val avdBuilder = avdManager.createAvdBuilder(deviceManager.getDevice("resizable", "Generic")!!)
        avdBuilder.systemImage = android33ext4
        val backgroundFile = root.resolve("tmp").resolve("img1.png")
        backgroundFile.recordExistingFile(contents = "abcd")
        avdBuilder.background = backgroundFile

        val avdInfo = avdManager.createAvd(avdBuilder)

        assertThat(avdInfo.environment).containsExactly(EnvironmentKey.IMAGE, "img1.png")
        assertThat(avdInfo.dataFolderPath.resolve("img1.png").exists()).isTrue()
    }
}
