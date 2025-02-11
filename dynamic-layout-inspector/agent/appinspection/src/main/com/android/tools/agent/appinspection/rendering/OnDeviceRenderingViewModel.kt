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
data class OverlayViewInstruction(val rootId: Long, val bounds: Rect)

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

    private val _selectedNode = MutableStateFlow<OverlayViewInstruction?>(null)
    val selectedNode = _selectedNode.asStateFlow()

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

    fun setSelectedNode(instruction: DrawInstruction?) {
        _selectedNode.value = instruction?.toOverlayViewInstruction()
    }

    fun setInterceptTouchEvents(intercept: Boolean) {
        _interceptTouchEvents.value = intercept
    }

    fun onTouchEvent(point: PointF) {
        if (!_interceptTouchEvents.value) {
            return
        }

        connection.sendEvent {
            touchEvent = LayoutInspectorViewProtocol.TouchEvent.newBuilder().apply {
                x = point.x
                y = point.y
            }.build()
        }
    }

    suspend fun dispose() {
        setEnableOnDeviceRendering(false)
        setRoots(emptyMap())
    }

    private suspend fun addOverlayView(inspectorView: InspectorView) = withContext(mainDispatcher) {
        val view = inspectorView.view
        if (view is ViewGroup) {
            if (view.getChildren().filterIsInstance<OverlayView>().isNotEmpty()) {
                // Do nothing, overlay view is already there
            } else {
                try {
                    val overlayView = OverlayView(
                        context = view.context,
                        rootId = view.uniqueDrawingId,
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
                    Log.w(SPAM_LOG_TAG, "OverlayView added")
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

private fun DrawInstruction.toOverlayViewInstruction() = OverlayViewInstruction(rootId, bounds.toAndroidRect())
private fun LayoutInspectorViewProtocol.Rect.toAndroidRect() = Rect(x, y, x + w, y + h)
