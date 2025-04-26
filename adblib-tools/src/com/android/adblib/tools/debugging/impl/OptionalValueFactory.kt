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
package com.android.adblib.tools.debugging.impl

import com.android.adblib.ConnectedDevice
import com.android.adblib.CoroutineScopeCache
import com.android.adblib.InstructionSet
import com.android.adblib.tools.debugging.JdwpProcessProperties.Companion.unsupportedByOlderApi
import com.android.adblib.tools.debugging.OptionalValue
import com.android.adblib.tools.debugging.impl.JdwpProcessPropertiesCollectorImpl.Companion.filterFakeName

/**
 * A factory for [OptionalValue] related to [JdwpProcessProperties]
 */
internal class OptionalValueFactory(val device: ConnectedDevice) {

    private val lastVmIdentifier = LatestValueContainer<String>()
    private val lastJvmFlags = LatestValueContainer<String>()
    private val lastFeatures = LatestValueContainer<List<String>>()

    fun ofInstructionSet(value: InstructionSet): OptionalValue<InstructionSet> {
        return when (value) {
            is InstructionSet.Arm -> InstructionSetsSingletons.arm
            is InstructionSet.Arm64 -> InstructionSetsSingletons.arm64
            is InstructionSet.Riscv64 -> InstructionSetsSingletons.riscv64
            is InstructionSet.X86 -> InstructionSetsSingletons.x86
            is InstructionSet.X86_64 -> InstructionSetsSingletons.x86_64
            else -> OptionalValue.of(value)
        }
    }

    fun ofNullableInstructionSet(value: InstructionSet?): OptionalValue<InstructionSet> {
        return value?.let { ofInstructionSet(value) } ?: OptionalValue.empty()
    }

    fun ofVmIdentifier(value: String): OptionalValue<String> {
        return lastVmIdentifier.of(value)
    }

    fun ofJvmFlags(value: String): OptionalValue<String> {
        return lastJvmFlags.of(value)
    }

    fun ofNullableJvmFlags(value: String?): OptionalValue<String> {
        return value?.let { ofJvmFlags(value) } ?: OptionalValue.empty()
    }

    fun ofFeatures(value: List<String>): OptionalValue<List<String>> {
        return lastFeatures.of(value)
    }

    /**
     * See [OptionalValue.ofNullable]
     */
    fun <T> ofNullable(value: T): OptionalValue<T & Any> {
        return OptionalValue.ofNullable(value)
    }

    /**
     * See [OptionalValue.of]
     */
    fun <T : Any> of(value: T): OptionalValue<T> {
        return OptionalValue.of(value)
    }

    /**
     * See [OptionalValue.ofError]
     */
    fun <T : Any> ofError(message: String): OptionalValue<T> {
        return OptionalValue.ofError(message)
    }

    /**
     * Returns an [OptionalValue] for a process or package name, which may contain "fake" names
     * (see [filterFakeName]).
     */
    fun ofFilteredFakeName(name: String?): OptionalValue<String> {
        return when (name) {
            null -> OptionalValue.unsupportedByOlderApi()
            else -> filterFakeName(name)?.let { OptionalValue.of(it) } ?: OptionalValue.empty()
        }
    }

    /**
     * Returns an [OptionalValue] for a process or package name, which may contain "fake" names
     * (see [filterFakeName]).
     */
    fun ofFilteredFakeNames(names: List<String>?): OptionalValue<List<String>> {
        val goodNames = names?.mapNotNull { filterFakeName(it) } ?: return OptionalValue.empty()
        return if (goodNames.isEmpty()) {
            OptionalValue.empty()
        } else {
            OptionalValue.of(goodNames)
        }
    }

    /**
     * Keeps a reference to a single [OptionalValue] so it can be shared across consumers.
     */
    private class LatestValueContainer<T : Any> {

        @Volatile
        private var _lastValue: OptionalValue<T>? = null

        fun of(value: T): OptionalValue<T> {
            return _lastValue.let {
                if (it !== null && it.hasValue && it.getOrThrow() == value) {
                    it
                } else {
                    OptionalValue.of(value).also { newOptionalValue ->
                        _lastValue = newOptionalValue
                    }
                }
            }
        }
    }

    /**
     * Singleton container for all known values of [InstructionSet]
     */
    object InstructionSetsSingletons {

        val arm = OptionalValue.of<InstructionSet>(InstructionSet.Arm)
        val arm64 = OptionalValue.of<InstructionSet>(InstructionSet.Arm64)
        val riscv64 = OptionalValue.of<InstructionSet>(InstructionSet.Riscv64)
        val x86 = OptionalValue.of<InstructionSet>(InstructionSet.X86)
        val x86_64 = OptionalValue.of<InstructionSet>(InstructionSet.X86_64)
    }
}

private val optionalValueFactoryKey =
    CoroutineScopeCache.Key<OptionalValueFactory>("OptionalValueFactory")

/**
 * The [OptionalValueFactory] for this device
 */
internal val ConnectedDevice.optionalValueFactory: OptionalValueFactory
    get() = this.cache.getOrPut(optionalValueFactoryKey) {
        OptionalValueFactory(this)
    }
