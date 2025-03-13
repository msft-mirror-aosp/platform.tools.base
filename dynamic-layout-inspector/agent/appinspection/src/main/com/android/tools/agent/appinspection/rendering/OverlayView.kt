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
import kotlinx.coroutines.launch

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

    /**
     * Drawing instructions for an [OverlayView].
     * @param rect The rect to be drawn.
     * @param color The color used to draw the [rect], in ARGB format.
     */
    private class DrawInstruction(val rect: Rect, val color: Int)

    private val selectedRectPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(4f)
    }
    private val hoveredRectPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(4f)
    }
    private val visibleRectPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = dpToPx(1f)
    }
    private val recomposingRectPaint = Paint().apply {
        style = Paint.Style.FILL
    }

    /** Rendering instruction for the selected rectangles. */
    private var selectedRectangles: List<DrawInstruction> = emptyList()

    /** Rendering instruction for the hovered rectangles. */
    private var hoveredRectangle: List<DrawInstruction> = emptyList()

    /**
     * Rendering instruction for the visible rectangles,
     * which include selected and hovered rectangles.
     */
    private var visibleRectangles: List<DrawInstruction> = emptyList()

    /** Rendering instructions for the recomposition highlights. */
    private var recomposingRectangles: List<DrawInstruction> = emptyList()

    /** Set to true when the view should prevent other views from receiving touch events. */
    private var interceptTouchEvents = false

    private var viewScope: CoroutineScope? = null

    override fun onAttachedToWindow() {
        Log.w(SPAM_LOG_TAG, "OverlayView $rootId onAttachedToWindow")
        super.onAttachedToWindow()

        viewScope = CoroutineScope(scope.coroutineContext + SupervisorJob()).apply {
            launch {
                viewModel.selectedNodes.collect { drawInstructions ->
                    selectedRectangles = drawInstructions.mapToDrawInstructions(rootId)
                    Log.w(SPAM_LOG_TAG, "OverlayView $rootId selectedRectangles changed: $selectedRectangles")
                    postInvalidate()
                }
            }

            launch {
                viewModel.hoveredNodes.collect { drawInstructions ->
                    hoveredRectangle = drawInstructions.mapToDrawInstructions(rootId)
                    Log.w(SPAM_LOG_TAG, "OverlayView $rootId hoveredRectangle changed: $hoveredRectangle")
                    postInvalidate()
                }
            }

            launch {
                viewModel.visibleNodes.collect { drawInstructions ->
                    visibleRectangles = drawInstructions.mapToDrawInstructions(rootId)
                    Log.w(SPAM_LOG_TAG, "OverlayView $rootId visibleRectangles changed: $visibleRectangles")
                    postInvalidate()
                }
            }

            launch {
                viewModel.recomposingNodes.collect { drawInstructions ->
                    recomposingRectangles = drawInstructions.mapToDrawInstructions(rootId)
                    Log.w(SPAM_LOG_TAG, "OverlayView $rootId recomposingRectangles changed: $recomposingRectangles")
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
        Log.w(SPAM_LOG_TAG, "OverlayView $rootId onDetachedFromWindow")
        super.onDetachedFromWindow()
        viewScope?.cancel()
        viewScope = null
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val point = PointF(ev.x, ev.y)
        Log.w(SPAM_LOG_TAG, "OverlayView $rootId onTouchEvent: $point")

        if (ev.action == MotionEvent.ACTION_DOWN && ev.buttonState == MotionEvent.BUTTON_SECONDARY) {
            viewModel.onRightClick(rootId, point)
            Log.w(SPAM_LOG_TAG, "OverlayView $rootId right click")
        }

        // Always select the view.
        viewModel.onTouchEvent(rootId, point)

        return interceptTouchEvents || super.onTouchEvent(ev)
    }

    override fun onHoverEvent(ev: MotionEvent): Boolean {
        val point = PointF(ev.x, ev.y)
        Log.w(SPAM_LOG_TAG, "OverlayView $rootId hover event: $point")
        viewModel.onHoverEvent(rootId, point)
        return interceptTouchEvents || super.onHoverEvent(ev)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // The rendering order matters.
        recomposingRectangles.forEach {
            recomposingRectPaint.color = it.color.setColorAlpha(64)
            canvas.drawRect(it.rect, recomposingRectPaint)
        }
        visibleRectangles.forEach {
            visibleRectPaint.color = it.color
            canvas.drawRect(it.rect, visibleRectPaint)
        }
        hoveredRectangle.forEach {
            hoveredRectPaint.color = it.color
            canvas.drawRect(it.rect, hoveredRectPaint)
        }
        selectedRectangles.forEach {
            selectedRectPaint.color = it.color
            canvas.drawRect(it.rect, selectedRectPaint)
        }
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            resources.displayMetrics
        )
    }

    /** Map each [OverlayViewInstruction] to a [Rect] to be rendered in the provided [ownerRootId]. */
    private fun List<OverlayViewInstruction>.mapToDrawInstructions(ownerRootId: Long): List<DrawInstruction> {
        return filter { it.rootId == ownerRootId }.map { DrawInstruction(it.bounds, it.color) }
    }

    /** Set the alpha for the int representation of a color. */
    private fun Int.setColorAlpha(alpha: Int): Int {
        val validAlpha = alpha.coerceIn(0, 255)
        return (validAlpha shl 24) or (this and 0x00FFFFFF)
    }
}
