/*
 * Copyright (C) 2022 The Android Open Source Project
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
import com.android.adblib.tools.debugging.OptionalValue.Companion.ofError
import com.android.adblib.tools.debugging.packets.ddms.chunks.DdmsFeatChunk
import com.android.adblib.tools.debugging.packets.ddms.chunks.DdmsHeloChunk

/**
 * List of known properties corresponding to a [JdwpProcess] instance.
 */
data class JdwpProcessProperties(

    /**
     * The process ID.
     *
     * Note: This is the only property that is guaranteed to be valid, all other
     * properties are instances of [OptionalValue].
     */
    val pid: Int,

    /**
     * The process name
     *
     * A value of [OptionalValue.empty] indicates the underlying process discovery
     * mechanism is still trying to retrieve the actual value.
     *
     * A value of [OptionalValue.isError], for example [unsupportedByOlderApiSingleton],
     * indicates the underlying process discovery mechanism could not retrieve the actual value
     * for some reason.
     *
     * Note: The process name is often equal to [packageName], except when a `android:process`
     * process name entry is specified in the
     * [AndroidManifest.xml](https://developer.android.com/guide/topics/manifest/application-element)
     * file.
     */
    val processName: OptionalValue<String> = OptionalValue.empty(),

    /**
     * The package name of the process
     *
     * A value of [OptionalValue.empty] indicates the underlying process discovery
     * mechanism is still trying to retrieve the actual value.
     *
     * A value of [OptionalValue.isError], for example [unsupportedByOlderApiSingleton],
     * indicates the underlying process discovery mechanism could not retrieve the actual value
     * for some reason.
     */
    val packageName: OptionalValue<String> = OptionalValue.empty(),

    /**
     * The User ID this process is running in context of
     *
     * A value of [OptionalValue.empty] indicates the underlying process discovery
     * mechanism is still trying to retrieve the actual value.
     *
     * A value of [OptionalValue.isError], for example [unsupportedByOlderApiSingleton],
     * indicates the underlying process discovery mechanism could not retrieve the actual value
     * for some reason.
     */
    val userId: OptionalValue<Int> = OptionalValue.empty(),

    /**
     * The Android VM identifier
     *
     * A value of [OptionalValue.empty] indicates the underlying process discovery
     * mechanism is still trying to retrieve the actual value.
     *
     * A value of [OptionalValue.isError], for example [unsupportedByOlderApiSingleton],
     * indicates the underlying process discovery mechanism could not retrieve the actual value
     * for some reason.
     */
    val vmIdentifier: OptionalValue<String> = OptionalValue.empty(),

    /**
     * The [InstructionSet] used by this process
     *
     * A value of [OptionalValue.empty] indicates the underlying process discovery
     * mechanism is still trying to retrieve the actual value.
     *
     * A value of [OptionalValue.isError], for example [unsupportedByOlderApiSingleton],
     * indicates the underlying process discovery mechanism could not retrieve the actual value
     * for some reason.
     */
    val instructionSet: OptionalValue<InstructionSet> = OptionalValue.empty(),

    /**
     * The JVM flags, i.e. the value of the `jvmFlags` env. variable of the process.
     *
     * A value of [OptionalValue.empty] indicates the underlying process discovery
     * mechanism is still trying to retrieve the actual value.
     *
     * A value of [OptionalValue.isError], for example [unsupportedByOlderApiSingleton],
     * indicates the underlying process discovery mechanism could not retrieve the actual value
     * for some reason.
     */
    val jvmFlags: OptionalValue<String> = OptionalValue.empty(),

    /**
     * Whether legacy native debugging is supported. This property is deprecated.
     *
     * A value of [OptionalValue.empty] indicates the underlying process discovery
     * mechanism is still trying to retrieve the actual value.
     *
     * A value of [OptionalValue.isError], for example [unsupportedByOlderApiSingleton],
     * indicates the underlying process discovery mechanism could not retrieve the actual value
     * for some reason.
     */
    @Deprecated("This property was never fully supported and is now completely deprecated")
    val isNativeDebuggable: OptionalValue<Boolean> = OptionalValue.empty(),

    /**
     * Whether the process is waiting for a debugger to attach.
     *
     * A value of [OptionalValue.empty] indicates the underlying process discovery
     * mechanism is still trying to retrieve the actual value.
     *
     * A value of [OptionalValue.isError], for example [unsupportedByOlderApiSingleton],
     * indicates the underlying process discovery mechanism could not retrieve the actual value
     * for some reason.
     */
    val isWaitingForDebugger: OptionalValue<Boolean> = OptionalValue.empty(),

    /**
     * List of features reported by the [DdmsFeatChunk] packet.
     *
     * A value of [OptionalValue.empty] indicates the underlying process discovery
     * mechanism is still trying to retrieve the actual value.
     *
     * A value of [OptionalValue.isError], for example [unsupportedByOlderApiSingleton],
     * indicates the underlying process discovery mechanism could not retrieve the actual value
     * for some reason.
     */
    val features: OptionalValue<List<String>> = OptionalValue.empty(),

    /**
     * The error related to retrieving properties (other than [pid]), or `null`.
     *
     * This value is only set when [completed] is `true`, and remains `null` unless
     * there was an error retrieving some property values.
     *
     * For example, it is sometimes not possible to retrieve any information about a process ID
     * from the Android VM if there is already a JDWP session active for that process.
     */
    val exception: OptionalValue<Throwable> = OptionalValue.empty(),
) {
    private var _instructionSetDescription: OptionalValue<String>? = null

    /**
     * A description of the [InstructionSet] (e.g. "64-bit (arm64)"), or [unsupportedByOlderApi] if the device does not support retrieving this information.
     *
     * A value of [OptionalValue.empty] indicates the underlying process discovery
     * mechanism is still trying to retrieve the actual value.
     *
     * A value of [OptionalValue.isError], for example [unsupportedByOlderApiSingleton],
     * indicates the underlying process discovery mechanism could not retrieve the actual value
     * for some reason.
     */
    val instructionSetDescription: OptionalValue<String>
        get() {
            return _instructionSetDescription
                ?: computeInstructionSetDescription().also { _instructionSetDescription = it }
        }

    private fun computeInstructionSetDescription(): OptionalValue<String> {
        return if (instructionSet.hasValue) {
            OptionalValue.of(instructionSet.getOrThrow().toLegacyDescription())
        } else if (instructionSet.isError) {
            ofError(instructionSet.getErrorMessageOrThrow())
        } else {
            OptionalValue.empty()
        }
    }

    companion object {
        private val unsupportedByOlderApiSingleton =
            ofError<Any>("The JDWP process property is not supported by this Android API")

        /**
         * The [OptionalValue.isError] containing the error specific to a property of
         * [JdwpProcessProperties] that cannot be retrieved because the property is not
         * supported on older Android APIs.
         */
        fun <T: Any> OptionalValue.Companion.unsupportedByOlderApi(): OptionalValue<T> {
            @Suppress("UNCHECKED_CAST")
            return unsupportedByOlderApiSingleton as OptionalValue<T>
        }
    }
}

