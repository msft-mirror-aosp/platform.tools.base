/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.sdklib.deviceprovisioner.testing

import com.android.sdklib.devices.DeviceManager
import com.android.sdklib.internal.avd.AvdManager
import com.android.sdklib.repository.AndroidSdkHandler
import com.android.sdklib.testing.TestSystemImages
import com.android.testutils.file.createInMemoryFileSystem
import com.android.testutils.file.someRoot
import com.android.utils.StdLogger
import java.nio.file.Files
import java.nio.file.Path

/**
 * A fixture that sets up AvdManager, DeviceManager, and AndroidSdkHandler on an in-memory filesystem.
 *
 * The DeviceManager contains the usual built-in set of devices from the resource files. System images are faked by TestSystemImages, which
 * writes package.xml and other relevant files to disk.
 */
class SdkFixture {
  val fileSystem = createInMemoryFileSystem()
  val sdkRoot: Path = Files.createDirectories(fileSystem.someRoot.resolve("sdk"))
  val avdRoot: Path = sdkRoot.root.resolve("avd")
  val sdkHandler = AndroidSdkHandler(sdkRoot, avdRoot)
  private val logger = StdLogger(StdLogger.Level.INFO)
  val deviceManager = DeviceManager.createInstance(sdkHandler, logger)
  val avdManager = AvdManager.createInstance(sdkHandler, avdRoot, deviceManager, logger)
  val testSystemImages = TestSystemImages(sdkHandler)
}
