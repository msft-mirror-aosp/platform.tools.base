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
package com.android.adblib.utils

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.assertFailsWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AdbServerStartupUtilsTest {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test
  fun findAdbOnPath_findsFirstMatchingExecutableAcrossDirectories() {
    // Prepare
    val emptyDir = tempFolder.newFolder("empty").toPath()
    val firstBinDir = tempFolder.newFolder("bin1").toPath()
    val secondBinDir = tempFolder.newFolder("bin2").toPath()
    val adb1 = Files.createFile(firstBinDir.resolve("adb"))
    Files.createFile(secondBinDir.resolve("adb"))
    val pathEnv = listOf(emptyDir, firstBinDir, secondBinDir).joinToString(File.pathSeparator)

    // Act
    val result = AdbServerStartupUtils.findAdbOnPath(osName = "Linux", pathEnv = pathEnv)

    // Assert
    assertEquals(adb1.toAbsolutePath(), result)
  }

  @Test
  fun findAdbOnPath_usesAdbExeOnWindows() {
    // Prepare
    val binDir = tempFolder.newFolder("bin").toPath()
    Files.createFile(binDir.resolve("adb"))
    val adbExe = Files.createFile(binDir.resolve("adb.exe"))

    // Act
    val result = AdbServerStartupUtils.findAdbOnPath(osName = "Windows 11", pathEnv = binDir.toString())

    // Assert
    assertEquals(adbExe.toAbsolutePath(), result)
  }

  @Test
  fun findAdbOnPath_returnsAbsolutePathForRelativePathEntry() {
    // Prepare
    val binDir = tempFolder.newFolder("bin").toPath().toAbsolutePath()
    val adbFile = Files.createFile(binDir.resolve("adb"))
    val workingDir = Paths.get("").toAbsolutePath()
    // `relativize` requires both paths to have the same root (e.g. the same drive on Windows)
    assumeTrue(workingDir.root == binDir.root)
    val relativeBinDir = workingDir.relativize(binDir)

    // Act
    val result = AdbServerStartupUtils.findAdbOnPath(osName = "Linux", pathEnv = relativeBinDir.toString())

    // Assert
    assertTrue(result.isAbsolute)
    assertTrue(Files.isSameFile(adbFile, result))
  }

  @Test
  fun findAdbOnPath_throwsWhenNotFound() {
    // Prepare
    val pathEnv = tempFolder.newFolder("empty").toPath().toString()

    // Act
    val exception = assertFailsWith<IOException> { AdbServerStartupUtils.findAdbOnPath(osName = "Linux", pathEnv = pathEnv) }
    val windowsException = assertFailsWith<IOException> { AdbServerStartupUtils.findAdbOnPath(osName = "Windows 11", pathEnv = pathEnv) }

    // Assert
    assertEquals("Couldn't locate 'adb' on PATH", exception.message)
    assertEquals("Couldn't locate 'adb.exe' on PATH", windowsException.message)
  }

  @Test
  fun findAdbOnPath_throwsWhenPathNotDefined() {
    // Act
    val exception = assertFailsWith<IOException> { AdbServerStartupUtils.findAdbOnPath(osName = "Linux", pathEnv = null) }

    // Assert
    assertEquals("No PATH environment variable is defined", exception.message)
  }
}