/**
 * Convert this [InstructionSet] (typically `"arm64"` or `"arm"`) to the legacy representation
 * used in [DdmsHeloChunk.abi] for backward compatibility (e.g. `"64-bit (arm)"`).
 */
internal fun InstructionSet.toLegacyDescription(): String {
    // See https://cs.android.com/android/_/android/platform/frameworks/base/+/eea3b0d26916f92184b48d8ba95a064db2ca884c:core/java/android/ddm/DdmHandleHello.java;l=128
    val instructionSetDescription = if (text.contains("64")) {
        "64-bit"
    } else {
        "32-bit"
    }
    return if (text.isEmpty()) {
        instructionSetDescription
    } else {
        "$instructionSetDescription (${text})"
    }
}

/**
 * Convert a legacy instruction set description from the [DdmsHeloChunk.abi] field
 * of [DdmsHeloChunk] (e.g. `"64-bit (arm)"`) into a valid [InstructionSet].
 *
 * Note: Values that are not recognized are returned as [InstructionSet.Unknown] instances.
 */
internal fun InstructionSet.Companion.fromLegacyDescription(value: String): InstructionSet {
    // See https://cs.android.com/android/_/android/platform/frameworks/base/+/eea3b0d26916f92184b48d8ba95a064db2ca884c:core/java/android/ddm/DdmHandleHello.java;l=128
    val index1 = value.indexOf('(')
    val index2 = value.indexOf(')')
    return if (index1 >= 0 && index2 > index1) {
        fromString(value.substring(index1 + 1, index2))
    } else {
        fromString(value)
    }
}

/**
 * Returns a new [JdwpProcessProperties] that contains all [OptionalValue] properties
 * that are have [a value][OptionalValue.hasValue] in [newer] or [this].
 */
internal fun JdwpProcessProperties.mergeWith(newer: JdwpProcessProperties): JdwpProcessProperties {
    val current = this
    @Suppress("DEPRECATION")
    return current.copy(
        processName = newer.processName.orElse(current.processName),
        userId = newer.userId.orElse(current.userId),
        packageName = newer.packageName.orElse(current.packageName),
        vmIdentifier = newer.vmIdentifier.orElse(current.vmIdentifier),
        instructionSet = newer.instructionSet.orElse(current.instructionSet),
        jvmFlags = newer.jvmFlags.orElse(current.jvmFlags),
        isNativeDebuggable = newer.isNativeDebuggable.orElse(current.isNativeDebuggable),
        features = newer.features.orElse(current.features),
        exception = newer.exception.orElse(current.exception),
        isWaitingForDebugger = newer.isWaitingForDebugger.orElse(current.isWaitingForDebugger),
    )
}

/**
 * Adds an [Throwable.suppressedExceptions] to [JdwpProcessProperties.exception]
 */
internal fun JdwpProcessProperties.addException(throwable: Throwable): OptionalValue<Throwable> {
    return if (exception.hasValue) {
        exception.getOrThrow().addSuppressed(throwable)
        exception
    } else {
        OptionalValue.of(throwable)
    }
}
