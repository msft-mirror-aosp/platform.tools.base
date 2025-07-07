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

package com.android.tools.agent.appinspection.rendering

import android.graphics.Rect
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol

fun buildDrawInstructionsProto(
    rootId: Long,
    bounds: List<Rect>,
    color: Int,
    strokeThickness: Float,
    label: Label? = null
): List<LayoutInspectorViewProtocol.DrawInstruction> {
    return bounds.map {
            val rect =
                LayoutInspectorViewProtocol.Rect.newBuilder()
                    .apply {
                        x = it.left
                        y = it.top
                        w = it.right - it.left
                        h = it.bottom - it.top
                    }
                    .build()

            LayoutInspectorViewProtocol.DrawInstruction.newBuilder()
                .apply {
                    this.rootId = rootId
                    this.bounds = rect
                    this.color = color
                    this.strokeThickness = strokeThickness
                    if (label != null) {
                        this.label = LayoutInspectorViewProtocol.Label.newBuilder().apply {
                            text = label.text
                            size = label.size
                        }.build()
                    }
                }
                .build()
        }
}

fun buildUserInputEventProto(
    rootId: Long,
    x: Float,
    y: Float,
    type: LayoutInspectorViewProtocol.UserInputEvent.Type
): LayoutInspectorViewProtocol.Event {
    val userInputEvent =
        LayoutInspectorViewProtocol.UserInputEvent.newBuilder()
            .setType(type)
            .setRootId(rootId)
            .setX(x)
            .setY(y)
            .build()

    return LayoutInspectorViewProtocol.Event.newBuilder().setUserInputEvent(userInputEvent).build()
}
