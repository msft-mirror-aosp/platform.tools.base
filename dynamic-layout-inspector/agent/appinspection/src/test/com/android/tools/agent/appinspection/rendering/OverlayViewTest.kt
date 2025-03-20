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
import android.content.res.Resources
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.inspection.Connection
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OverlayViewTest {
    @Test
    fun testDrawsSelectedRect() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root = ViewGroup(context)
        val overlayView = OverlayView(root = root, scope = this, viewModel = viewModel)
        overlayView.onAttachedToWindow()
        testScheduler.advanceUntilIdle()

        overlayView.fakeCanvas.drawLogs.clear()

        val rootId = root.uniqueDrawingId
        val drawInstruction = buildDrawInstructionsProto(
            rootId = rootId,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 0x10101010.toInt()
        )
        viewModel.setSelectedNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.fakeCanvas.drawLogs).hasSize(1)
        assertThat(overlayView.fakeCanvas.drawLogs.first().rect).isEqualTo(Rect(0, 0, 2, 2))
        assertThat(overlayView.fakeCanvas.drawLogs.first().paint.color).isEqualTo(0x10101010.toInt())
    }

    @Test
    fun testDrawsHoveredRect() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root = ViewGroup(context)
        val overlayView = OverlayView(root = root, scope = this, viewModel = viewModel)
        overlayView.onAttachedToWindow()
        testScheduler.advanceUntilIdle()

        overlayView.fakeCanvas.drawLogs.clear()

        val rootId = root.uniqueDrawingId
        val drawInstruction = buildDrawInstructionsProto(
            rootId = rootId,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 0x10101010.toInt()
        )
        viewModel.setHoveredNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.fakeCanvas.drawLogs).hasSize(1)
        assertThat(overlayView.fakeCanvas.drawLogs.first().rect).isEqualTo(Rect(0, 0, 2, 2))
        assertThat(overlayView.fakeCanvas.drawLogs.first().paint.color).isEqualTo(0x10101010.toInt())
    }

    @Test
    fun testDrawsVisibleRect() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root = ViewGroup(context)
        val overlayView = OverlayView(root = root, scope = this, viewModel = viewModel)
        overlayView.onAttachedToWindow()
        testScheduler.advanceUntilIdle()

        overlayView.fakeCanvas.drawLogs.clear()

        val rootId = root.uniqueDrawingId
        val drawInstruction = buildDrawInstructionsProto(
            rootId = rootId,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 0x10101010.toInt()
        )
        viewModel.setVisibleNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.fakeCanvas.drawLogs).hasSize(1)
        assertThat(overlayView.fakeCanvas.drawLogs.first().rect).isEqualTo(Rect(0, 0, 2, 2))
        assertThat(overlayView.fakeCanvas.drawLogs.first().paint.color).isEqualTo(0x10101010.toInt())
    }

    @Test
    fun testDrawsRecomposingRect() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root = ViewGroup(context)
        val overlayView = OverlayView(root = root, scope = this, viewModel = viewModel)
        overlayView.onAttachedToWindow()
        testScheduler.advanceUntilIdle()

        overlayView.fakeCanvas.drawLogs.clear()

        val rootId = root.uniqueDrawingId
        val drawInstruction = buildDrawInstructionsProto(
            rootId = rootId,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 0x10101010.toInt()
        )
        viewModel.setRecomposingNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.fakeCanvas.drawLogs).hasSize(1)
        assertThat(overlayView.fakeCanvas.drawLogs.first().rect).isEqualTo(Rect(0, 0, 2, 2))
        assertThat(overlayView.fakeCanvas.drawLogs.first().paint.color).isEqualTo(0x40101010.toInt())
    }

    @Test
    fun testDoesNotDrawSelectedRectBelongingToOtherOverlayView() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root1 = ViewGroup(context)
        val overlayView1 = OverlayView(root = root1, scope = this, viewModel = viewModel)
        overlayView1.onAttachedToWindow()
        testScheduler.advanceUntilIdle()

        val root2 = ViewGroup(context)
        val overlayView2 = OverlayView(root = root2, scope = this, viewModel = viewModel)
        overlayView2.onAttachedToWindow()
        testScheduler.advanceUntilIdle()

        overlayView1.fakeCanvas.drawLogs.clear()

        // This draw instruction is meant for OverlayView belonging to root id 1.
        val rootId1 = root1.uniqueDrawingId
        val drawInstruction = buildDrawInstructionsProto(
            rootId = rootId1,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 0x10101010.toInt()
        )
        viewModel.setSelectedNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        // There are three events because listening to each selected, hovered and visible
        // triggers an invalidate.
        assertThat(overlayView1.fakeCanvas.drawLogs).hasSize(1)
        assertThat(overlayView1.fakeCanvas.drawLogs.first().rect).isEqualTo(Rect(0, 0, 2, 2))
        assertThat(overlayView1.fakeCanvas.drawLogs.first().paint.color).isEqualTo(0x10101010.toInt())

        assertThat(overlayView2.fakeCanvas.drawLogs).hasSize(0)
    }

    @Test
    fun testDoesNotDrawHoveredRectBelongingToOtherOverlayView() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root1 = ViewGroup(context)
        val overlayView1 = OverlayView(root = root1, scope = this, viewModel = viewModel)
        overlayView1.onAttachedToWindow()
        testScheduler.advanceUntilIdle()

        val root2 = ViewGroup(context)
        val overlayView2 = OverlayView(root = root2, scope = this, viewModel = viewModel)
        overlayView2.onAttachedToWindow()
        testScheduler.advanceUntilIdle()

        overlayView1.fakeCanvas.drawLogs.clear()

        // This draw instruction is meant for OverlayView belonging to root id 1.
        val rootId1 = root1.uniqueDrawingId
        val drawInstruction = buildDrawInstructionsProto(
            rootId = rootId1,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 0x10101010.toInt()
        )
        viewModel.setHoveredNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        // There are three events because listening to each selected, hovered and visible
        // triggers an invalidate.
        assertThat(overlayView1.fakeCanvas.drawLogs).hasSize(1)
        assertThat(overlayView1.fakeCanvas.drawLogs.first().rect).isEqualTo(Rect(0, 0, 2, 2))
        assertThat(overlayView1.fakeCanvas.drawLogs.first().paint.color).isEqualTo(0x10101010.toInt())

        assertThat(overlayView2.fakeCanvas.drawLogs).hasSize(0)
    }

    @Test
    fun testDoesNotDrawVisibleRectBelongingToOtherOverlayView() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root1 = ViewGroup(context)
        val overlayView1 = OverlayView(root = root1, scope = this, viewModel = viewModel)
        overlayView1.onAttachedToWindow()
        testScheduler.advanceUntilIdle()

        val root2 = ViewGroup(context)
        val overlayView2 = OverlayView(root = root2, scope = this, viewModel = viewModel)
        overlayView2.onAttachedToWindow()
        testScheduler.advanceUntilIdle()

        overlayView1.fakeCanvas.drawLogs.clear()

        // This draw instruction is meant for OverlayView belonging to root id 1.
        val rootId1 = root1.uniqueDrawingId
        val drawInstruction = buildDrawInstructionsProto(
            rootId = rootId1,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 0x10101010.toInt()
        )
        viewModel.setVisibleNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        // There are three events because listening to each selected, hovered and visible
        // triggers an invalidate.
        assertThat(overlayView1.fakeCanvas.drawLogs).hasSize(1)
        assertThat(overlayView1.fakeCanvas.drawLogs.first().rect).isEqualTo(Rect(0, 0, 2, 2))
        assertThat(overlayView1.fakeCanvas.drawLogs.first().paint.color).isEqualTo(0x10101010.toInt())

        assertThat(overlayView2.fakeCanvas.drawLogs).hasSize(0)
    }

    @Test
    fun testDoesNotDrawWhenNotAttachedToWindow() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root = ViewGroup(context)
        val overlayView = OverlayView(root = root, scope = this, viewModel = viewModel)
        overlayView.onAttachedToWindow()

        val rootId = root.uniqueDrawingId
        val drawInstruction1 = buildDrawInstructionsProto(
            rootId = rootId,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 0x10101010.toInt()
        )
        viewModel.setSelectedNodes(drawInstruction1)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.fakeCanvas.drawLogs).hasSize(4)

        overlayView.fakeCanvas.drawLogs.clear()
        overlayView.onDetachedFromWindow()
        testScheduler.advanceUntilIdle()

        val drawInstruction2 = buildDrawInstructionsProto(
            rootId = rootId,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 0x10101010.toInt()
        )
        viewModel.setSelectedNodes(drawInstruction2)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.fakeCanvas.drawLogs).hasSize(0)
    }

    @Test
    fun testTouchEvents() = runTest {
        val receivedEvents = mutableListOf<ByteArray>()
        val connection = object : Connection() {
            override fun sendEvent(data: ByteArray) {
                receivedEvents.add(data)
            }
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root = ViewGroup(context)
        val overlayView = OverlayView(root = root, scope = this, viewModel = viewModel)
        overlayView.onAttachedToWindow()

        assertThat(overlayView.onTouchEvent(MotionEvent(1f, 1f))).isFalse()

        viewModel.setInterceptTouchEvents(true)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.onTouchEvent(MotionEvent(2f, 2f))).isTrue()

        viewModel.setInterceptTouchEvents(false)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.onTouchEvent(MotionEvent(3f, 3f))).isFalse()

        val rootId = root.uniqueDrawingId
        val expectedSelectionEvent = buildUserInputEventProto(
            rootId = rootId, x = 2f, y = 2f, type = LayoutInspectorViewProtocol.UserInputEvent.Type.SELECTION
        ).toByteArray()
        assertThat(receivedEvents).hasSize(1)
        assertThat(receivedEvents.first()).isEqualTo(expectedSelectionEvent)
    }

    @Test
    fun testTouchEventsRightClick() = runTest {
        val receivedEvents = mutableListOf<ByteArray>()
        val connection = object : Connection() {
            override fun sendEvent(data: ByteArray) {
                receivedEvents.add(data)
            }
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root = ViewGroup(context)
        val overlayView = OverlayView(root = root, scope = this, viewModel = viewModel)
        overlayView.onAttachedToWindow()

        assertThat(overlayView.onTouchEvent(rightClick(1f, 1f))).isFalse()

        viewModel.setInterceptTouchEvents(true)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.onTouchEvent(rightClick(2f, 2f))).isTrue()

        viewModel.setInterceptTouchEvents(false)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.onTouchEvent(rightClick(3f, 3f))).isFalse()

        val rootId = root.uniqueDrawingId
        val expectedSelectionEvent = buildUserInputEventProto(
            rootId = rootId, x = 2f, y = 2f, type = LayoutInspectorViewProtocol.UserInputEvent.Type.SELECTION
        ).toByteArray()
        val expectedRightClickEvent = buildUserInputEventProto(
            rootId = rootId, x = 2f, y = 2f, type = LayoutInspectorViewProtocol.UserInputEvent.Type.RIGHT_CLICK
        ).toByteArray()
        assertThat(receivedEvents).hasSize(2)
        assertThat(receivedEvents[0]).isEqualTo(expectedRightClickEvent)
        assertThat(receivedEvents[1]).isEqualTo(expectedSelectionEvent)
    }

    @Test
    fun testHoverEvents() = runTest {
        val receivedEvents = mutableListOf<ByteArray>()
        val connection = object : Connection() {
            override fun sendEvent(data: ByteArray) {
                receivedEvents.add(data)
            }
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root = ViewGroup(context)
        val overlayView = OverlayView(root = root, scope = this, viewModel = viewModel)
        overlayView.onAttachedToWindow()

        assertThat(overlayView.onHoverEvent(MotionEvent(1f, 1f))).isFalse()

        viewModel.setInterceptTouchEvents(true)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.onHoverEvent(MotionEvent(2f, 2f))).isTrue()

        viewModel.setInterceptTouchEvents(false)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.onHoverEvent(MotionEvent(3f, 3f))).isFalse()

        val rootId = root.uniqueDrawingId
        val expectedHoverEvent = buildUserInputEventProto(
            rootId = rootId, x = 2f, y = 2f, type = LayoutInspectorViewProtocol.UserInputEvent.Type.HOVER
        ).toByteArray()
        assertThat(receivedEvents).hasSize(1)
        assertThat(receivedEvents.first()).isEqualTo(expectedHoverEvent)
    }

    @Test
    fun testMeasuredSize() = runTest {
        val connection = object : Connection() {}
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)
        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val root = ViewGroup(context)
        root.setPadding(10, 5, 10, 20)
        val view1 = View(context).apply {
            left = 25
            top = 25
            width = 300
            height = 100
        }
        val view2 = View(context).apply {
            left = 25
            top = 15
            width = 100
            height = 200
        }
        root.addView(view1)
        root.addView(view2)
        val overlayView = OverlayView(root = root, scope = this, viewModel = viewModel)
        root.addView(overlayView)
        root.measure(1000, 2000)
        assertThat(overlayView.measuredWidth).isEqualTo(315)
        assertThat(overlayView.measuredHeight).isEqualTo(210)
    }
}

private fun rightClick(x: Float, y: Float): MotionEvent {
    val action = MotionEvent.ACTION_DOWN
    val buttonState = MotionEvent.BUTTON_SECONDARY
    return MotionEvent(x, y, action, buttonState)
}
