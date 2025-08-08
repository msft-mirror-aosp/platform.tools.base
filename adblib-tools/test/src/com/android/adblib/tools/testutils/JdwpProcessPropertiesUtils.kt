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
package com.android.adblib.tools.testutils

import com.android.adblib.tools.debugging.JdwpProcessProperties
import com.android.adblib.tools.debugging.OptionalValue

/**
 * For testing only: check that all [OptionalValue] of this [JdwpProcessProperties] are either
 * [OptionalValue.empty] or [OptionalValue.isError].
 */
internal fun JdwpProcessProperties.areAllPropertiesInitialized(): Boolean {
    @Suppress("DEPRECATION")
    return areAllPropertiesExceptWaitingForDebuggerInitialized() &&
            !isWaitingForDebugger.isEmpty
}

/**
 * For testing only: check that all [OptionalValue] of this [JdwpProcessProperties] are either
 * [OptionalValue.empty] or [OptionalValue.isError].
 */
internal fun JdwpProcessProperties.areAllPropertiesExceptWaitingForDebuggerInitialized(): Boolean {
    @Suppress("DEPRECATION")
    return !processName.isEmpty &&
            !userId.isEmpty &&
            !packageName.isEmpty &&
            !vmIdentifier.isEmpty &&
            !instructionSet.isEmpty &&
            !jvmFlags.isEmpty &&
            !isNativeDebuggable.isEmpty &&
            !features.isEmpty
}
