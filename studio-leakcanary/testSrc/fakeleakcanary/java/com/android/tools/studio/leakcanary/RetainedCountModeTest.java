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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.os.HandlerThread;

import leakcanary.AppWatcher;
import leakcanary.GcTrigger;
import leakcanary.LeakCanary;
import leakcanary.ObjectWatcher;
import leakcanary.internal.InternalLeakCanary;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;

/**
 * Tests the retained object count in both modes, using small fakes of LeakCanary (see the
 * leakcanary package in this folder). These run in their own test target, because the main tests
 * check what happens when LeakCanary is missing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class RetainedCountModeTest {

    private final ObjectWatcher objectWatcher = AppWatcher.INSTANCE.getObjectWatcher();
    private final GcTrigger.Default gcTrigger = GcTrigger.Default.INSTANCE;
    private Application mockApplication;
    private StudioLeakCanaryManager manager;

    @Before
    public void setUp() throws Exception {
        resetSingleton(StudioLeakCanaryManager.class);
        resetSingleton(StudioLeakCanaryListener.class);
        objectWatcher.reset();
        gcTrigger.reset();
        LeakCanary.INSTANCE.reset();
        // Like LeakCanary at app start: only its own listener is registered.
        objectWatcher.addOnObjectRetainedListener(InternalLeakCanary.INSTANCE);

        mockApplication = mock(Application.class);
        Context mockContext = mock(Context.class);
        when(mockContext.getApplicationContext()).thenReturn(mockApplication);
        when(mockApplication.getPackageName()).thenReturn("com.test.app");

        manager = StudioLeakCanaryManager.getInstance();
        manager.initialize(mockContext); // Starts in ON_HOST.
        idleListenerThread();
        clearInvocations(mockApplication);
    }

    @After
    public void tearDown() throws Exception {
        StudioLeakCanaryListener listener = listenerInstance();
        if (listener != null) {
            // Finish waiting tasks first, so none runs during the next test.
            idleListenerThread();
            handlerThread(listener).quit();
        }
        resetSingleton(StudioLeakCanaryManager.class);
        resetSingleton(StudioLeakCanaryListener.class);
    }

    /** ON_HOST: our listener replaces LeakCanary's, and LeakCanary does not dump the heap. */
    @Test
    public void testOnHostReplacesLeakCanaryListener() {
        assertThat(objectWatcher.listeners).hasSize(1);
        assertThat(objectWatcher.listeners).doesNotContain(InternalLeakCanary.INSTANCE);
        assertThat(LeakCanary.INSTANCE.getConfig().getDumpHeap()).isFalse();
    }

    /** ON_DEVICE: LeakCanary's listener stays, ours is added, and LeakCanary dumps the heap. */
    @Test
    public void testOnDeviceKeepsLeakCanaryListenerAndAddsOurs() {
        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);

        assertThat(objectWatcher.listeners).hasSize(2);
        assertThat(objectWatcher.listeners).contains(InternalLeakCanary.INSTANCE);
        assertThat(LeakCanary.INSTANCE.getConfig().getDumpHeap()).isTrue();
    }

    /** Switching modes many times always leaves the right listeners, with no duplicates. */
    @Test
    public void testSwitchingModesLeavesRightListeners() {
        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);
        manager.setMode(StudioLeakCanaryManager.MODE_ON_HOST);
        assertThat(objectWatcher.listeners).hasSize(1);
        assertThat(objectWatcher.listeners).doesNotContain(InternalLeakCanary.INSTANCE);

        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);
        assertThat(objectWatcher.listeners).hasSize(2);
        assertThat(objectWatcher.listeners).contains(InternalLeakCanary.INSTANCE);
    }

    /** ON_DEVICE: the count is sent, without running GC (LeakCanary runs its own GC). */
    @Test
    public void testOnDeviceSendsCountWithoutGc() throws Exception {
        setOnDeviceMode();
        objectWatcher.retainedObjectCount = 3;

        listenerInstance().checkRetainedObjects();

        assertThat(gcTrigger.runCount).isEqualTo(0);
        assertThat(lastSentCount()).isEqualTo(3);
    }

    /** ON_HOST: GC runs before counting, and the count after GC is sent. */
    @Test
    public void testOnHostRunsGcBeforeCounting() throws Exception {
        objectWatcher.retainedObjectCount = 3;
        gcTrigger.countAfterGc = 1;

        listenerInstance().checkRetainedObjects();

        assertThat(gcTrigger.runCount).isEqualTo(1);
        assertThat(lastSentCount()).isEqualTo(1);
    }

    /** ON_DEVICE: the first check waits longer, so LeakCanary's own GC can finish first. */
    @Test
    public void testOnDeviceFirstCheckWaitsLonger() throws Exception {
        setOnDeviceMode();
        objectWatcher.retainedObjectCount = 2;

        listenerInstance().onObjectRetained();

        idleListenerThreadFor(StudioLeakCanaryListener.DEBOUNCE_DELAY_MILLIS);
        verify(mockApplication, never()).sendBroadcast(any(Intent.class));
        idleListenerThreadFor(
                StudioLeakCanaryListener.ON_DEVICE_FIRST_CHECK_DELAY_MILLIS
                        - StudioLeakCanaryListener.DEBOUNCE_DELAY_MILLIS);
        assertThat(lastSentCount()).isEqualTo(2);
    }

    /** ON_HOST: the first check still runs after the short delay. */
    @Test
    public void testOnHostFirstCheckRunsAfterShortDelay() throws Exception {
        objectWatcher.retainedObjectCount = 2;

        listenerInstance().onObjectRetained();

        idleListenerThreadFor(StudioLeakCanaryListener.DEBOUNCE_DELAY_MILLIS);
        assertThat(lastSentCount()).isEqualTo(2);
    }

    /** ON_DEVICE: a heap dump finished event must not clear LeakCanary's watched objects. */
    @Test
    public void testOnDeviceIgnoresHeapDumpFinished() throws Exception {
        setOnDeviceMode();

        StudioLeakCanaryListener.onHeapDumpFinished(123L);

        assertThat(objectWatcher.clearedBefore).isEqualTo(-1);
    }

    /** ON_HOST: a heap dump finished event still clears the objects watched before the dump. */
    @Test
    public void testOnHostClearsWatchedObjectsOnHeapDumpFinished() {
        StudioLeakCanaryListener.onHeapDumpFinished(123L);

        assertThat(objectWatcher.clearedBefore).isEqualTo(123L);
    }

    /**
     * ON_DEVICE: when Studio starts listening, the current count is sent right away, also when the
     * app is already in ON_DEVICE (then the mode does not change, so nothing else sends it).
     */
    @Test
    public void testStartListeningInOnDeviceSendsCurrentCount() throws Exception {
        setOnDeviceMode();
        objectWatcher.retainedObjectCount = 2;
        Intent intent = new Intent(HelperConfig.START_LISTENING_INTENT);
        intent.putExtra("leakcanary_mode", StudioLeakCanaryManager.MODE_ON_DEVICE);

        new StartListeningReceiver().onReceive(mockApplication, intent);
        idleListenerThread();

        assertThat(lastSentCount()).isEqualTo(2);
    }

    private void setOnDeviceMode() throws Exception {
        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);
        idleListenerThread();
        clearInvocations(mockApplication);
    }

    /** Returns the count in the last OBJECT_COUNT_UPDATE broadcast. */
    private int lastSentCount() {
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mockApplication, atLeastOnce()).sendBroadcast(captor.capture());
        List<Intent> sent = captor.getAllValues();
        Intent last = sent.get(sent.size() - 1);
        assertThat(last.getAction()).isEqualTo(HelperConfig.OBJECT_COUNT_UPDATE_INTENT);
        return last.getIntExtra(HelperConfig.OBJECT_COUNT_UPDATE_EXTRA, -999);
    }

    /** Runs all tasks waiting on the listener's background thread. */
    private void idleListenerThread() throws Exception {
        StudioLeakCanaryListener listener = listenerInstance();
        if (listener != null) {
            shadowOf(handlerThread(listener).getLooper()).idle();
        }
    }

    /** Moves time forward by the given millis and runs the tasks due on the listener's thread. */
    private void idleListenerThreadFor(long millis) throws Exception {
        shadowOf(handlerThread(listenerInstance()).getLooper()).idleFor(Duration.ofMillis(millis));
    }

    private static StudioLeakCanaryListener listenerInstance() throws Exception {
        Field field = StudioLeakCanaryListener.class.getDeclaredField("instance");
        field.setAccessible(true);
        return (StudioLeakCanaryListener) field.get(null);
    }

    private static HandlerThread handlerThread(StudioLeakCanaryListener listener) throws Exception {
        Field field = StudioLeakCanaryListener.class.getDeclaredField("handlerThread");
        field.setAccessible(true);
        return (HandlerThread) field.get(listener);
    }

    private static void resetSingleton(Class<?> clazz) throws Exception {
        Field field = clazz.getDeclaredField("instance");
        field.setAccessible(true);
        field.set(null, null);
    }
}
