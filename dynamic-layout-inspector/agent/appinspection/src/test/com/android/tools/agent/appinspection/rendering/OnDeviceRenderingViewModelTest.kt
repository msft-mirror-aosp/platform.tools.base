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
import android.graphics.PointF
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.inspection.Connection
import com.android.tools.agent.appinspection.InspectorView
import com.android.tools.agent.appinspection.framework.getChildren
import com.android.tools.idea.layoutinspector.view.inspection.LayoutInspectorViewProtocol
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class OnDeviceRenderingViewModelTest {
    @Test
    fun testAddingNewRootsAddsOverlayView() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)
        onDeviceRenderingViewModel.setEnableOnDeviceRendering(true)
        testScheduler.advanceUntilIdle()

        val inspectorView1 = createRootInspectorView()
        onDeviceRenderingViewModel.setRoots(mapOf(1L to inspectorView1))
        testScheduler.advanceUntilIdle()

        assertContainsOverlayView(inspectorView1)

        val inspectorView2 = createRootInspectorView()
        onDeviceRenderingViewModel.setRoots(mapOf(1L to inspectorView1, 2L to inspectorView2))
        testScheduler.advanceUntilIdle()

        assertContainsOverlayView(inspectorView1)
        assertContainsOverlayView(inspectorView2)
    }

    @Test
    fun testRemovingRootsRemovesOverlayView() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)
        onDeviceRenderingViewModel.setEnableOnDeviceRendering(true)
        testScheduler.advanceUntilIdle()

        val inspectorView1 = createRootInspectorView()
        onDeviceRenderingViewModel.setRoots(mapOf(1L to inspectorView1))
        testScheduler.advanceUntilIdle()

        assertContainsOverlayView(inspectorView1)

        val inspectorView2 = createRootInspectorView()
        onDeviceRenderingViewModel.setRoots(mapOf(1L to inspectorView1, 2L to inspectorView2))
        testScheduler.advanceUntilIdle()

        assertContainsOverlayView(inspectorView1)
        assertContainsOverlayView(inspectorView2)

        onDeviceRenderingViewModel.setRoots(mapOf(1L to inspectorView1))
        testScheduler.advanceUntilIdle()

        assertContainsOverlayView(inspectorView1)
        assertDoesNotContainsOverlayView(inspectorView2)
    }

    @Test
    fun testSetSelectedNodeAddOverlayView() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)
        onDeviceRenderingViewModel.setEnableOnDeviceRendering(true)
        testScheduler.advanceUntilIdle()

        val inspectorView1 = createRootInspectorView()
        onDeviceRenderingViewModel.setRoots(mapOf(1L to inspectorView1))
        testScheduler.advanceUntilIdle()

        assertContainsOverlayView(inspectorView1)

        val inspectorView2 = createRootInspectorView()
        onDeviceRenderingViewModel.setRoots(mapOf(1L to inspectorView1, 2L to inspectorView2))
        testScheduler.advanceUntilIdle()

        assertContainsOverlayView(inspectorView1)
        assertContainsOverlayView(inspectorView2)

        onDeviceRenderingViewModel.setRoots(mapOf(1L to inspectorView1))
        testScheduler.advanceUntilIdle()

        assertContainsOverlayView(inspectorView1)
        assertDoesNotContainsOverlayView(inspectorView2)

        // Manually remove the OverlayView.
        (inspectorView1.view as ViewGroup).getChildren().forEach {
            (inspectorView1.view as ViewGroup).removeView(it)
        }

        val drawInstruction = buildDrawInstructionsProto(
            rootId = 1L,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 1,
            label = "label"
        )
        // Calling set selected nods should add back the OverlayView.
        onDeviceRenderingViewModel.setSelectedNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        assertContainsOverlayView(inspectorView1)
    }

    @Test
    fun testDisablingOnDeviceRenderingRemovesOverlayViews() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)
        onDeviceRenderingViewModel.setEnableOnDeviceRendering(true)
        testScheduler.advanceUntilIdle()

        val inspectorView1 = createRootInspectorView()
        val inspectorView2 = createRootInspectorView()
        onDeviceRenderingViewModel.setRoots(mapOf(1L to inspectorView1, 2L to inspectorView2))
        testScheduler.advanceUntilIdle()

        assertContainsOverlayView(inspectorView1)
        assertContainsOverlayView(inspectorView2)

        onDeviceRenderingViewModel.setEnableOnDeviceRendering(false)
        testScheduler.advanceUntilIdle()

        assertDoesNotContainsOverlayView(inspectorView1)
        assertDoesNotContainsOverlayView(inspectorView2)
    }

    @Test
    fun testDisposeDisablesOnDeviceRendering() = runTest {
        val scope = CoroutineScope(Job())
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(scope, connection, testDispatcher)
        onDeviceRenderingViewModel.setEnableOnDeviceRendering(true)
        testScheduler.advanceUntilIdle()

        val inspectorView1 = createRootInspectorView()
        onDeviceRenderingViewModel.setRoots(mapOf(1L to inspectorView1))
        testScheduler.advanceUntilIdle()

        onDeviceRenderingViewModel.dispose()
        testScheduler.advanceUntilIdle()

        assertThat(onDeviceRenderingViewModel.enableOnDeviceRendering).isFalse()
        assertThat(onDeviceRenderingViewModel.roots).isEmpty()
    }

    @Test
    fun testInterceptTouchEvents() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val interceptTouchEventsValues = mutableListOf<Boolean>()
        val job = launch {
            onDeviceRenderingViewModel.interceptTouchEvents.collect {
                interceptTouchEventsValues.add(it)
            }
        }

        onDeviceRenderingViewModel.setInterceptTouchEvents(true)
        testScheduler.advanceUntilIdle()

        onDeviceRenderingViewModel.setInterceptTouchEvents(false)
        testScheduler.advanceUntilIdle()

        job.cancelAndJoin()

        assertThat(interceptTouchEventsValues).containsExactly(true, false)
    }

    @Test
    fun testSetSelectedNodes() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val instructions = mutableListOf<List<OverlayViewInstruction>>()
        val job = launch {
            onDeviceRenderingViewModel.selectedNodes.collect {
                instructions.add(it)
            }
        }

        val drawInstruction = buildDrawInstructionsProto(
            rootId = 1L,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 1,
            label = "label"
        )
        onDeviceRenderingViewModel.setSelectedNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        onDeviceRenderingViewModel.setSelectedNodes(emptyList())
        testScheduler.advanceUntilIdle()

        job.cancelAndJoin()

        assertThat(instructions).hasSize(2)
        assertThat(instructions[0]).isEqualTo(
            listOf(OverlayViewInstruction(rootId = 1L, bounds = Rect(0, 0, 2, 2), color = 1, label = "label"))
        )
        assertThat(instructions[1]).isEmpty()
    }

    @Test
    fun testSetHoveredNodes() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val instructions = mutableListOf<List<OverlayViewInstruction>>()
        val job = launch {
            onDeviceRenderingViewModel.hoveredNodes.collect {
                instructions.add(it)
            }
        }

        val drawInstruction = buildDrawInstructionsProto(
            rootId = 1L,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 1
        )
        onDeviceRenderingViewModel.setHoveredNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        onDeviceRenderingViewModel.setHoveredNodes(emptyList())
        testScheduler.advanceUntilIdle()

        job.cancelAndJoin()

        assertThat(instructions).hasSize(2)
        assertThat(instructions[0]).isEqualTo(
            listOf(OverlayViewInstruction(rootId = 1L, bounds = Rect(0, 0, 2, 2), color = 1, label = null))
        )
        assertThat(instructions[1]).isEmpty()
    }

    @Test
    fun testSetVisibleNodes() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val instructions = mutableListOf<List<OverlayViewInstruction>>()
        val job = launch {
            onDeviceRenderingViewModel.visibleNodes.collect {
                instructions.add(it)
            }
        }

        val drawInstruction = buildDrawInstructionsProto(
            rootId = 1L,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 1
        )
        onDeviceRenderingViewModel.setVisibleNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        onDeviceRenderingViewModel.setVisibleNodes(emptyList())
        testScheduler.advanceUntilIdle()

        job.cancelAndJoin()

        assertThat(instructions).hasSize(2)
        assertThat(instructions[0]).isEqualTo(
            listOf(OverlayViewInstruction(rootId = 1L, bounds = Rect(0, 0, 2, 2), color = 1, label = null))
        )
        assertThat(instructions[1]).isEmpty()
    }

    @Test
    fun testSetRecomposingNodes() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val instructions = mutableListOf<List<OverlayViewInstruction>>()
        val job = launch {
            onDeviceRenderingViewModel.recomposingNodes.collect {
                instructions.add(it)
            }
        }

        val drawInstruction = buildDrawInstructionsProto(
            rootId = 1L,
            bounds = listOf(Rect(0, 0, 2, 2)),
            color = 1
        )
        onDeviceRenderingViewModel.setRecomposingNodes(drawInstruction)
        testScheduler.advanceUntilIdle()

        onDeviceRenderingViewModel.setRecomposingNodes(emptyList())
        testScheduler.advanceUntilIdle()

        job.cancelAndJoin()

        assertThat(instructions).hasSize(2)
        assertThat(instructions[0]).isEqualTo(
            listOf(OverlayViewInstruction(rootId = 1L, bounds = Rect(0, 0, 2, 2), color = 1, label = null))
        )
        assertThat(instructions[1]).isEmpty()
    }

    @Test
    fun testOnTouchEvent() = runTest {
        val expectedEvent = buildUserInputEventProto(
            rootId = 1L, x = 1f, y = 1f, LayoutInspectorViewProtocol.UserInputEvent.Type.SELECTION
        ).toByteArray()

        val receivedEvents = mutableListOf<ByteArray>()
        val connection = object : Connection() {
            override fun sendEvent(data: ByteArray) {
                receivedEvents.add(data)
            }
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)
        onDeviceRenderingViewModel.setInterceptTouchEvents(true)

        onDeviceRenderingViewModel.onTouchEvent(rootId = 1L, point = PointF(1f, 1f))

        assertThat(receivedEvents).hasSize(1)
        assertThat(receivedEvents.first()).isEqualTo(expectedEvent)
    }

    @Test
    fun testOnHoverEvent() = runTest {
        val expectedEvent = buildUserInputEventProto(
            rootId = 1L, x = 1f, y = 1f, type = LayoutInspectorViewProtocol.UserInputEvent.Type.HOVER
        ).toByteArray()

        val receivedEvents = mutableListOf<ByteArray>()
        val connection = object : Connection() {
            override fun sendEvent(data: ByteArray) {
                receivedEvents.add(data)
            }
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)
        onDeviceRenderingViewModel.setInterceptTouchEvents(true)

        onDeviceRenderingViewModel.onHoverEvent(rootId = 1L, point = PointF(1f, 1f))

        assertThat(receivedEvents).hasSize(1)
        assertThat(receivedEvents.first()).isEqualTo(expectedEvent)
    }

    @Test
    fun testOnRightClickEvent() = runTest {
        val expectedEvent = buildUserInputEventProto(
            rootId = 1L, x = 1f, y = 1f, type = LayoutInspectorViewProtocol.UserInputEvent.Type.RIGHT_CLICK
        ).toByteArray()

        val receivedEvents = mutableListOf<ByteArray>()
        val connection = object : Connection() {
            override fun sendEvent(data: ByteArray) {
                receivedEvents.add(data)
            }
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)
        onDeviceRenderingViewModel.setInterceptTouchEvents(true)

        onDeviceRenderingViewModel.onRightClick(rootId = 1L, point = PointF(1f, 1f))

        assertThat(receivedEvents).hasSize(1)
        assertThat(receivedEvents.first()).isEqualTo(expectedEvent)
    }

    @Test
    fun testOnTouchEventNotDispatchedWhenDisabled() = runTest {
        val receivedEvents = mutableListOf<ByteArray>()
        val connection = object : Connection() {
            override fun sendEvent(data: ByteArray) {
                receivedEvents.add(data)
            }
        }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val onDeviceRenderingViewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)
        onDeviceRenderingViewModel.setInterceptTouchEvents(false)

        onDeviceRenderingViewModel.onTouchEvent(rootId = 1L, point = PointF(1f, 1f))

        assertThat(receivedEvents).isEmpty()
    }
}

private fun assertContainsOverlayView(inspectorView: InspectorView) {
    val rootView = inspectorView.view as ViewGroup
    val lastChild = rootView.getChildAt(rootView.childCount - 1)
    assertThat(lastChild).isInstanceOf(OverlayView::class.java)
}

private fun assertDoesNotContainsOverlayView(inspectorView: InspectorView) {
    val rootView = inspectorView.view as ViewGroup
    val lastChild = rootView.getChildAt(rootView.childCount - 1)
    assertThat(lastChild).isNotInstanceOf(OverlayView::class.java)
}

private fun createRootInspectorView(): InspectorView {
    val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
    val rootView = ViewGroup(context)
    val childView = View(context)
    rootView.addView(childView)

    return InspectorView(view = rootView, isXr = false)
}
