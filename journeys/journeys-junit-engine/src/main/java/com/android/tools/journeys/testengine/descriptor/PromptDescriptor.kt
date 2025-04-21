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

import org.junit.platform.engine.TestDescriptor.Type
import org.junit.platform.engine.UniqueId
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor

class PromptDescriptor(
    parentId: UniqueId,
    promptText: String,
    private val promptIndex: Int
) :
    AbstractTestDescriptor(parentId.append(SEGMENT_TYPE, "$promptIndex"), promptText) {
    companion object {
        const val SEGMENT_TYPE: String = "prompt"
    }

    override fun getType(): Type = Type.TEST

    fun getPromptIndex(): Int = promptIndex
}
