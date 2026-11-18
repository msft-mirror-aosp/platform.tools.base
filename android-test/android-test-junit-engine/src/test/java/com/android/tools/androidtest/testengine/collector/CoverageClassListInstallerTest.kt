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

package com.android.tools.androidtest.testengine.collector

import com.android.tools.androidtest.testengine.adb.AdbController
import com.android.tools.androidtest.testengine.adb.AdbController.CommandResult
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.IOException
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mock
import org.mockito.Mockito.lenient
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/** Unit tests for [CoverageClassListInstaller]. */
class CoverageClassListInstallerTest {

  @get:Rule val mockitoRule: MockitoRule = MockitoJUnit.rule()

  @get:Rule val temporaryFolder = TemporaryFolder()

  @Mock private lateinit var adbController: AdbController

  private val deviceSerial = "device-1234"
  private val packageName = "com.example.app"
  private val dataDir = "/data/user/0/com.example.app/code_cache"
  private val listPath = "$dataDir/coverage_classes.txt"
  private val partialPath = "$listPath.tmp"
  private lateinit var classList: File
  private lateinit var installer: CoverageClassListInstaller

  @Before
  fun setUp() {
    classList = temporaryFolder.newFile("coverage_classes.txt")
    classList.writeText("#coverage-classes v1 count=1\ncom/example/Foo\n#end\n")
    installer = CoverageClassListInstaller(adbController, deviceSerial)
    // Not every test runs every command.
    lenient().`when`(adbController.push(eq(deviceSerial), any(), any(), anyOrNull())).thenReturn(CommandResult(0, "", ""))
    lenient().`when`(adbController.runAdbShellCommand(eq(deviceSerial), any(), anyOrNull())).thenReturn(CommandResult(0, "", ""))
    lenient()
      .`when`(adbController.runAdbShellCommand(eq(deviceSerial), eq(statCommand()), anyOrNull()))
      .thenReturn(CommandResult(0, "${classList.length()}\n", ""))
  }

  @Test
  fun `install pushes the list, copies it into place and removes the temporary file`() {
    assertThat(installer.install(classList, packageName, dataDir)).isTrue()

    val tempPath = pushedPath()
    assertThat(tempPath).startsWith("/data/local/tmp/coverage_classes_com.example.app_")
    val inOrder = inOrder(adbController)
    inOrder.verify(adbController).push(eq(deviceSerial), eq(classList.absolutePath), eq(tempPath), anyOrNull())
    inOrder.verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("chmod", "644", tempPath)), anyOrNull())
    inOrder
      .verify(adbController)
      .runAdbShellCommand(
        eq(deviceSerial),
        eq(
          listOf(
            "run-as",
            packageName,
            "sh",
            "-c",
            "\"rm -f '$listPath' '$partialPath'; cp '$tempPath' '$partialPath' && mv -f '$partialPath' '$listPath'\"",
          )
        ),
        anyOrNull(),
      )
    inOrder.verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(statCommand()), anyOrNull())
    inOrder.verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("rm", "-f", tempPath)), anyOrNull())
    verify(adbController, never()).runAdbShellCommand(eq(deviceSerial), eq(removeCommand()), anyOrNull())
  }

  @Test
  fun `install removes the list when the push fails`() {
    whenever(adbController.push(eq(deviceSerial), any(), any(), anyOrNull())).thenReturn(CommandResult(1, "", "no space"))

    assertThat(installer.install(classList, packageName, dataDir)).isFalse()

    val tempPath = pushedPath()
    verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(removeCommand()), anyOrNull())
    verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("rm", "-f", tempPath)), anyOrNull())
    verify(adbController, never()).runAdbShellCommand(eq(deviceSerial), eq(statCommand()), anyOrNull())
  }

  @Test
  fun `install removes the list when the copy fails`() {
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), argThat { getOrNull(2) == "sh" }, anyOrNull()))
      .thenReturn(CommandResult(1, "", "run-as: package not debuggable"))

    assertThat(installer.install(classList, packageName, dataDir)).isFalse()

    val tempPath = pushedPath()
    verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(removeCommand()), anyOrNull())
    verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("rm", "-f", tempPath)), anyOrNull())
  }

  @Test
  fun `install removes the list when its size differs`() {
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), eq(statCommand()), anyOrNull())).thenReturn(CommandResult(0, "12\n", ""))

    assertThat(installer.install(classList, packageName, dataDir)).isFalse()

    verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(removeCommand()), anyOrNull())
  }

  @Test
  fun `install removes the list when adb can't run`() {
    whenever(adbController.push(eq(deviceSerial), any(), any(), anyOrNull())).thenAnswer { throw IOException("Cannot run program adb") }
    whenever(adbController.runAdbShellCommand(eq(deviceSerial), any(), anyOrNull())).thenAnswer {
      throw IOException("Cannot run program adb")
    }

    assertThat(installer.install(classList, packageName, dataDir)).isFalse()

    val tempPath = pushedPath()
    verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(removeCommand()), anyOrNull())
    verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(listOf("rm", "-f", tempPath)), anyOrNull())
  }

  @Test
  fun `install without a list removes any list on the device`() {
    assertThat(installer.install(null, packageName, dataDir)).isFalse()

    verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(removeCommand()), anyOrNull())
    verify(adbController, never()).push(any(), any(), any(), anyOrNull())
  }

  @Test
  fun `install with a missing file removes any list on the device`() {
    assertThat(installer.install(File(temporaryFolder.root, "missing.txt"), packageName, dataDir)).isFalse()

    verify(adbController).runAdbShellCommand(eq(deviceSerial), eq(removeCommand()), anyOrNull())
    verify(adbController, never()).push(any(), any(), any(), anyOrNull())
  }

  private fun statCommand() = listOf("run-as", packageName, "stat", "-c", "%s", listPath)

  private fun removeCommand() = listOf("run-as", packageName, "rm", "-f", listPath, partialPath)

  /** Returns the device path that the list was pushed to. */
  private fun pushedPath(): String {
    var path: String? = null
    verify(adbController)
      .push(
        eq(deviceSerial),
        eq(classList.absolutePath),
        argThat {
          path = this
          true
        },
        anyOrNull(),
      )
    return checkNotNull(path)
  }
}
