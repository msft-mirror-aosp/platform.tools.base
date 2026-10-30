/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.tools.studio.leakcanary;

import static com.google.common.truth.Truth.assertThat;

import static leakcanary.LeakCanary.Config.LEAK_CANARY_LISTENER_1;
import static leakcanary.LeakCanary.Config.LEAK_CANARY_LISTENER_2;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.HandlerThread;

import leakcanary.AppWatcher;
import leakcanary.EventListener;
import leakcanary.EventListener.Event.DumpingHeap;
import leakcanary.EventListener.Event.HeapAnalysisProgress;
import leakcanary.LeakCanary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests that the analysis progress is sent to Studio in ON_DEVICE, using small fakes of LeakCanary
 * (see the leakcanary package in this folder).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class AnalysisProgressTest {

    private Application mockApplication;
    private StudioLeakCanaryManager manager;

    @Before
    public void setUp() throws Exception {
        resetSingleton(StudioLeakCanaryManager.class);
        resetSingleton(StudioLeakCanaryListener.class);
        LeakCanary.INSTANCE.reset();
        AppWatcher.INSTANCE.getObjectWatcher().reset();

        mockApplication = mock(Application.class);
        Context mockContext = mock(Context.class);
        when(mockContext.getApplicationContext()).thenReturn(mockApplication);
        when(mockApplication.getPackageName()).thenReturn("com.test.app");

        manager = StudioLeakCanaryManager.getInstance();
        manager.initialize(mockContext); // Starts in ON_HOST.
        clearInvocations(mockApplication);
    }

    @After
    public void tearDown() throws Exception {
        Field field = StudioLeakCanaryListener.class.getDeclaredField("instance");
        field.setAccessible(true);
        StudioLeakCanaryListener listener = (StudioLeakCanaryListener) field.get(null);
        if (listener != null) {
            Field threadField = StudioLeakCanaryListener.class.getDeclaredField("handlerThread");
            threadField.setAccessible(true);
            HandlerThread thread = (HandlerThread) threadField.get(listener);
            // Finish waiting tasks first, so none runs during the next test.
            shadowOf(thread.getLooper()).idle();
            thread.quit();
        }
        resetSingleton(StudioLeakCanaryManager.class);
        resetSingleton(StudioLeakCanaryListener.class);
    }

    /** ON_HOST: our event listener is not added. LeakCanary's list stays as it was. */
    @Test
    public void testOnHostDoesNotAddEventListener() {
        assertThat(eventListeners())
                .containsExactly(LEAK_CANARY_LISTENER_1, LEAK_CANARY_LISTENER_2)
                .inOrder();
    }

    /** ON_DEVICE: our event listener is added after LeakCanary's listeners, which all stay. */
    @Test
    public void testOnDeviceAddsEventListenerAfterLeakCanaryListeners() {
        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);

        List<EventListener> listeners = eventListeners();
        assertThat(listeners).hasSize(3);
        assertThat(listeners.subList(0, 2))
                .containsExactly(LEAK_CANARY_LISTENER_1, LEAK_CANARY_LISTENER_2)
                .inOrder();
    }

    /** Switching modes many times adds our event listener only once. It stays in ON_HOST. */
    @Test
    public void testSwitchingModesAddsEventListenerOnce() {
        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);
        EventListener ours = eventListeners().get(2);

        manager.setMode(StudioLeakCanaryManager.MODE_ON_HOST);
        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);
        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE); // Same mode again.

        assertThat(eventListeners())
                .containsExactly(LEAK_CANARY_LISTENER_1, LEAK_CANARY_LISTENER_2, ours)
                .inOrder();
    }

    /** If the app sets a new LeakCanary config, the next ON_DEVICE call adds ours again. */
    @Test
    public void testNewConfigGetsEventListenerAgain() {
        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);
        LeakCanary.INSTANCE.reset(); // New config, without our listener.

        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);

        assertThat(eventListeners()).hasSize(3);
    }

    /** ON_DEVICE: progress 0.42 is sent as 42, only to our own app. */
    @Test
    public void testOnDeviceSendsProgressAsPercent() {
        EventListener ours = setOnDeviceModeAndGetOurListener();

        ours.onEvent(new HeapAnalysisProgress(0.42));

        List<Intent> sent = sentProgressIntents();
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getIntExtra(HelperConfig.ANALYSIS_PROGRESS_EXTRA, -1)).isEqualTo(42);
        assertThat(sent.get(0).getPackage()).isEqualTo("com.test.app");
    }

    /** Values outside 0.0 to 1.0 are clamped to 0 to 100. */
    @Test
    public void testPercentIsClamped() {
        EventListener ours = setOnDeviceModeAndGetOurListener();

        ours.onEvent(new HeapAnalysisProgress(-0.1));
        ours.onEvent(new HeapAnalysisProgress(1.5));

        assertThat(sentPercents()).containsExactly(0, 100).inOrder();
    }

    /** Other events, and null, send nothing. */
    @Test
    public void testOtherEventsSendNothing() {
        EventListener ours = setOnDeviceModeAndGetOurListener();

        ours.onEvent(new DumpingHeap());
        ours.onEvent(null);

        assertThat(sentProgressIntents()).isEmpty();
    }

    /** After a switch back to ON_HOST, our listener stays but sends nothing. */
    @Test
    public void testOnHostSendsNoProgress() {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        manager.setMode(StudioLeakCanaryManager.MODE_ON_HOST);

        ours.onEvent(new HeapAnalysisProgress(0.42));

        assertThat(sentProgressIntents()).isEmpty();
    }

    /** An error while sending must not reach LeakCanary, so the app can't crash. */
    @Test
    public void testSendErrorDoesNotEscape() {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        doThrow(new RuntimeException("test"))
                .when(mockApplication)
                .sendBroadcast(any(Intent.class));

        ours.onEvent(new HeapAnalysisProgress(0.42)); // Must not throw.
    }

    private EventListener setOnDeviceModeAndGetOurListener() {
        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);
        clearInvocations(mockApplication);
        return eventListeners().get(2);
    }

    private static List<EventListener> eventListeners() {
        return LeakCanary.INSTANCE.getConfig().getEventListeners();
    }

    private List<Integer> sentPercents() {
        List<Integer> percents = new ArrayList<>();
        for (Intent intent : sentProgressIntents()) {
            percents.add(intent.getIntExtra(HelperConfig.ANALYSIS_PROGRESS_EXTRA, -1));
        }
        return percents;
    }

    /** Returns the progress broadcasts sent. Count broadcasts are skipped. */
    private List<Intent> sentProgressIntents() {
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mockApplication, atLeast(0)).sendBroadcast(captor.capture());
        List<Intent> progress = new ArrayList<>();
        for (Intent intent : captor.getAllValues()) {
            if (HelperConfig.ANALYSIS_PROGRESS_INTENT.equals(intent.getAction())) {
                progress.add(intent);
            }
        }
        return progress;
    }

    private static void resetSingleton(Class<?> clazz) throws Exception {
        Field field = clazz.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
