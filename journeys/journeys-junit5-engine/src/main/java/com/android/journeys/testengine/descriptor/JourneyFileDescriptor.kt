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

package com.android.journeys.testengine.descriptor

import com.android.journeys.testengine.JourneysExecutionContext
import org.junit.platform.engine.TestDescriptor
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor
import org.junit.platform.engine.support.hierarchical.Node
import java.io.File

class JourneyFileDescriptor(parentId: UniqueId, private val journeyFile: File, journeyName: String)
    : AbstractTestDescriptor(
    parentId.append(SEGMENT_TYPE, journeyFile.absolutePath), journeyName),
    Node<JourneysExecutionContext> {
    companion object {
        const val SEGMENT_TYPE: String = "journeyFile"
    }

    override fun getType() = TestDescriptor.Type.TEST

    // Force to run this mode in the same thread as its parent (=DeviceDescriptor)
    // so that two Journey file will not be executed on the same device in parallel.
    override fun getExecutionMode() = Node.ExecutionMode.SAME_THREAD

    override fun execute(
        context: JourneysExecutionContext,
        dynamicTestExecutor: Node.DynamicTestExecutor
    ): JourneysExecutionContext {
        requireNotNull(context.targetDeviceId) { "Target Device ID should not be null." }

        // TODO: Execute Journeys test on context.targetDeviceId here.

        return context
    }
}
