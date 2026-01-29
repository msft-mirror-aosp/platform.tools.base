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
package com.android.adblib.tools.debugging

import com.android.adblib.InstructionSet
import com.android.adblib.testingutils.CoroutineTestUtils.runBlockingWithTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class InstructionSetExtensionsTest {

  @Test
  fun test_fromLegacyDescription_works(): Unit = runBlockingWithTimeout {
    // Prepare
    val arm = InstructionSet.fromLegacyDescription("32-bit (arm)")
    val arm64 = InstructionSet.fromLegacyDescription("64-bit (arm64)")
    val x86 = InstructionSet.fromLegacyDescription("32-bit (x86)")
    val x86_64 = InstructionSet.fromLegacyDescription("64-bit (x86_64)")
    val riscv64 = InstructionSet.fromLegacyDescription("64-bit (riscv64)")
    val unknown = InstructionSet.fromLegacyDescription("foobar")
    val unknown64 = InstructionSet.fromLegacyDescription("foobar64")

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
  fun test_toLegacyDescription_works(): Unit = runBlockingWithTimeout {
    // Prepare
    val arm = InstructionSet.fromLegacyDescription("32-bit (arm)")
    val arm64 = InstructionSet.fromLegacyDescription("64-bit (arm64)")
    val x86 = InstructionSet.fromLegacyDescription("32-bit (x86)")
    val x86_64 = InstructionSet.fromLegacyDescription("64-bit (x86_64)")
    val riscv64 = InstructionSet.fromLegacyDescription("64-bit (riscv64)")
    val unknown = InstructionSet.fromLegacyDescription("foobar")
    val unknown64 = InstructionSet.fromLegacyDescription("foobar64")

    // Assert
    assertEquals("32-bit (arm)", arm.toLegacyDescription())
    assertEquals("64-bit (arm64)", arm64.toLegacyDescription())
    assertEquals("32-bit (x86)", x86.toLegacyDescription())
    assertEquals("64-bit (x86_64)", x86_64.toLegacyDescription())
    assertEquals("64-bit (riscv64)", riscv64.toLegacyDescription())
    assertEquals("32-bit (foobar)", unknown.toLegacyDescription())
    assertEquals("64-bit (foobar64)", unknown64.toLegacyDescription())
  }
}
