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

import com.android.adblib.adbLogger
import com.android.adblib.tools.debugging.AtomicStateFlow
import com.android.adblib.tools.debugging.ExternalJdwpProcessPropertiesCollector
import com.android.adblib.tools.debugging.JdwpProcessProperties
import com.android.adblib.tools.debugging.mergeWith
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel

/**
 * Collect changes from an [ExternalJdwpProcessPropertiesCollector], merging them into
 * the current value of the [localPropertiesStateFlow].
 */
internal class ExternalPropertiesCollectorHandler(
    private val externalCollector: ExternalJdwpProcessPropertiesCollector,
    private val localCollectorJob: Job,
    private val localPropertiesStateFlow: AtomicStateFlow<JdwpProcessProperties>
) {
    private val session = externalCollector.process.device.session
    private val logger = adbLogger(session)

    suspend fun execute() {
        // Collect properties from external collector and merge them into our local properties
        // state flow.
        externalCollector.trackProperties().collect { externalProperties ->
            logger.debug { "Process ${externalProperties.pid} properties updated: $externalProperties" }

            localPropertiesStateFlow.update { localProperties ->
                // merge external properties with local properties
                val newProperties = localProperties.mergeWith(externalProperties)

                // Stop local property collector so that it does not hog a JDWP session
                if (newProperties.completed) {
                    logger.debug { "Cancelling local collector because collection is completed" }
                    localCollectorJob.cancel("Cancellation due to external collector completion")
                }

                logger.debug { "Updating local JDWP properties: $newProperties" }
                newProperties
            }
        }
    }
}
