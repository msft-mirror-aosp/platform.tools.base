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
package com.android.tools.journeys.testengine.descriptor

import com.android.tools.journeys.testengine.JourneysExecutionContext
import org.junit.platform.engine.TestDescriptor.Type
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.descriptor.ClassSource
import org.junit.platform.engine.support.hierarchical.Node

class DeviceDescriptor(
    parentId: UniqueId,
    private val deviceId: String,
    private val deviceName: String
) :
    AbstractTestDescriptor(
        parentId.append(SEGMENT_TYPE, deviceId),
        deviceId,
        ClassSource.from(deviceId)
    ),
    Node<JourneysExecutionContext> {

    companion object {
        const val SEGMENT_TYPE: String = "deviceId"
    }

    // Disable multi-device execution for now.
    override fun getExecutionMode() = Node.ExecutionMode.SAME_THREAD

    override fun getType(): Type = Type.CONTAINER

    override fun execute(
        context: JourneysExecutionContext,
        dynamicTestExecutor: Node.DynamicTestExecutor
    ): JourneysExecutionContext {
        context.executionListener.reportingEntryPublished(
            this,
            ReportEntry.from("deviceId", deviceId)
        )
        if (deviceName.isNotBlank()) {
            context.executionListener.reportingEntryPublished(
                this,
                ReportEntry.from("deviceDisplayName", deviceName)
            )
        }
        return context.copy(targetDeviceId = deviceId)
    }
}
