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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.android.tools.idea.protobuf.ByteString
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Label(val text: String, val size: Float)

/**
 * Defines a draw instruction for an [OverlayView].
 * @param rootId The id of the root view containing the [OverlayView] that needs to do the drawing.
 * @param bounds The rectangle to be rendered.
 * @param color The color to be used for this draw instruction.
 * @param strokeThickness The thickness to be used to render the bounds stroke
 */
data class OverlayViewInstruction(
    val rootId: Long,
    val bounds: Rect,
    val color: Int,
    val strokeThickness: Float,
    val label: Label?
)

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

    private val _overlayImage = MutableStateFlow<Bitmap?>(null)
    var overlayImage = _overlayImage.asStateFlow()

    private val _overlayAlpha = MutableStateFlow<Float>(0.5f)
    var overlayAlpha = _overlayAlpha.asStateFlow()

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
        addOverlayViewsIfMissing()
        _selectedNodes.value = instruction.map { it.toOverlayViewInstruction() }
    }

    fun setHoveredNodes(instruction: List<DrawInstruction>) {
        addOverlayViewsIfMissing()
        _hoveredNodes.value = instruction.map { it.toOverlayViewInstruction() }
    }

    fun setVisibleNodes(instructions: List<DrawInstruction>) {
        addOverlayViewsIfMissing()
        _visibleNodes.value = instructions.map { it.toOverlayViewInstruction() }
    }

    fun setRecomposingNodes(instructions: List<DrawInstruction>) {
        addOverlayViewsIfMissing()
        _recomposingNodes.value = instructions.map { it.toOverlayViewInstruction() }
    }

    fun setInterceptTouchEvents(intercept: Boolean) {
        addOverlayViewsIfMissing()
        _interceptTouchEvents.value = intercept
    }

    fun setOverlayImage(image: ByteString?) {
        val byteArray = image?.toByteArray()
        val bitmap = byteArray?.let { BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size) }
        _overlayImage.value = bitmap
    }

    fun setOverlayAlpha(alpha: Float) {
        _overlayAlpha.value = alpha
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

    fun onDoubleClick(rootId: Long, point: PointF) {
        sendInputEvent(rootId, point, UserInputEvent.Type.DOUBLE_CLICK)
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

    /**
     * A root view can be missing its OverlayView if the root view children were all removed,
     * but the root view itself was not. This can happen in some cases, for example if the
     * Activity is relaunched, see b/402729631.
     *
     * This is a utility method to make sure the OverlayView is actually present, each time
     * LayoutInspector tries to interact with it.
     */
    private fun addOverlayViewsIfMissing() {
        scope.launch { roots.values.forEach { inspectorView -> addOverlayView(inspectorView) } }
    }
}

/**
 * Sizes used for studio-side rendering look small in on-device rendering.
 * This scale factor is used to adjust them.
 */
private const val SIZE_SCALE_FACTOR = 2

private fun DrawInstruction.toOverlayViewInstruction(): OverlayViewInstruction {
    return OverlayViewInstruction(
        rootId = rootId,
        bounds = bounds.toAndroidRect(),
        color = color,
        strokeThickness = strokeThickness * SIZE_SCALE_FACTOR,
        label = labelOrNull()
    )
}

private fun DrawInstruction.labelOrNull(): Label? {
    return if (hasLabel()) {
        Label(label.text, label.size * SIZE_SCALE_FACTOR)
    }
    else {
        null
    }
}

private fun LayoutInspectorViewProtocol.Rect.toAndroidRect() = Rect(x, y, x + w, y + h)
