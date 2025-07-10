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

package com.android.tools.journeys.testengine.robo

import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.*
import org.mockito.kotlin.argumentCaptor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class AdbTest {

    private lateinit var mockExecutor: ProcessExecutor
    private lateinit var adb: Adb

    @Before
    fun setUp() {
        mockExecutor = mock(ProcessExecutor::class.java)
        adb = Adb("path/to/adb", "device-123", mockExecutor)
    }

    @Test
    fun `install success`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "Success", "")
        )

        adb.install("test.apk")

        verify(mockExecutor).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "install", "test.apk"),
            300
        )
    }

    @Test
    fun `install success with flags`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "Success", "")
        )

        adb.install("test.apk", listOf("-r", "-d"))

        verify(mockExecutor).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "install", "-r", "-d", "test.apk"),
            300
        )
    }

    @Test
    fun `install with special characters in path`() {
        val pathWithSpecialChars = "/path with spaces/and'quotes'/andSpecial$#@* chars/test.apk"
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "Success", "")
        )

        adb.install(pathWithSpecialChars)

        val installCaptor = argumentCaptor<List<String>>()
        verify(mockExecutor).execCmdSync(installCaptor.capture(), anyLong())
        val expectedCommand = listOf("path/to/adb", "-s", "device-123", "install", pathWithSpecialChars)
        assertEquals(expectedCommand, installCaptor.firstValue)
    }

    @Test
    fun `install failure due to non-zero exit code`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(1, "", "INSTALL_FAILED")
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            adb.install("test.apk")
        }
        assertEquals(
            "Command `path/to/adb -s device-123 install test.apk` failed (exit code 1) with output:\n------ stderr ------\nINSTALL_FAILED",
            exception.message
        )
    }

    @Test
    fun `install failure due to missing 'Success' message`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "Something went wrong", "")
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            adb.install("test.apk")
        }
        assertEquals(
            "Command `path/to/adb -s device-123 install test.apk` failed (exit code 0) with output:\n------ stdout ------\nSomething went wrong",
            exception.message
        )
    }

    @Test
    fun `uninstall app is installed`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "package:/data/app/com.example-1.apk", "")
        )

        adb.uninstall("com.example")

        verify(mockExecutor).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "shell", "pm", "path", "com.example"),
            30
        )
        verify(mockExecutor).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "uninstall", "com.example"),
            30
        )
    }

    @Test
    fun `uninstall app is not installed`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "", "")
        )

        adb.uninstall("com.example")

        verify(mockExecutor).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "shell", "pm", "path", "com.example"),
            30
        )
        verify(mockExecutor, never()).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "uninstall", "com.example"),
            30
        )
    }

    @Test
    fun `uninstall failure due to non-zero exit code`() {
        `when`(
            mockExecutor.execCmdSync(
                listOf("path/to/adb", "-s", "device-123", "shell", "pm", "path", "com.example"),
                30
            )
        ).thenReturn(
            ProcessResult(0, "package:/data/app/com.example-1.apk", "")
        )
        `when`(
            mockExecutor.execCmdSync(
                listOf("path/to/adb", "-s", "device-123", "uninstall", "com.example"),
                30
            )
        ).thenReturn(
            ProcessResult(1, "", "DELETE_FAILED_INTERNAL_ERROR")
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            adb.uninstall("com.example")
        }
        assertEquals(
            "Command `path/to/adb -s device-123 uninstall com.example` failed (exit code 1) with output:\n------ stderr ------\nDELETE_FAILED_INTERNAL_ERROR",
            exception.message
        )
    }

    @Test
    fun `getDeviceApiLevel success`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "30", "")
        )

        assertEquals(30, adb.getDeviceApiLevel())
    }


    @Test
    fun `getDeviceApiLevel failure non-integer output`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "thirty", "")
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            adb.getDeviceApiLevel()
        }
        assertEquals(
            "Command `path/to/adb -s device-123 shell getprop ro.build.version.sdk` failed (exit code 0) with output:\n------ stdout ------\nthirty",
            exception.message
        )
    }

    @Test
    fun `runInstrumentation success with args`() {
        val mockProcess = mock(Process::class.java)
        `when`(mockExecutor.execCmdAsync(anyList())).thenReturn(mockProcess)

        val process = adb.runInstrumentation(
            "com.example.test",
            "androidx.test.runner.AndroidJUnitRunner",
            mapOf("class" to "com.example.test.MyTestClass", "arg2" to "value1$# 'quote' ")
        )

        assertNotNull(process)
        verify(mockExecutor).execCmdAsync(
            listOf(
                "path/to/adb",
                "-s",
                "device-123",
                "shell",
                "am",
                "instrument",
                "-w",
                "-e",
                "class",
                "com.example.test.MyTestClass",
                "-e",
                "arg2",
                "value1$# 'quote' ",
                "com.example.test/androidx.test.runner.AndroidJUnitRunner"
            )
        )
    }

    @Test
    fun `runInstrumentation success without args`() {
        val mockProcess = mock(Process::class.java)
        `when`(mockExecutor.execCmdAsync(anyList())).thenReturn(mockProcess)

        val process = adb.runInstrumentation(
            "com.example.test",
            "androidx.test.runner.AndroidJUnitRunner"
        )

        assertNotNull(process)
        verify(mockExecutor).execCmdAsync(
            listOf(
                "path/to/adb",
                "-s",
                "device-123",
                "shell",
                "am",
                "instrument",
                "-w",
                "com.example.test/androidx.test.runner.AndroidJUnitRunner"
            )
        )
    }

    @Test
    fun `runInstrumentation failure`() {
        `when`(mockExecutor.execCmdAsync(anyList())).thenThrow(IllegalStateException("Command not found"))

        val exception = assertThrows(IllegalStateException::class.java) {
            adb.runInstrumentation(
                "com.example.test",
                "androidx.test.runner.AndroidJUnitRunner"
            )
        }
        assertEquals("Command not found", exception.message)
    }

    @Test
    fun `forward success`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "8080", "")
        )

        adb.forward(8080, 5050)

        verify(mockExecutor).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "forward", "tcp:8080", "tcp:5050"),
            30
        )
    }

    @Test
    fun `forward failure`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(1, "error", "")
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            adb.forward(8080, 5050)
        }
        assertEquals(
            "Command `path/to/adb -s device-123 forward tcp:8080 tcp:5050` failed (exit code 1) with output:\n------ stdout ------\nerror",
            exception.message
        )
    }

    @Test
    fun `removeForward success when rule exists`() {
        `when`(
            mockExecutor.execCmdSync(
                listOf("path/to/adb", "-s", "device-123", "forward", "--list"),
                30
            )
        ).thenReturn(ProcessResult(0, "device-123 tcp:8080 tcp:5050", ""))
        `when`(
            mockExecutor.execCmdSync(
                listOf("path/to/adb", "-s", "device-123", "forward", "--remove", "tcp:8080"),
                30
            )
        ).thenReturn(ProcessResult(0, "", ""))

        adb.removeForward(8080)

        verify(mockExecutor).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "forward", "--list"),
            30
        )
        verify(mockExecutor).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "forward", "--remove", "tcp:8080"),
            30
        )
    }

    @Test
    fun `removeForward success when rule does not exist`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "", "")
        )

        adb.removeForward(8080)

        verify(mockExecutor).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "forward", "--list"),
            30
        )
        verify(mockExecutor, never()).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "forward", "--remove", "tcp:8080"),
            30
        )
    }

    @Test
    fun `removeForward failure due to non-zero exit code`() {
        `when`(
            mockExecutor.execCmdSync(
                listOf("path/to/adb", "-s", "device-123", "forward", "--list"),
                30
            )
        ).thenReturn(ProcessResult(0, "device-123 tcp:8080 tcp:5050", ""))
        `when`(
            mockExecutor.execCmdSync(
                listOf("path/to/adb", "-s", "device-123", "forward", "--remove", "tcp:8080"),
                30
            )
        ).thenReturn(ProcessResult(1, "", "error: something went wrong"))

        val exception = assertThrows(IllegalStateException::class.java) {
            adb.removeForward(8080)
        }
        verify(mockExecutor).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "forward", "--list"),
            30
        )
        assertEquals(
            "Command `path/to/adb -s device-123 forward --remove tcp:8080` failed (exit code 1) with output:\n------ stderr ------\nerror: something went wrong",
            exception.message
        )
    }

    @Test
    fun `dumpsys success`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "DUMP OF SERVICE...", "")
        )

        assertEquals("DUMP OF SERVICE...\n", adb.dumpsys("activity"))
    }

    @Test
    fun `dumpsys failure`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(1, "", "Service not found")
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            adb.dumpsys("nonexistent.service")
        }
        assertEquals(
            "Command `path/to/adb -s device-123 shell dumpsys activity service nonexistent.service` failed (exit code 1) with output:\n------ stderr ------\nService not found",
            exception.message
        )
    }

    @Test
    fun `setGlobalSettingsValue success`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(0, "", "")
        )

        adb.setGlobalSettingsValue("my_key", "my_value")

        verify(mockExecutor).execCmdSync(
            listOf("path/to/adb", "-s", "device-123", "shell", "settings", "put", "global", "my_key", "my_value"),
            10
        )
    }

    @Test
    fun `setGlobalSettingsValue failure`() {
        `when`(mockExecutor.execCmdSync(anyList(), anyLong())).thenReturn(
            ProcessResult(1, "", "Invalid command")
        )

        val exception = assertThrows(IllegalStateException::class.java) {
            adb.setGlobalSettingsValue("invalid_key", "invalid_value")
        }
        assertEquals(
            "Command `path/to/adb -s device-123 shell settings put global invalid_key invalid_value` failed (exit code 1) with output:\n------ stderr ------\nInvalid command",
            exception.message
        )
    }
}
