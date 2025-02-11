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

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.annotation.VisibleForTesting
import com.android.tools.agent.appinspection.SPAM_LOG_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@VisibleForTesting
const val SELECTION_COLOR = 0xFF1886F7.toInt()

/**
 * View responsible for drawing Layout Inspector overlay on-top the app's ui.
 * Each root view in the app contains a OverlayView that does the rendering.
 * Each [OverlayView] is controlled by the [OnDeviceRenderingViewModel].
 */
class OverlayView(
    context: Context,
    private val rootId: Long,
    private val scope: CoroutineScope,
    private val viewModel: OnDeviceRenderingViewModel
) : View(context) {
    private val paint = Paint().apply {
        color = SELECTION_COLOR
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(2f)
    }

    /** Rendering instruction for the selected rectangle. */
    private var selectedRect: Rect? = null

    /** Set to true when the view should prevent other views from receiving touch events. */
    private var interceptTouchEvents = false

    private var viewScope: CoroutineScope? = null

    override fun onAttachedToWindow() {
        Log.w(SPAM_LOG_TAG, "OverlayView onAttachedToWindow")
        super.onAttachedToWindow()

        viewScope = CoroutineScope(scope.coroutineContext + SupervisorJob()).apply {
            launch {
                viewModel.selectedNode.collect { drawInstructions ->
                    selectedRect = drawInstructions
                        ?.takeIf { it.rootId == rootId }
                        ?.bounds
                    Log.w(SPAM_LOG_TAG, "OverlayView selectedRectChanged: $selectedRect")
                    postInvalidate()
                }
            }

            launch {
                viewModel.interceptTouchEvents.collect { intercept ->
                    interceptTouchEvents = intercept
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        Log.w(SPAM_LOG_TAG, "OverlayView onDetachedFromWindow")
        super.onDetachedFromWindow()
        viewScope?.cancel()
        viewScope = null
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val point = PointF(ev.x, ev.y)
        Log.w(SPAM_LOG_TAG, "touch event: $point, OverlayView: $uniqueDrawingId")
        viewModel.onTouchEvent(point)
        return interceptTouchEvents || super.onTouchEvent(ev)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        selectedRect?.let { canvas.drawRect(it, paint) }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }
}
