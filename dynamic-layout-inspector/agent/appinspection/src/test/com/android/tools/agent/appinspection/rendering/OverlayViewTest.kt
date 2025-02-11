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
import androidx.inspection.Connection
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
        val overlayView = OverlayView(context = context, rootId = 1L, scope = this, viewModel = viewModel)
        overlayView.onAttachedToWindow()

        val drawInstruction = buildDrawInstructions(rootId = 1L, bounds = Rect(0, 0, 2, 2))
        viewModel.setSelectedNode(drawInstruction)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.fakeCanvas.drawLogs).hasSize(1)
        assertThat(overlayView.fakeCanvas.drawLogs.first().rect).isEqualTo(Rect(0, 0, 2, 2))
        assertThat(overlayView.fakeCanvas.drawLogs.first().paint.color).isEqualTo(SELECTION_COLOR)
    }

    @Test
    fun testDoesNotDrawSelectedRectBelongingToOtherOverlayView() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val overlayView1 = OverlayView(context = context, rootId = 1L, scope = this, viewModel = viewModel)
        overlayView1.onAttachedToWindow()

        val overlayView2 = OverlayView(context = context, rootId = 2L, scope = this, viewModel = viewModel)
        overlayView2.onAttachedToWindow()

        // This draw instruction is meant for OverlayView belonging to root id 1.
        val drawInstruction = buildDrawInstructions(rootId = 1L, bounds = Rect(0, 0, 2, 2))
        viewModel.setSelectedNode(drawInstruction)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView1.fakeCanvas.drawLogs).hasSize(1)
        assertThat(overlayView1.fakeCanvas.drawLogs.first().rect).isEqualTo(Rect(0, 0, 2, 2))
        assertThat(overlayView1.fakeCanvas.drawLogs.first().paint.color).isEqualTo(SELECTION_COLOR)

        assertThat(overlayView2.fakeCanvas.drawLogs).hasSize(0)
    }

    @Test
    fun testDoesNotDrawWhenNotAttachedToWindow() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val overlayView = OverlayView(context = context, rootId = 1L, scope = this, viewModel = viewModel)
        overlayView.onAttachedToWindow()

        val drawInstruction1 = buildDrawInstructions(rootId = 1L, bounds = Rect(0, 0, 2, 2))
        viewModel.setSelectedNode(drawInstruction1)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.fakeCanvas.drawLogs).hasSize(1)

        overlayView.fakeCanvas.drawLogs.clear()
        overlayView.onDetachedFromWindow()
        testScheduler.advanceUntilIdle()

        val drawInstruction2 = buildDrawInstructions(rootId = 1L, bounds = Rect(0, 0, 2, 2))
        viewModel.setSelectedNode(drawInstruction2)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.fakeCanvas.drawLogs).hasSize(0)
    }

    @Test
    fun testTouchEvents() = runTest {
        val connection = object : Connection() { }

        val testDispatcher = StandardTestDispatcher(testScheduler)
        val viewModel = OnDeviceRenderingViewModel(this, connection, testDispatcher)

        val context = Context("fake.package.name", Resources(emptyMap<Int, String>()))
        val overlayView = OverlayView(context = context, rootId = 1L, scope = this, viewModel = viewModel)
        overlayView.onAttachedToWindow()

        assertThat(overlayView.onTouchEvent(MotionEvent())).isFalse()

        viewModel.setInterceptTouchEvents(true)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.onTouchEvent(MotionEvent())).isTrue()

        viewModel.setInterceptTouchEvents(false)
        testScheduler.advanceUntilIdle()

        assertThat(overlayView.onTouchEvent(MotionEvent())).isFalse()
    }
}
