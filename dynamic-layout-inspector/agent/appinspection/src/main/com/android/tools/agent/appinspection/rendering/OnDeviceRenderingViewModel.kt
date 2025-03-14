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

import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import androidx.inspection.Connection
import com.android.tools.agent.appinspection.InspectorView
import com.android.tools.agent.appinspection.SPAM_LOG_TAG
import com.android.tools.agent.appinspection.framework.getChildren
import com.android.tools.agent.appinspection.sendEvent
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol.DrawInstruction
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol.UserInputEvent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Defines a draw instruction for an [OverlayView].
 * @param rootId The id of the root view containing the [OverlayView] that needs to do the drawing.
 * @param bounds The rectangle to be rendered.
 */
data class OverlayViewInstruction(val rootId: Long, val bounds: Rect, val color: Int, val label: String?)

/** View model handling the logic for on-device rendering */
class OnDeviceRenderingViewModel(
    private val scope: CoroutineScope,
    private val connection: Connection,
    private val mainDispatcher: CoroutineDispatcher
) {
    @VisibleForTesting
    var enableOnDeviceRendering = false
        private set
    @VisibleForTesting
    var roots = emptyMap<Long, InspectorView>()
        private set

    private val _selectedNodes = MutableStateFlow<List<OverlayViewInstruction>>(emptyList())
    val selectedNodes = _selectedNodes.asStateFlow()

    private val _hoveredNodes = MutableStateFlow<List<OverlayViewInstruction>>(emptyList())
    val hoveredNodes = _hoveredNodes.asStateFlow()

    private val _visibleNodes = MutableStateFlow<List<OverlayViewInstruction>>(emptyList())
    val visibleNodes = _visibleNodes.asStateFlow()

    private val _recomposingNodes = MutableStateFlow<List<OverlayViewInstruction>>(emptyList())
    val recomposingNodes = _recomposingNodes.asStateFlow()

    private val _interceptTouchEvents = MutableStateFlow<Boolean>(false)
    var interceptTouchEvents = _interceptTouchEvents.asStateFlow()

    suspend fun setEnableOnDeviceRendering(enable: Boolean) {
        if (enable) {
            roots.values.forEach { addOverlayView(it) }
        }
        else {
            roots.values.forEach { removeOverlayView(it) }
        }
        enableOnDeviceRendering = enable
    }

    suspend fun setRoots(newRoots: Map<Long, InspectorView>) {
        if (enableOnDeviceRendering) {
            val addedRoots = newRoots.filterKeys { it !in roots.keys }
            addedRoots.values.forEach { inspectorView -> addOverlayView(inspectorView) }

            val removedRoots = roots.filterKeys { it !in newRoots.keys }
            removedRoots.values.forEach { inspectorView -> removeOverlayView(inspectorView) }
        }
        roots = newRoots
    }

    fun setSelectedNodes(instruction: List<DrawInstruction>) {
        _selectedNodes.value = instruction.map { it.toOverlayViewInstruction() }
    }

    fun setHoveredNodes(instruction: List<DrawInstruction>) {
        _hoveredNodes.value = instruction.map { it.toOverlayViewInstruction() }
    }

    fun setVisibleNodes(instructions: List<DrawInstruction>) {
        _visibleNodes.value = instructions.map { it.toOverlayViewInstruction() }
    }

    fun setRecomposingNodes(instructions: List<DrawInstruction>) {
        _recomposingNodes.value = instructions.map { it.toOverlayViewInstruction() }
    }

    fun setInterceptTouchEvents(intercept: Boolean) {
        _interceptTouchEvents.value = intercept
    }

    fun onTouchEvent(rootId: Long, point: PointF) {
        sendInputEvent(rootId, point, UserInputEvent.Type.SELECTION)
    }

    fun onHoverEvent(rootId: Long, point: PointF) {
        sendInputEvent(rootId, point, UserInputEvent.Type.HOVER)
    }

    fun onRightClick(rootId: Long, point: PointF) {
        sendInputEvent(rootId, point, UserInputEvent.Type.RIGHT_CLICK)
    }

    suspend fun dispose() {
        setEnableOnDeviceRendering(false)
        setRoots(emptyMap())
    }

    private fun sendInputEvent(rootId: Long, point: PointF, type: UserInputEvent.Type) {
        if (!interceptTouchEvents.value) {
            return
        }

        connection.sendEvent {
            userInputEvent = UserInputEvent.newBuilder().apply {
                this.rootId = rootId
                this.type = type
                x = point.x
                y = point.y
            }.build()
        }
    }

    private suspend fun addOverlayView(inspectorView: InspectorView) = withContext(mainDispatcher) {
        val view = inspectorView.view
        if (view is ViewGroup) {
            if (view.getChildren().filterIsInstance<OverlayView>().isNotEmpty()) {
                // Do nothing, overlay view is already there
            } else {
                try {
                    val overlayView = OverlayView(
                        root = view,
                        scope = scope,
                        viewModel = this@OnDeviceRenderingViewModel
                    )
                    view.addView(
                        overlayView,
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    Log.w(SPAM_LOG_TAG, "OverlayView added (${view.left}, ${view.top})")
                } catch (t: Throwable) {
                    Log.w(SPAM_LOG_TAG, "Failed to add OverlayView to view: $view, $t")
                }
            }
        }
    }

    private suspend fun removeOverlayView(inspectorView: InspectorView) = withContext(mainDispatcher) {
        val view = inspectorView.view
        if (view is ViewGroup) {
            val overlayView = view.getChildren().filterIsInstance<OverlayView>().firstOrNull()
            if (overlayView != null) {
                view.removeView(overlayView)
                Log.w(SPAM_LOG_TAG, "OverlayView removed")
            }
            else {
                Log.w(SPAM_LOG_TAG, "OverlayView missing")
                // Do nothing, overlay view is not there
            }
        }
    }
}

private fun DrawInstruction.toOverlayViewInstruction() = OverlayViewInstruction(rootId, bounds.toAndroidRect(), color, labelOrNull())
private fun LayoutInspectorViewProtocol.Rect.toAndroidRect() = Rect(x, y, x + w, y + h)
private fun DrawInstruction.labelOrNull() = if (hasLabel()) label else null
