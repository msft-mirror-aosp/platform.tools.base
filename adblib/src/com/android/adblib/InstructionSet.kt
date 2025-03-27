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
package com.android.adblib

/**
 * The CPU instruction set used by an Android VM. This is sometimes called `"isa"` or `"architecture"`
 * in the Android documentation.
 *
 * See [Android VM Instruction Set](https://cs.android.com/android/platform/superproject/main/+/b8e25499cd5f4290507e5be0d7686c2b129cb6ab:art/libartbase/arch/instruction_set.cc;l=41)
 *
 * Note: `"abi"` is a slightly different concept, related to
 * [NDK abi](https://developer.android.com/ndk/guides/abis).
 *
 */
sealed class InstructionSet {

    abstract val text: String

    abstract override fun toString(): String

    data object Arm : InstructionSet() {

        override val text: String
            get() = "arm"

        override fun toString(): String {
            return text
        }

    }

    data object Arm64 : InstructionSet() {

        override val text: String
            get() = "arm64"

        override fun toString(): String {
            return text
        }

    }

    data object Riscv64 : InstructionSet() {

        override val text: String
            get() = "riscv64"

        override fun toString(): String {
            return text
        }

    }

    data object X86 : InstructionSet() {

        override val text: String
            get() = "x86"

        override fun toString(): String {
            return text
        }
    }

    data object X86_64 : InstructionSet() {

        override val text: String
            get() = "x86_64"

        override fun toString(): String {
            return text
        }
    }

    /**
     * Unknown or unrecognized instruction set. [text] contains the original string as returned
     * by the Android VM. This could happen in case of newly released CPU types not yet supported
     * by `adblib`.
     */
    data class Unknown(override val text: String) : InstructionSet() {

        override fun toString(): String {
            return text
        }
    }

    open val is64Bit: Boolean
        get() = text.contains("64")

    open val is32Bit: Boolean
        get() = !is64Bit

    open val isArm: Boolean
        get() = text.startsWith("arm")

    open val isX86: Boolean
        get() = text.startsWith("x86")

    open val isRiscV: Boolean
        get() = text.startsWith("riscv")

    open val isUnknown: Boolean
        get() = (this is Unknown)

    companion object {

        /**
         * Convert an instruction string representation (e.g. from [AppProcessEntry.architecture])
         * to a valid [InstructionSet] instance.
         *
         * Note: Values that are not recognized are returned as [InstructionSet.Unknown] instances.
         */
        fun fromString(value: String): InstructionSet {
            return when (value) {
                Arm.text -> Arm
                Arm64.text -> Arm64
                Riscv64.text -> Riscv64
                X86.text -> X86
                X86_64.text -> X86_64
                else -> Unknown(value)
            }
        }
    }
}
