/*
 * Copyright (C) 2016 The Android Open Source Project
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

import com.android.io.CancellableFileIo
import com.android.prefs.AbstractAndroidLocations
import com.android.sdklib.AndroidVersion
import com.android.sdklib.PathFileWrapper
import com.android.sdklib.devices.DeviceManager
import com.android.sdklib.internal.avd.AvdManager.Companion.ENVIRONMENT_DIR
import com.android.sdklib.internal.avd.ConfigKey.ENCODING
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.sdklib.repository.targets.SystemImage
import com.android.sdklib.testing.TestSystemImages
import com.android.testutils.MockLog
import com.android.testutils.file.createInMemoryFileSystem
import com.android.testutils.file.recordExistingFile
import com.android.testutils.file.someRoot
import com.android.testutils.truth.PathSubject.assertThat
import com.android.utils.NullLogger
import com.android.utils.PathUtils
import com.google.common.collect.ImmutableMap
import com.google.common.truth.Truth.assertThat
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.TreeMap
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class AvdManagerTest {
  @get:Rule var name = TestName()

  private lateinit var androidSdkHandler: AndroidSdkHandler
  private lateinit var avdManager: AvdManager
  private lateinit var avdFolder: Path
  private lateinit var gradleManagedDeviceAvdManager: AvdManager
  private lateinit var gradleManagedDeviceAvdFolder: Path
  private lateinit var systemImages: TestSystemImages
  private val mockFs = createInMemoryFileSystem()

  @Before
  fun setUp() {
    val root = mockFs.someRoot
    root.resolve("sdk/tools/lib/emulator/snapshots.img").recordExistingFile()
    val prefsRoot = root.resolve("android-home")
    androidSdkHandler = AndroidSdkHandler(root.resolve("sdk"), prefsRoot)
    systemImages = TestSystemImages(androidSdkHandler)
    avdManager =
      AvdManager.createInstance(
        androidSdkHandler,
        prefsRoot.resolve(AbstractAndroidLocations.FOLDER_AVD),
        DeviceManager.createInstance(androidSdkHandler, NullLogger.getLogger()),
        NullLogger.getLogger(),
      )
    avdFolder = AvdInfo.getDefaultAvdFolder(avdManager, name.methodName, false)
    gradleManagedDeviceAvdManager =
      AvdManager.createInstance(
        androidSdkHandler,
        prefsRoot.resolve(AbstractAndroidLocations.FOLDER_AVD).resolve(AbstractAndroidLocations.FOLDER_GRADLE_AVD),
        DeviceManager.createInstance(androidSdkHandler, NullLogger.getLogger()),
        NullLogger.getLogger(),
      )
    gradleManagedDeviceAvdFolder = AvdInfo.getDefaultAvdFolder(gradleManagedDeviceAvdManager, name.methodName, false)
  }

  @Test
  fun getPidHardwareQemuIniLockScannerHasNextLong() {
    // Arrange
    val avd = avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api23.image)
    val file = avdManager.resolveLockFile(avd, "hardware-qemu.ini.lock")
    Files.createDirectories(file.parent)
    Files.write(file, "412503".toByteArray())

    // Act
    val pid = avdManager.getPid(avd)

    // Assert
    assertThat(pid).isEqualTo(412503)
  }

  @Test
  fun getPidHardwareQemuIniLockIsEmpty() {
    // Arrange
    val avd = avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api23.image)
    val file = avdManager.resolveLockFile(avd, "hardware-qemu.ini.lock")
    Files.createDirectories(file.parent)
    Files.createFile(file)

    // Act
    val pid = avdManager.getPid(avd)

    // Assert
    assertThat(pid).isEqualTo(0)
  }

  @Test
  fun getPidHardwareQemuIniLockScannerDoesntHaveNextLong() {
    // Arrange
    val avd = avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api23.image)
    val file = avdManager.resolveLockFile(avd, "hardware-qemu.ini.lock")
    Files.createDirectories(file.parent)
    Files.write(file, "notlong".toByteArray())

    // Act
    val pid = avdManager.getPid(avd)

    // Assert
    assertThat(pid).isEqualTo(0)
  }

  @Test
  fun getPidUserdataQemuImgLockScannerHasNextLong() {
    // Arrange
    val avd = avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api23.image)
    val file = avdManager.resolveLockFile(avd, "userdata-qemu.img.lock")
    Files.createDirectories(file.parent)
    Files.write(file, "412503".toByteArray())

    // Act
    val pid = avdManager.getPid(avd)

    // Assert
    assertThat(pid).isEqualTo(412503)
  }

  @Test
  fun getPid() {
    // Arrange
    val avd = avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api23.image)

    // Act
    val pid = avdManager.getPid(avd)

    // Assert
    assertThat(pid).isEqualTo(0)
  }

  @Test
  fun createAvdWithoutSnapshot() {
    avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api23.image)
    val metadataIniFile = avdFolder.parent.resolve(name.methodName + ".ini")
    val metadata = AvdManager.parseIniFile(PathFileWrapper(metadataIniFile), null)!!
    assertThat(metadata["target"]).isEqualTo("android-23")
    val avdConfigFile = avdFolder.resolve("config.ini")
    assertTrue("Expected config.ini in $avdFolder", CancellableFileIo.exists(avdConfigFile))
    val properties = AvdManager.parseIniFile(PathFileWrapper(avdConfigFile), null)!!
    assertThat(properties["image.sysdir.1"]).isEqualTo("system-images/android-23/default/x86/".replace('/', File.separatorChar))
    assertNull(properties["snapshot.present"])
    assertFalse(
      "Expected NO " + AvdManager.USERDATA_IMG + " in " + avdFolder,
      CancellableFileIo.exists(avdFolder.resolve(AvdManager.USERDATA_IMG)),
    )
    assertFalse(
      "Expected NO " + AvdManager.USERDATA_QEMU_IMG + " in " + avdFolder,
      CancellableFileIo.exists(avdFolder.resolve(AvdManager.USERDATA_QEMU_IMG)),
    )
    assertFalse("Expected NO snapshots.img in " + avdFolder, CancellableFileIo.exists(avdFolder.resolve("snapshots.img")))
  }

  @Test
  fun createAvdWithUserdata() {
    avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api21.image)
    val avdConfigFile = avdFolder.resolve("config.ini")
    assertTrue("Expected config.ini in $avdFolder", Files.exists(avdConfigFile))
    val properties = AvdManager.parseIniFile(PathFileWrapper(avdConfigFile), null)!!
    assertFalse(Files.exists(avdFolder.resolve("boot.prop")))
    assertThat(properties["image.sysdir.1"]).isEqualTo("system-images/android-21/default/x86/".replace('/', File.separatorChar))
    assertNull(properties["snapshot.present"])
    assertTrue("Expected " + AvdManager.USERDATA_IMG + " in " + avdFolder, Files.exists(avdFolder.resolve(AvdManager.USERDATA_IMG)))
    assertFalse(
      "Expected NO " + AvdManager.USERDATA_QEMU_IMG + " in " + avdFolder,
      Files.exists(avdFolder.resolve(AvdManager.USERDATA_QEMU_IMG)),
    )
    assertFalse("Expected NO snapshots.img in " + avdFolder, Files.exists(avdFolder.resolve("snapshots.img")))
  }

  @Test
  fun createAvdWithNullValueUserSettings() {
    val userSettings: MutableMap<String, String> = HashMap()
    avdManager.createAvd(
      avdFolder = avdFolder,
      avdName = name.methodName,
      systemImage = systemImages.api23.image,
      userSettings = userSettings,
    )
    val avdConfigFile = avdFolder.resolve("config.ini")
    assertTrue("Expected config.ini in $avdFolder", CancellableFileIo.exists(avdConfigFile))
    val properties = AvdManager.parseIniFile(PathFileWrapper(avdConfigFile), null)!!
    assertThat(properties["image.sysdir.1"]).isEqualTo("system-images/android-23/default/x86/".replace('/', File.separatorChar))
    assertNull(properties["snapshot.present"])
    assertFalse(
      "Expected NO " + AvdManager.USERDATA_IMG + " in " + avdFolder,
      CancellableFileIo.exists(avdFolder.resolve(AvdManager.USERDATA_IMG)),
    )
    assertFalse(
      "Expected NO " + AvdManager.USERDATA_QEMU_IMG + " in " + avdFolder,
      CancellableFileIo.exists(avdFolder.resolve(AvdManager.USERDATA_QEMU_IMG)),
    )
    assertFalse("Expected NO snapshots.img in " + avdFolder, CancellableFileIo.exists(avdFolder.resolve("snapshots.img")))
    val userSettingsIniFile = AvdInfo.getUserSettingsPath(avdFolder)
    assertTrue("Expected user-settings.ini in $avdFolder", Files.exists(userSettingsIniFile))
  }

  @Test
  fun createAvdWithBootProps() {
    val expected: MutableMap<String, String> = TreeMap()
    expected["ro.build.display.id"] = "sdk-eng 4.3 JB_MR2 774058 test-keys"
    expected["ro.board.platform"] = ""
    expected["ro.build.tags"] = "test-keys"
    avdManager.createAvd(
      avdFolder = avdFolder,
      avdName = name.methodName,
      systemImage = systemImages.api24PlayStore.image,
      userSettings = expected,
      bootProps = expected,
    )
    val bootPropFile = avdFolder.resolve("boot.prop")
    assertTrue(Files.exists(bootPropFile))
    val properties = AvdManager.parseIniFile(PathFileWrapper(bootPropFile), null)!!

    // use a tree map to make sure test order is consistent
    assertThat(TreeMap(properties).toString()).isEqualTo(expected.toString())
  }

  @Test
  fun createChromeOsAvd() {
    avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.chromeOs.image)
    val avdConfigFile = avdFolder.resolve("config.ini")
    assertTrue("Expected config.ini in $avdFolder", Files.exists(avdConfigFile))
    val properties = AvdManager.parseIniFile(PathFileWrapper(avdConfigFile), null)!!
    assertThat(properties["hw.arc"]).isEqualTo("true")
    assertThat(properties["hw.cpu.arch"]).isEqualTo("x86_64")
  }

  @Test
  fun createNonChromeOsAvd() {
    avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api23.image)
    val avdConfigFile = avdFolder.resolve("config.ini")
    assertTrue("Expected config.ini in $avdFolder", Files.exists(avdConfigFile))
    val properties = AvdManager.parseIniFile(PathFileWrapper(avdConfigFile), null)!!
    assertThat(properties["hw.arc"]).isEqualTo("false")
    assertThat(properties["hw.cpu.arch"]).isEqualTo("x86")
  }

  @Test
  fun createAvdForGradleManagedDevice() {
    gradleManagedDeviceAvdManager.createAvd(
      avdFolder = gradleManagedDeviceAvdFolder,
      avdName = name.methodName,
      systemImage = systemImages.api23.image,
    )
    assertThat(gradleManagedDeviceAvdManager.allAvds).hasSize(1)

    // Creating AVD in Gradle Managed Device folder (.android/avd/gradle-managed) should not
    // confuse the standard AVD manager (.android/avd).
    avdManager.reloadAvds()
    assertThat(avdManager.allAvds).isEmpty()
  }

  @Test
  fun createTabletAvd() {
    avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api34TabletPlayStore.image)
    val avdConfigFile = avdFolder.resolve("config.ini")
    assertTrue("Expected config.ini in $avdFolder", Files.exists(avdConfigFile))
    val properties = AvdManager.parseIniFile(PathFileWrapper(avdConfigFile), null)!!
    assertThat(properties["tag.id"]).isEqualTo("google_apis_playstore")
    assertThat(properties["tag.display"]).isEqualTo("Google Play")
    assertThat(properties["tag.ids"]).isEqualTo("google_apis_playstore,tablet")
    assertThat(properties["tag.displaynames"]).isEqualTo("Google Play,Tablet")
  }

  @Test
  fun createAvdWithSkin() {
    val log = MockLog()
    val deviceManager = DeviceManager.createInstance(androidSdkHandler, log)
    val device = deviceManager.getDevice("medium_phone", "Generic")!!
    val builder = avdManager.createAvdBuilder(device)
    val skinPath = androidSdkHandler.location!!.resolve("skins").resolve("pixel_8")
    skinPath.resolve("layout").recordExistingFile()
    builder.avdName = name.methodName
    builder.avdFolder = avdFolder
    builder.systemImage = systemImages.api23.image
    builder.skin = OnDiskSkin(skinPath)
    avdManager.createAvd(builder)
    val config = AvdManager.parseIniFile(PathFileWrapper(avdFolder.resolve("config.ini")), null)!!
    assertThat(config[ConfigKey.SKIN_NAME]).isEqualTo(skinPath.fileName.toString())
    assertThat(config[ConfigKey.SKIN_PATH]).isEqualTo(skinPath.toString())
  }

  @Test
  fun moveAvd() {
    val hardwareConfig = ImmutableMap.of("ro.build.display.id", "sdk-eng 4.3 JB_MR2 774058 test-keys")
    val userSettings = ImmutableMap.of("abi.type.preferred", "x86")
    val bootProps = ImmutableMap.of("ro.emulator.circular", "true")
    val backgroundFile = mockFs.someRoot.resolve("tmp").resolve("img1.png")
    val environment = ImmutableMap.of(EnvironmentKey.IMAGE, backgroundFile.toString())
    backgroundFile.recordExistingFile("abcd")
    val avdInfo =
      avdManager.createAvd(
        avdFolder = avdFolder,
        avdName = name.methodName,
        systemImage = systemImages.api24PlayStore.image,
        sdcard = InternalSdCard(SDCARD_MIN_BYTE_SIZE),
        hardwareConfig = hardwareConfig,
        userSettings = userSettings,
        bootProps = bootProps,
        environment = environment,
        deviceHasPlayStore = true,
      )
    val metadataIniFile = avdFolder.parent.resolve(name.methodName + ".ini")
    assertTrue(Files.exists(metadataIniFile))
    assertTrue(Files.exists(avdFolder.resolve("boot.prop")))
    assertTrue(Files.exists(avdFolder.resolve("user-settings.ini")))
    assertTrue(Files.exists(avdFolder.resolve(ENVIRONMENT_DIR).resolve(backgroundFile.fileName)))

    // Move the AVD, updating its name and data folder path
    val newAvdName = avdInfo.name + "_2"
    val newAvdFolder = avdInfo.dataFolderPath.resolveSibling("$newAvdName.avd")
    avdManager.moveAvd(avdInfo, newAvdName, newAvdFolder)

    // The locations of the metadata .ini and the data folder are updated
    assertFalse(Files.exists(metadataIniFile))
    assertFalse(Files.isDirectory(avdFolder))
    val newMetadataIniPath = metadataIniFile.resolveSibling("$newAvdName.ini")
    assertTrue(Files.exists(newMetadataIniPath))
    assertTrue(Files.isDirectory(newAvdFolder))
    assertTrue(Files.exists(newAvdFolder.resolve(ENVIRONMENT_DIR).resolve(backgroundFile.fileName)))

    // The contents of the metadata .ini reflect the new paths
    val metadata = AvdManager.parseIniFile(PathFileWrapper(newMetadataIniPath), null)!!
    assertThat(metadata[MetadataKey.ABS_PATH]).isEqualTo(newAvdFolder.toString())
    assertThat(metadata[MetadataKey.REL_PATH]).isEqualTo(newAvdFolder.parent.parent.relativize(newAvdFolder).toString())
    val movedBootProps = AvdManager.parseIniFile(PathFileWrapper(newAvdFolder.resolve("boot.prop")), null)!!
    movedBootProps.remove(ENCODING)
    assertThat(movedBootProps).isEqualTo(bootProps)
    val movedUserSettings = AvdManager.parseIniFile(PathFileWrapper(newAvdFolder.resolve("user-settings.ini")), null)!!
    movedUserSettings.remove(ENCODING)
    assertThat(movedUserSettings).isEqualTo(userSettings)
    val movedConfig = AvdManager.parseIniFile(PathFileWrapper(newAvdFolder.resolve("config.ini")), null)!!
    movedConfig.remove(ENCODING)
    assertThat(movedConfig).containsEntry("ro.build.display.id", "sdk-eng 4.3 JB_MR2 774058 test-keys")
  }

  @Test
  fun renameAvd() {
    // Create an AVD
    val origAvd = avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api23.image)
    assertNotNull("Could not create AVD", origAvd)
    var avdConfigFile = avdFolder.resolve("config.ini")
    assertTrue("Expected config.ini in $avdFolder", Files.exists(avdConfigFile))
    assertFalse(Files.exists(avdFolder.resolve("boot.prop")))
    val properties = AvdManager.parseIniFile(PathFileWrapper(avdConfigFile), null)!!
    assertEquals("system-images/android-23/default/x86/".replace('/', File.separatorChar), properties["image.sysdir.1"])
    assertFalse("Expected NO " + AvdManager.USERDATA_IMG + " in " + avdFolder, Files.exists(avdFolder.resolve(AvdManager.USERDATA_IMG)))
    assertFalse(
      "Expected NO " + AvdManager.USERDATA_QEMU_IMG + " in " + avdFolder,
      Files.exists(avdFolder.resolve(AvdManager.USERDATA_QEMU_IMG)),
    )

    // Create an AVD that is the same, but with a different name
    val newName = name.methodName + "_renamed"
    val renamedAvd =
      avdManager.createAvd(
        avdFolder = avdFolder,
        avdName = newName,
        systemImage = systemImages.api23.image,
        userSettings = properties,
        editExisting = true, /* Yes, edit the existing AVD*/
      )
    assertNotNull("Could not rename AVD", renamedAvd)
    val parentFolder = avdFolder.parent
    val newNameIni = "$newName.ini"
    val newAvdConfigFile = parentFolder.resolve(newNameIni)
    assertTrue("Expected renamed $newNameIni in $parentFolder", Files.exists(newAvdConfigFile))
    val newProperties = AvdManager.parseIniFile(PathFileWrapper(newAvdConfigFile), null)!!
    assertEquals(avdFolder.toString(), newProperties["path"])
    assertFalse(Files.exists(avdFolder.resolve("boot.prop")))
    avdConfigFile = avdFolder.resolve("config.ini")
    val baseProperties = AvdManager.parseIniFile(PathFileWrapper(avdConfigFile), null)!!
    assertEquals("system-images/android-23/default/x86/".replace('/', File.separatorChar), baseProperties["image.sysdir.1"])
    assertFalse("Expected NO " + AvdManager.USERDATA_IMG + " in " + avdFolder, Files.exists(avdFolder.resolve(AvdManager.USERDATA_IMG)))
    assertFalse(
      "Expected NO " + AvdManager.USERDATA_QEMU_IMG + " in " + avdFolder,
      Files.exists(avdFolder.resolve(AvdManager.USERDATA_QEMU_IMG)),
    )
  }

  @Test
  fun editAvdViaBuilder() {
    val log = MockLog()
    val deviceManager = DeviceManager.createInstance(androidSdkHandler, log)
    val device = deviceManager.getDevice("medium_phone", "Generic")!!
    val builder = avdManager.createAvdBuilder(device)
    builder.systemImage = systemImages.api33ext4.image
    val initialAvdInfo = avdManager.createAvd(builder)
    assertThat(initialAvdInfo.name).isEqualTo("Medium_Phone")
    assertThat(initialAvdInfo).isNotNull()
    val initialMetadataPath = builder.metadataIniPath
    assertThat(Files.exists(initialMetadataPath)).isTrue()
    builder.avdName = "My_Phone"
    builder.bootMode = ColdBoot
    val editedAvdInfo = avdManager.editAvd(initialAvdInfo, builder)
    assertThat(editedAvdInfo.name).isEqualTo("My_Phone")
    assertThat(editedAvdInfo.properties).containsEntry(ConfigKey.FORCE_COLD_BOOT_MODE, "yes")
    assertThat(builder.metadataIniPath as Any).isNotEqualTo(initialMetadataPath)
    assertThat(Files.exists(builder.metadataIniPath)).isTrue()
  }

  @Test
  fun editGlassesAvdViaBuilder() {
    val log = MockLog()
    val deviceManager = DeviceManager.createInstance(androidSdkHandler, log)
    val device = deviceManager.getDevice("ai_glasses_device", "Google")!!
    val builder = avdManager.createAvdBuilder(device)
    builder.systemImage = systemImages.api33ext4.image
    val backgroundPath = mockFs.someRoot.resolve("temp").resolve("background1.png").createParentDirectories().createFile()
    builder.environment = backgroundPath
    val initialAvdInfo = avdManager.createAvd(builder)
    assertThat(initialAvdInfo).isNotNull()

    val newBuilder = AvdBuilder.createForExistingDevice(device, initialAvdInfo)
    assertThat(newBuilder.environment?.isAbsolute).isFalse()
    newBuilder.bootMode = ColdBoot

    val editedAvdInfo = avdManager.editAvd(initialAvdInfo, newBuilder)
    assertThat(editedAvdInfo.properties).containsEntry(ConfigKey.FORCE_COLD_BOOT_MODE, "yes")
    assertThat(editedAvdInfo.environment).isEqualTo(initialAvdInfo.environment)
  }

  @Test
  fun duplicateAvd() {
    // Create an AVD
    val origAvdConfig = HashMap<String, String>()
    origAvdConfig["testKey1"] = "originalValue1"
    origAvdConfig["testKey2"] = "originalValue2"
    val origAvd =
      avdManager.createAvd(
        avdFolder = avdFolder,
        avdName = name.methodName,
        systemImage = systemImages.api24PlayStore.image,
        sdcard = InternalSdCard((100 shl 20).toLong()), // SD card size
        hardwareConfig = origAvdConfig,
        userSettings = origAvdConfig,
      )
    assertNotNull("Could not create AVD", origAvd)
    // Put some extra files in the AVD directory
    Files.createFile(avdFolder.resolve("foo.bar"))
    avdFolder
      .resolve("hardware-qemu.ini")
      .recordExistingFile(
        """
            avd.name=${name.methodName}
            hw.sdCard.path=${avdFolder.toAbsolutePath()}/sdcard.img"""
          .trimIndent()
      )

    // Copy this AVDa to an AVD with a different name and a slightly different configuration
    val newAvdConfig = HashMap<String, String>()
    newAvdConfig["testKey2"] = "newValue2"
    val newName = "Copy_of_" + name.methodName
    val duplicatedAvd =
      avdManager.createAvd(
        avdFolder = avdFolder,
        avdName = newName,
        systemImage = systemImages.api24PlayStore.image,
        sdcard = InternalSdCard((222 shl 20).toLong()), // Different SD card size
        hardwareConfig = newAvdConfig,
      ) // Do not remove the original

    // Verify that the duplicated AVD is correct
    assertNotNull("Could not duplicate AVD", duplicatedAvd)
    val parentFolder = avdFolder.parent
    val newFolder = parentFolder.resolve("$newName.avd")
    val newNameIni = "$newName.ini"
    val newIniFile = parentFolder.resolve(newNameIni)
    assertTrue("Expected $newNameIni in $parentFolder", Files.exists(newIniFile))
    val iniProperties = AvdManager.parseIniFile(PathFileWrapper(newIniFile), null)!!
    assertThat(iniProperties["path"]).isEqualTo(newFolder.toString())
    assertTrue(Files.exists(newFolder.resolve("foo.bar")))
    assertFalse(Files.exists(newFolder.resolve("boot.prop")))
    // Check the config.ini file
    val configProperties = AvdManager.parseIniFile(PathFileWrapper(newFolder.resolve("config.ini")), null)!!
    assertThat(configProperties["image.sysdir.1"])
      .isEqualTo("system-images/android-24/google_apis_playstore/x86_64/".replace('/', File.separatorChar))
    assertEquals(newName, configProperties["AvdId"])
    assertEquals(newName, configProperties["avd.ini.displayname"])
    assertEquals("222M", configProperties["sdcard.size"])
    assertEquals("originalValue1", configProperties["testKey1"])
    assertEquals("newValue2", configProperties["testKey2"])
    assertFalse("Expected NO " + AvdManager.USERDATA_IMG + " in " + newFolder, Files.exists(newFolder.resolve(AvdManager.USERDATA_IMG)))
    assertFalse(
      "Expected NO " + AvdManager.USERDATA_QEMU_IMG + " in " + avdFolder,
      Files.exists(avdFolder.resolve(AvdManager.USERDATA_QEMU_IMG)),
    )

    // Check the hardware-qemu.ini file
    val hardwareProperties = AvdManager.parseIniFile(PathFileWrapper(newFolder.resolve("hardware-qemu.ini")), null)!!
    assertEquals(newName, hardwareProperties["avd.name"])
    assertEquals(
      avdFolder.parent.toAbsolutePath().toString() + File.separator + newName + ".avd/sdcard.img",
      hardwareProperties["hw.sdCard.path"],
    )

    // Quick check that the original AVD directory still exists
    assertTrue(Files.exists(avdFolder.resolve("foo.bar")))
    assertTrue(Files.exists(avdFolder.resolve("config.ini")))
    assertTrue(Files.exists(avdFolder.resolve("hardware-qemu.ini")))
    val baseConfigProperties = AvdManager.parseIniFile(PathFileWrapper(avdFolder.resolve("config.ini")), null)!!
    assertThat(baseConfigProperties["AvdId"]).isNotEqualTo(newName) // Different or null
  }

  @Test
  fun duplicateAvdViaBuilder() {
    val log = MockLog()
    val deviceManager = DeviceManager.createInstance(androidSdkHandler, log)
    val device = deviceManager.getDevice("medium_phone", "Generic")!!
    val builder = avdManager.createAvdBuilder(device)
    builder.avdName = name.methodName
    builder.avdFolder = avdFolder
    builder.systemImage = systemImages.api33ext4.image
    builder.sdCard = ExternalSdCard(avdFolder.resolve("custom_sdcard.img").toString())
    builder.frontCamera = AvdCamera.NONE
    builder.backCamera = AvdCamera.NONE
    val initialAvdInfo = avdManager.createAvd(builder)
    assertNotNull("Could not create AVD", initialAvdInfo)
    // Put some extra files in the AVD directory
    Files.createFile(avdFolder.resolve("foo.bar"))
    avdFolder
      .resolve("hardware-qemu.ini")
      .recordExistingFile(
        """
          avd.name=${name.methodName}
          hw.sdCard.path=${avdFolder.resolve("sdcard.img")}"""
          .trimIndent()
      )

    // Copy this AVD to an AVD with a different name and a slightly different configuration
    val newBuilder = AvdBuilder.createForExistingDevice(device, initialAvdInfo)
    newBuilder.backCamera = AvdCamera.WEBCAM
    newBuilder.displayName = "Copy of ${initialAvdInfo.displayName}"
    newBuilder.avdName = "Copy_of_${name.methodName}"
    newBuilder.avdFolder = initialAvdInfo.dataFolderPath.resolveSibling("Copy_of_${name.methodName}.avd")
    val duplicatedAvd = avdManager.duplicateAvd(initialAvdInfo, newBuilder)

    // Verify that the duplicated AVD is correct
    assertNotNull("Could not duplicate AVD", duplicatedAvd)
    val parentFolder = avdFolder.parent
    val newFolder = newBuilder.avdFolder
    val newName = newBuilder.avdName
    val newNameIni = "$newName.ini"
    val newIniFile = parentFolder.resolve(newNameIni)
    assertTrue("Expected $newNameIni in $parentFolder", Files.exists(newIniFile))
    val iniProperties = AvdManager.parseIniFile(PathFileWrapper(newIniFile), null)!!
    assertEquals(newFolder.toString(), iniProperties["path"])
    assertTrue(Files.exists(newFolder.resolve("foo.bar")))
    assertFalse(Files.exists(newFolder.resolve("boot.prop")))
    // Check the config.ini file
    val configProperties = AvdManager.parseIniFile(PathFileWrapper(newFolder.resolve("config.ini")), null)!!
    assertEquals(
      "system-images/android-33-ext4/google_apis_playstore/x86_64/".replace('/', File.separatorChar),
      configProperties["image.sysdir.1"],
    )
    assertEquals(newName, configProperties["AvdId"])
    assertEquals(newBuilder.displayName, configProperties["avd.ini.displayname"])
    assertThat(configProperties[ConfigKey.SDCARD_PATH]).isEqualTo(newFolder.resolve("custom_sdcard.img").toString())
    assertEquals(AvdCamera.NONE.asParameter, configProperties[ConfigKey.CAMERA_FRONT])
    assertEquals(AvdCamera.WEBCAM.asParameter, configProperties[ConfigKey.CAMERA_BACK])
    assertFalse("Expected NO ${AvdManager.USERDATA_IMG} in $newFolder", Files.exists(newFolder.resolve(AvdManager.USERDATA_IMG)))
    assertFalse("Expected NO ${AvdManager.USERDATA_QEMU_IMG} in $avdFolder", Files.exists(avdFolder.resolve(AvdManager.USERDATA_QEMU_IMG)))

    // Check the hardware-qemu.ini file
    val hardwareProperties = AvdManager.parseIniFile(PathFileWrapper(newFolder.resolve("hardware-qemu.ini")), null)!!
    assertThat(hardwareProperties["avd.name"]).isEqualTo(newName)
    assertThat(hardwareProperties["hw.sdCard.path"])
      .isEqualTo(avdFolder.parent.toAbsolutePath().resolve("$newName.avd").resolve("sdcard.img").toString())

    // Quick check that the original AVD directory still exists
    assertTrue(Files.exists(avdFolder.resolve("foo.bar")))
    assertTrue(Files.exists(avdFolder.resolve("config.ini")))
    assertTrue(Files.exists(avdFolder.resolve("hardware-qemu.ini")))
    val baseConfigProperties = AvdManager.parseIniFile(PathFileWrapper(avdFolder.resolve("config.ini")), null)!!
    assertThat(baseConfigProperties["AvdId"]).isNotEqualTo(newName) // Different or null
  }

  @Test
  fun reloadAvds() {
    // Create an AVD.
    var avd = avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api23.image)
    assertNotNull("Could not create AVD", avd)
    assertEquals(AvdInfo.AvdStatus.OK, avd.status)

    // Delete the system image of the AVD.
    PathUtils.deleteRecursivelyIfExists(systemImages.api23.image.location)
    avdManager.reloadAvds()
    avd = avdManager.getAvd(avd.name, false)!!
    assertNotNull(avd)
    assertEquals(AvdInfo.AvdStatus.ERROR_IMAGE_MISSING, avd.status)
  }

  @Test
  fun playStoreProperty() {
    val expected: MutableMap<String, String> = TreeMap()
    expected["ro.build.display.id"] = "sdk-eng 4.3 JB_MR2 774058 test-keys"
    expected["ro.board.platform"] = ""
    expected["ro.build.tags"] = "test-keys"

    // Play Store image with Play Store device
    avdManager.createAvd(
      avdFolder = avdFolder,
      avdName = name.methodName,
      systemImage = systemImages.api24PlayStore.image,
      userSettings = expected,
      bootProps = expected,
      deviceHasPlayStore = true, // deviceHasPlayStore
    )
    val configIniFile = avdFolder.resolve("config.ini")
    var baseProperties = AvdManager.parseIniFile(PathFileWrapper(configIniFile), null)!!
    assertEquals("true", baseProperties["PlayStore.enabled"])

    // Play Store image with non-Play Store device
    avdManager.createAvd(
      avdFolder = avdFolder,
      avdName = name.methodName,
      systemImage = systemImages.api24PlayStore.image,
      bootProps = expected,
      removePrevious = true,
    )
    baseProperties = AvdManager.parseIniFile(PathFileWrapper(configIniFile), null)!!
    assertEquals("false", baseProperties["PlayStore.enabled"])

    // Non-Play Store image with Play Store device
    avdManager.createAvd(
      avdFolder = avdFolder,
      avdName = name.methodName,
      systemImage = systemImages.api23GoogleApis.image,
      bootProps = expected,
      deviceHasPlayStore = true, // deviceHasPlayStore
      removePrevious = true,
    )
    baseProperties = AvdManager.parseIniFile(PathFileWrapper(configIniFile), null)!!
    assertEquals("false", baseProperties["PlayStore.enabled"])

    // Wear image API 24 (no Play Store)
    avdManager.createAvd(
      avdFolder = avdFolder,
      avdName = name.methodName,
      systemImage = systemImages.api24Wear.image,
      bootProps = expected,
      deviceHasPlayStore = true, // deviceHasPlayStore
      removePrevious = true,
    )
    baseProperties = AvdManager.parseIniFile(PathFileWrapper(configIniFile), null)!!
    assertEquals("false", baseProperties["PlayStore.enabled"])

    // Wear image API 25 (with Play Store)
    // (All Wear devices have Play Store)
    avdManager.createAvd(
      avdFolder = avdFolder,
      avdName = name.methodName,
      systemImage = systemImages.api25Wear.image,
      bootProps = expected,
      deviceHasPlayStore = true, // deviceHasPlayStore
      removePrevious = true,
    )
    baseProperties = AvdManager.parseIniFile(PathFileWrapper(configIniFile), null)!!
    assertEquals("true", baseProperties["PlayStore.enabled"])

    // Wear image for China (no Play Store)
    avdManager.createAvd(
      avdFolder = avdFolder,
      avdName = name.methodName,
      systemImage = systemImages.api25WearChina.image,
      bootProps = expected,
      deviceHasPlayStore = true, // deviceHasPlayStore
      removePrevious = true,
    )
    baseProperties = AvdManager.parseIniFile(PathFileWrapper(configIniFile), null)!!
    assertEquals("false", baseProperties["PlayStore.enabled"])
  }

  @Test
  fun updateDeviceChanged() {
    val log = MockLog()
    val devMan = DeviceManager.createInstance(androidSdkHandler, log)
    val myDevice = devMan.getDevice("7.6in Foldable", "Generic")!!
    val baseHardwareProperties = DeviceManager.getHardwareProperties(myDevice)

    // Modify hardware properties that should change
    baseHardwareProperties["hw.lcd.height"] = "960"
    baseHardwareProperties["hw.displayRegion.0.1.height"] = "480"
    // Modify a hardware property that should NOT change
    baseHardwareProperties["hw.ramSize"] = "1536"
    // Add a user-settable property
    baseHardwareProperties["hw.keyboard"] = "yes"

    // Create a virtual device including these properties
    val myDeviceInfo =
      avdManager.createAvd(
        avdFolder = avdFolder,
        avdName = name.methodName,
        systemImage = systemImages.api23GoogleApis.image,
        hardwareConfig = baseHardwareProperties,
        userSettings = baseHardwareProperties,
        deviceHasPlayStore = true,
        removePrevious = true,
      )

    // Verify all the parameters that we changed and the parameter that we added
    val firstHardwareProperties = myDeviceInfo.properties
    assertEquals("960", firstHardwareProperties["hw.lcd.height"])
    assertEquals("480", firstHardwareProperties["hw.displayRegion.0.1.height"])
    assertEquals("1536", firstHardwareProperties["hw.ramSize"])
    assertEquals("yes", firstHardwareProperties["hw.keyboard"])

    // Update the device using the original hardware definition
    val updatedDeviceInfo = avdManager.updateDeviceChanged(myDeviceInfo)!!

    // Verify that the two fixed hardware properties changed back, but the other hardware
    // property and the user-settable property did not change.
    val updatedHardwareProperties = updatedDeviceInfo.properties
    assertEquals("2208", updatedHardwareProperties["hw.lcd.height"])
    assertEquals("2208", updatedHardwareProperties["hw.displayRegion.0.1.height"])
    assertEquals("1536", updatedHardwareProperties["hw.ramSize"])
    assertEquals("yes", updatedHardwareProperties["hw.keyboard"])
  }

  @Test
  fun parseAvdInfo() {
    avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api23.image)

    // Check a valid AVD .ini file
    val parentFolder = avdFolder.parent
    val avdIniName = name.methodName + ".ini"
    val avdIniFile = parentFolder.resolve(avdIniName).toAbsolutePath()
    assertTrue("Expected AVD .ini in $parentFolder", Files.exists(avdIniFile))
    val avdInfo = avdManager.parseAvdInfo(avdIniFile)
    assertThat(avdInfo.status).isEqualTo(AvdInfo.AvdStatus.OK)
    assertThat(avdInfo.dataFolderPath).isEqualTo(avdFolder)
    assertThat(avdInfo.androidVersion).isEqualTo(AndroidVersion(23))

    // Check a bad AVD .ini file.
    // Append garbage to make the file invalid.
    Files.newOutputStream(avdIniFile, StandardOpenOption.APPEND).use { corruptedStream ->
      BufferedWriter(OutputStreamWriter(corruptedStream)).use { corruptedWriter -> corruptedWriter.write("[invalid syntax]\n") }
    }
    val corruptedInfo = avdManager.parseAvdInfo(avdIniFile)
    assertThat(corruptedInfo.status).isEqualTo(AvdInfo.AvdStatus.ERROR_CORRUPTED_INI)

    // Check a non-existent AVD .ini file
    val noSuchIniName = "noSuch.ini"
    val noSuchIniFile = parentFolder.resolve(noSuchIniName)
    assertFalse("Found unexpected noSuch.ini in $parentFolder", Files.exists(noSuchIniFile))
    val noSuchInfo = avdManager.parseAvdInfo(noSuchIniFile)
    assertThat(noSuchInfo.status).isEqualTo(AvdInfo.AvdStatus.ERROR_CORRUPTED_INI)

    // Check an empty AVD .ini file
    val emptyIniFile = parentFolder.resolve("empty.ini")
    assertNotNull("Empty .ini file already exists in $parentFolder", Files.createFile(emptyIniFile))
    assertTrue("Expected empty AVD .ini in $parentFolder", Files.exists(emptyIniFile))
    assertThat(Files.size(emptyIniFile)).isEqualTo(0)
    val emptyInfo = avdManager.parseAvdInfo(emptyIniFile)
    assertThat(emptyInfo.status).isEqualTo(AvdInfo.AvdStatus.ERROR_CORRUPTED_INI)
  }

  @Test
  fun parseAvdInfoWithExtensionLevel() {
    val image: SystemImage = systemImages.api33ext4.image
    avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = image)

    // Check a valid AVD .ini file
    val parentFolder = avdFolder.parent
    val avdIniName = "${name.methodName}.ini"
    val avdIniFile = parentFolder.resolve(avdIniName).toAbsolutePath()
    assertTrue("Expected AVD .ini in $parentFolder", Files.exists(avdIniFile))
    val avdInfo = avdManager.parseAvdInfo(avdIniFile)
    assertThat(avdInfo.status).isEqualTo(AvdInfo.AvdStatus.OK)
    assertThat(avdInfo.dataFolderPath).isEqualTo(avdFolder)

    // check that the AndroidVersion survives the round trip
    assertThat(avdInfo.androidVersion).isEqualTo(image.androidVersion)
  }

  @Test
  fun parseAvdInfoWithoutDisplayName() {
    avdManager.createAvd(avdFolder = avdFolder, avdName = name.methodName, systemImage = systemImages.api23.image)

    // Remove the display name property from the .ini file
    val parentFolder = avdFolder.parent
    val avdIniName = "${name.methodName}.ini"
    val avdIniFile = parentFolder.resolve(avdIniName).toAbsolutePath()
    removeKeyFromIniFile(avdIniFile, ConfigKey.DISPLAY_NAME)
    val expectedDisplayName = name.methodName
    val avdInfo = avdManager.parseAvdInfo(avdIniFile)
    assertEquals(expectedDisplayName, avdInfo.displayName)
    assertEquals(expectedDisplayName, avdInfo.getProperty(ConfigKey.DISPLAY_NAME))
  }

  private fun removeKeyFromIniFile(path: Path, key: String) {
    val lines = mutableListOf<String>()
    Files.newBufferedReader(path).use { reader ->
      reader.forEachLine { line ->
        if (!line.contains(key)) {
          lines.add(line)
        }
      }
    }
    Files.newBufferedWriter(path, StandardOpenOption.WRITE).use { writer ->
      for (line in lines) {
        writer.write(line)
        writer.newLine()
      }
    }
  }
}
