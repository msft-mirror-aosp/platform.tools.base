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
import com.android.adblib.tools.debugging.packets.ddms.chunks.DdmsFeatChunk
import com.android.adblib.tools.debugging.packets.ddms.chunks.DdmsHeloChunk

/**
 * List of known properties corresponding to a [JdwpProcess] instance.
 */
data class JdwpProcessProperties(

    /**
     * The process ID. This is the only property that is guaranteed to be valid, all other
     * properties can be `null` or have default value until more is known about a process.
     */
    val pid: Int,

    /**
     * The process name that uniquely identifies the process on the device, or `null` if the process
     * name is not known (yet) due to debugger latency or an error connecting to the process and
     * retrieving data about it.
     *
     * The process name is often equal to [packageName], except when a `android:process`
     * process name entry is specified in the
     * [AndroidManifest.xml](https://developer.android.com/guide/topics/manifest/application-element)
     * file.
     */
    val processName: String? = null,

    /**
     * The package name of the process, or `null` if the value is not known yet or if the device
     * does not support retrieving this information (R+ only)
     */
    val packageName: String? = null,

    /**
     * The User ID this process is running in context of, or `null` if the value is not known yet or
     * the device does not support retrieving this information (R+ only).
     */
    val userId: Int? = null,

    /**
     * The Android VM identifier, or `null` if the value is not known yet.
     */
    val vmIdentifier: String? = null,

    /**
     * The [InstructionSet] used by this process, or `null` if the value is not known yet.
     * https://cs.android.com/android/platform/superproject/main/+/b8e25499cd5f4290507e5be0d7686c2b129cb6ab:art/libartbase/arch/instruction_set.cc;l=41
     */
    val instructionSet: InstructionSet? = null,

    /**
     * The JVM flags, or `null` if the value is not known yet.
     */
    val jvmFlags: String? = null,

    /**
     * Whether legacy native debugging is supported.
     */
    @Deprecated("This property was never fully supported and is now completely deprecated")
    val isNativeDebuggable: Boolean = false,

    /**
     * `true` if the process is waiting for a debugger to attach.
     * `false` if we don't know or if a debugger is already attached.
     */
    val isWaitingForDebugger: Boolean = false,

    /**
     * List of features reported by the [DdmsFeatChunk] packet
     */
    val features: List<String> = emptyList(),

    /**
     * Whether this [JdwpProcessProperties] instance is fully populated, i.e. there is no pending
     * operation to collect more information. See the [exception] property for additional
     * information about the status.
     */
    val completed: Boolean = false,

    /**
     * The error related to retrieving properties (other than [pid]), or `null`.
     *
     * This value is only set when [completed] is `true`, and remains `null` unless
     * there was an error retrieving some property values.
     *
     * For example, it is sometimes not possible to retrieve any information about a process ID
     * from the Android VM if there is already a JDWP session active for that process.
     */
    val exception: Throwable? = null,
) {
    /**
     * A description of the [instructionSet] (e.g. "64-bit (arm64)"), or `null` if
     * the value is not known yet.
     *
     * See [instructionSet] for the specific CPU architecture
     */
    val instructionSetDescription: String?
        get() = instructionSet?.toLegacyDescription()
}

/**
 * Convert this [InstructionSet] (typically `"arm64"` or `"arm"`) to the legacy representation
 * used in [DdmsHeloChunk.abi] for backward compatibility (e.g. `"64-bit (arm)"`).
 */
fun InstructionSet.toLegacyDescription(): String {
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
fun InstructionSet.Companion.fromLegacyDescription(value: String): InstructionSet {
    // See https://cs.android.com/android/_/android/platform/frameworks/base/+/eea3b0d26916f92184b48d8ba95a064db2ca884c:core/java/android/ddm/DdmHandleHello.java;l=128
    val index1 = value.indexOf('(')
    val index2 = value.indexOf(')')
    return if (index1 >= 0 && index2 > index1) {
        fromString(value.substring(index1 + 1, index2))
    } else {
        fromString(value)
    }
}

internal fun JdwpProcessProperties.mergeWith(other: JdwpProcessProperties): JdwpProcessProperties {
    val source = this
    @Suppress("DEPRECATION")
    return source.copy(
        processName = source.processName.mergeWith(other.processName),
        userId = source.userId.mergeWith(other.userId),
        packageName = source.packageName.mergeWith(other.packageName),
        vmIdentifier = source.vmIdentifier.mergeWith(other.vmIdentifier),
        instructionSet = source.instructionSet.mergeWith(other.instructionSet),
        jvmFlags = source.jvmFlags.mergeWith(other.jvmFlags),
        isNativeDebuggable = source.isNativeDebuggable.mergeWith(other.isNativeDebuggable),
        features = source.features.mergeWith(other.features),
        completed = source.completed.mergeWith(other.completed),
        exception = source.exception.mergeWith(other.exception),
        isWaitingForDebugger = source.isWaitingForDebugger.mergeWith(other.isWaitingForDebugger),
    )
}

internal fun JdwpProcessProperties.addException(throwable: Throwable): Throwable {
    return exception?.also { it.addSuppressed(throwable) } ?: throwable
}

private fun String?.mergeWith(other: String?): String? {
    return this ?: other
}

private fun Throwable?.mergeWith(other: Throwable?): Throwable? {
    return this ?: other
}

private fun Int?.mergeWith(other: Int?): Int? {
    return this ?: other
}

private fun InstructionSet?.mergeWith(other: InstructionSet?): InstructionSet? {
    return this ?: other
}

private fun Boolean.mergeWith(other: Boolean): Boolean {
    return if (this) true else other
}

private fun List<String>.mergeWith(other: List<String>): List<String> {
    return this.ifEmpty { other }
}
