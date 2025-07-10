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

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import android.util.Log
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.annotation.VisibleForTesting
import com.android.tools.agent.appinspection.SPAM_LOG_TAG
import com.android.tools.agent.appinspection.framework.measureSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch


@VisibleForTesting
const val DOUBLE_TAP_TIMEOUT_MS = 300L

/**
 * View responsible for drawing Layout Inspector overlay on-top the app's ui.
 * Each root view in the app contains a OverlayView that does the rendering.
 * Each [OverlayView] is controlled by the [OnDeviceRenderingViewModel].
 */
class OverlayView(
    private val root: ViewGroup,
    private val scope: CoroutineScope,
    private val viewModel: OnDeviceRenderingViewModel,
    private val timeProviderMs: () -> Long = { System.currentTimeMillis() }
) : View(root.context) {
    /**
     * Drawing instructions for an [OverlayView].
     * @param rect The rect to be drawn.
     * @param color The color used to draw the [rect], in ARGB format.
     */
    private class DrawInstruction(val rect: Rect, val color: Int, val label: String?)

    private val rootId = root.uniqueDrawingId
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
    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dpToPx(20f)
    }

    /** Holds the screen coordinates of this [OverlayView]. Updated in onLayout. */
    private val currentScreenLocation = intArrayOf(0, 0)

    /**
     * Temporary [Rect] used to convert a [DrawInstruction.rect] from screen to view
     * coordinates, without allocating a new [Rect] instance each time.
     */
    private val rectForDrawing = Rect(0, 0, 0, 0)

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

    /** The time of the last touch event received */
    private var previousTouchTimeMs: Long = 0

    private var viewScope: CoroutineScope? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // This is important for dialogs and popups that occupy a smaller part of the display.
        // Without this the parent DecorView may get larger after this OverlayView is added.
        val size = root.measureSize(this)
        setMeasuredDimension(size.width, size.height)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Store the current screen location of this [OverlayView] for coordinate conversions.
        getLocationOnScreen(currentScreenLocation)
    }

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
        val point = ev.toScreenCoordinates()
        Log.w(SPAM_LOG_TAG, "OverlayView $rootId onTouchEvent: $point, interceptTouchEvents: $interceptTouchEvents")

        if (ev.action == MotionEvent.ACTION_DOWN) {
            val currentTouchTimeMs = timeProviderMs()
            if (ev.buttonState == MotionEvent.BUTTON_SECONDARY) {
                viewModel.onRightClick(rootId, point)
                Log.w(SPAM_LOG_TAG, "OverlayView $rootId right click")
            }
            else if (currentTouchTimeMs - previousTouchTimeMs < DOUBLE_TAP_TIMEOUT_MS) {
                viewModel.onDoubleClick(rootId, point)
                Log.w(SPAM_LOG_TAG, "OverlayView $rootId double click")
            }
            else {
                viewModel.onTouchEvent(rootId, point)
                Log.w(SPAM_LOG_TAG, "OverlayView $rootId click")
            }
            previousTouchTimeMs = currentTouchTimeMs
        }

        return interceptTouchEvents || super.onTouchEvent(ev)
    }

    override fun onHoverEvent(ev: MotionEvent): Boolean {
        val point = ev.toScreenCoordinates()
        Log.w(SPAM_LOG_TAG, "OverlayView $rootId hover event: $point, interceptTouchEvents: $interceptTouchEvents")
        viewModel.onHoverEvent(rootId, point)
        return interceptTouchEvents || super.onHoverEvent(ev)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // The rendering order matters.
        recomposingRectangles.forEach { it.paint(canvas, recomposingRectPaint) }
        visibleRectangles.forEach { it.paint(canvas, visibleRectPaint) }
        hoveredRectangle.forEach { it.paint(canvas, hoveredRectPaint) }
        selectedRectangles.forEach { it.paint(canvas, selectedRectPaint) }
    }

    private fun DrawInstruction.paint(canvas: Canvas, paint: Paint) {
        paint.color = color
        val bounds = rect.toViewCoordinates()
        canvas.drawRect(bounds, paint)
        if (label != null) {
            drawLabel(
                text = label,
                nodeBounds = bounds,
                backgroundPaint = paint,
                textPaint = textPaint,
                canvas = canvas
            )
        }
    }

    private fun drawLabel(text: String, nodeBounds: Rect, backgroundPaint: Paint, textPaint: Paint, canvas: Canvas) {
        if (
            nodeBounds.bottom < 0 && nodeBounds.top < 0 ||
            nodeBounds.left < 0 && nodeBounds.right < 0 ||
            nodeBounds.bottom > canvas.height  && nodeBounds.top > canvas.height ||
            nodeBounds.left > canvas.width  && nodeBounds.right > canvas.width
            ) {
            // The bounds are not visible on the screen, don't render the label.
            return
        }

        val fontMetrics = textPaint.fontMetrics
        val horizontalPadding = dpToPx(4f)
        val textWidth = textPaint.measureText(text)
        val textHeight = fontMetrics.bottom - fontMetrics.top
        val strokeWidth = backgroundPaint.strokeWidth
        val canvasWidth = canvas.width

        var labelBottom = nodeBounds.top.toFloat()
        var labelLeft = nodeBounds.left.toFloat() - (strokeWidth / 2f)
        var labelTop = labelBottom - textHeight

        val totalWidth = textWidth + 2 * horizontalPadding
        var labelRight = labelLeft + totalWidth

        // If the text goes above the top edge of the canvas, move it down so it fits.
        if (labelTop < 0) {
            labelTop = 0f
            labelBottom = labelTop + textHeight
        }
        // If it extends beyond the left edge of the canvas, move it right so it fits.
        if (labelLeft < 0) {
            labelLeft = 0f
            labelRight = labelLeft + totalWidth
        }
        // If it extends beyond the right edge of the canvas, move it left so it fits.
        if (labelRight > canvasWidth) {
            labelRight = canvasWidth.toFloat()
            labelLeft = labelRight - totalWidth
        }

        // The background rectangle for the label.
        val labelBackgroundRect = Rect(labelLeft.toInt(), labelTop.toInt(), labelRight.toInt(), labelBottom.toInt())
        val backgroundPaint = Paint().apply {
            style = Paint.Style.FILL
            color = backgroundPaint.color
        }
        canvas.drawRect(labelBackgroundRect, backgroundPaint)

        val textBaseline = labelBottom - fontMetrics.bottom
        val textX = labelLeft + horizontalPadding
        canvas.drawText(text, textX, textBaseline, textPaint)
    }

    /** Convert the [MotionEvent] coordinates from view to screen coordinates. */
    private fun MotionEvent.toScreenCoordinates(): PointF {
        return PointF(x + currentScreenLocation[0], y + currentScreenLocation[1])
    }

    /**
     * Convert the [Rect] from screen to view coordinates without allocating anything.
     */
    private fun Rect.toViewCoordinates(): Rect {
        rectForDrawing.set(this)
        rectForDrawing.offset(-currentScreenLocation[0], -currentScreenLocation[1])
        return rectForDrawing
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
        return filter { it.rootId == ownerRootId }.map { DrawInstruction(it.bounds, it.color, it.label) }
    }
}
