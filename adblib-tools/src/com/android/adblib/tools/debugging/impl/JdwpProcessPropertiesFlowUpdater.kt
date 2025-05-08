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
package com.android.adblib.tools.debugging.impl

import com.android.adblib.tools.debugging.AtomicStateFlow
import com.android.adblib.tools.debugging.JdwpProcessProperties

/**
 * A component that asynchronously updates an [AtomicStateFlow] of [JdwpProcessProperties]
 */
internal interface JdwpProcessPropertiesFlowUpdater {

    /**
     * Updates [stateFlow] with incremental changes to [JdwpProcessProperties]
     * of a given JDWP [process][JdwpProcessProperties.pid] for as long as the process
     * is active.
     *
     * The caller is responsible for cancelling this coroutine function when no
     * more updates are needed, typically when the JDWP process is terminated.
     */
    suspend fun collectUpdates(stateFlow: AtomicStateFlow<JdwpProcessProperties>)
}
