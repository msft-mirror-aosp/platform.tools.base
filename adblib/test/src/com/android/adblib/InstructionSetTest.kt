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
package com.android.adblib

import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstructionSetTest {

    @Test
    fun test_arm_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val instructionSet = InstructionSet.Arm

        // Assert
        assertTrue(instructionSet.is32Bit)
        assertFalse(instructionSet.is64Bit)
        assertTrue(instructionSet.isArm)
        assertFalse(instructionSet.isX86)
        assertFalse(instructionSet.isRiscV)
        assertFalse(instructionSet.isUnknown)
        assertEquals("arm", instructionSet.text)
    }

    @Test
    fun test_arm64_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val instructionSet = InstructionSet.Arm64

        // Assert
        assertFalse(instructionSet.is32Bit)
        assertTrue(instructionSet.is64Bit)
        assertTrue(instructionSet.isArm)
        assertFalse(instructionSet.isX86)
        assertFalse(instructionSet.isRiscV)
        assertFalse(instructionSet.isUnknown)
        assertEquals("arm64", instructionSet.text)
    }

    @Test
    fun test_x86_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val instructionSet = InstructionSet.X86

        // Assert
        assertTrue(instructionSet.is32Bit)
        assertFalse(instructionSet.is64Bit)
        assertFalse(instructionSet.isArm)
        assertTrue(instructionSet.isX86)
        assertFalse(instructionSet.isRiscV)
        assertFalse(instructionSet.isUnknown)
        assertEquals("x86", instructionSet.text)
    }

    @Test
    fun test_x86_64_Works(): Unit = runBlockingWithTimeout {
        // Prepare
        val instructionSet = InstructionSet.X86_64

        // Assert
        assertFalse(instructionSet.is32Bit)
        assertTrue(instructionSet.is64Bit)
        assertFalse(instructionSet.isArm)
        assertTrue(instructionSet.isX86)
        assertFalse(instructionSet.isRiscV)
        assertFalse(instructionSet.isUnknown)
        assertEquals("x86_64", instructionSet.text)
    }

    @Test
    fun test_riscv64_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val instructionSet = InstructionSet.Riscv64

        // Assert
        assertFalse(instructionSet.is32Bit)
        assertTrue(instructionSet.is64Bit)
        assertFalse(instructionSet.isArm)
        assertFalse(instructionSet.isX86)
        assertTrue(instructionSet.isRiscV)
        assertFalse(instructionSet.isUnknown)
        assertEquals("riscv64", instructionSet.text)
    }

    @Test
    fun test_unknown_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val instructionSet = InstructionSet.Unknown("foobar")

        // Assert
        assertTrue(instructionSet.is32Bit)
        assertFalse(instructionSet.is64Bit)
        assertFalse(instructionSet.isArm)
        assertFalse(instructionSet.isX86)
        assertFalse(instructionSet.isRiscV)
        assertTrue(instructionSet.isUnknown)
        assertEquals("foobar", instructionSet.text)
    }

    @Test
    fun test_unknown64_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val instructionSet = InstructionSet.Unknown("foobar64")

        // Assert
        assertFalse(instructionSet.is32Bit)
        assertTrue(instructionSet.is64Bit)
        assertFalse(instructionSet.isArm)
        assertFalse(instructionSet.isX86)
        assertFalse(instructionSet.isRiscV)
        assertTrue(instructionSet.isUnknown)
        assertEquals("foobar64", instructionSet.text)
    }

    @Test
    fun test_fromString_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val arm = InstructionSet.fromString("arm")
        val arm64 = InstructionSet.fromString("arm64")
        val x86 = InstructionSet.fromString("x86")
        val x86_64 = InstructionSet.fromString("x86_64")
        val riscv64 = InstructionSet.fromString("riscv64")
        val unknown = InstructionSet.fromString("foobar")
        val unknown64 = InstructionSet.fromString("foobar64")

        // Assert
        assertEquals(InstructionSet.Arm, arm)
        assertEquals(InstructionSet.Arm64, arm64)
        assertEquals(InstructionSet.X86, x86)
        assertEquals(InstructionSet.X86_64, x86_64)
        assertEquals(InstructionSet.Riscv64, riscv64)
        assertEquals(InstructionSet.Unknown("foobar"), unknown)
        assertEquals(InstructionSet.Unknown("foobar64"), unknown64)
    }

    @Test
    fun test_toString_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val arm = InstructionSet.fromString("arm")
        val arm64 = InstructionSet.fromString("arm64")
        val x86 = InstructionSet.fromString("x86")
        val x86_64 = InstructionSet.fromString("x86_64")
        val riscv64 = InstructionSet.fromString("riscv64")
        val unknown = InstructionSet.fromString("foobar")
        val unknown64 = InstructionSet.fromString("foobar64")

        // Assert
        assertEquals("arm", arm.toString())
        assertEquals("arm64", arm64.toString())
        assertEquals("x86", x86.toString())
        assertEquals("x86_64", x86_64.toString())
        assertEquals("riscv64", riscv64.toString())
        assertEquals("foobar", unknown.toString())
        assertEquals("foobar64", unknown64.toString())
    }

    @Test
    fun test_text_works(): Unit = runBlockingWithTimeout {
        // Prepare
        val arm = InstructionSet.fromString("arm")
        val arm64 = InstructionSet.fromString("arm64")
        val x86 = InstructionSet.fromString("x86")
        val x86_64 = InstructionSet.fromString("x86_64")
        val riscv64 = InstructionSet.fromString("riscv64")
        val unknown = InstructionSet.fromString("foobar")
        val unknown64 = InstructionSet.fromString("foobar64")

        // Assert
        assertEquals("arm", arm.text)
        assertEquals("arm64", arm64.text)
        assertEquals("x86", x86.text)
        assertEquals("x86_64", x86_64.text)
        assertEquals("riscv64", riscv64.text)
        assertEquals("foobar", unknown.text)
        assertEquals("foobar64", unknown64.text)
    }
}
