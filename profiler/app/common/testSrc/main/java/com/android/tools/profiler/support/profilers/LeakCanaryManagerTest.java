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

package com.android.tools.profiler.support.profilers;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class LeakCanaryManagerTest {

    private Context mockContext;

    /**
     * Sets up the testing environment before each test runs. Mocks the Android Context and uses
     * reflection to inject the mocked context into LeakCanaryManager's private static
     * 'sApplicationContext' field. This isolates the manager from needing a real Android
     * application lifecycle during JVM testing.
     */
    @Before
    public void setUp() throws Exception {
        mockContext = mock(Context.class);
        when(mockContext.getPackageName()).thenReturn("com.test.app");

        // Inject the mock context directly into the sApplicationContext field
        Field appContextField = LeakCanaryManager.class.getDeclaredField("sApplicationContext");
        appContextField.setAccessible(true);
        appContextField.set(null, mockContext);
    }

    /**
     * Cleans up the environment after each test. Removes the mocked application context and ensures
     * any dynamically registered broadcast receivers are unregistered to prevent memory leaks or
     * interference between tests.
     */
    @After
    public void tearDown() throws Exception {
        LeakCanaryManager.stopListeningForRetainedObjects();

        Field appContextField = LeakCanaryManager.class.getDeclaredField("sApplicationContext");
        appContextField.setAccessible(true);
        appContextField.set(null, null);
    }

    /**
     * Verifies the behavior of retrieving the LeakCanary threshold when the app fails to respond.
     * The test mocks the initial broadcast sent to the app but provides no simulated response. It
     * ensures the manager correctly waits, times out, and safely returns 0 instead of crashing.
     */
    @Test
    public void testGetRetainedVisibleThresholdTimesOut() throws Exception {
        // Speed up the test by reducing the timeout to 0 seconds
        Field timeoutField =
                LeakCanaryManager.class.getDeclaredField("LEAKCANARY_CHECK_TIMEOUT_SECONDS");
        timeoutField.setAccessible(true);
        long originalTimeout = (long) timeoutField.get(null);
        timeoutField.set(null, 0L);

        try {
            // Since we are mocking the context, the receiver will be registered,
            // but no one will send the THRESHOLD_RESULT_INTENT.
            // It should time out immediately and return 0.
            int threshold = LeakCanaryManager.getRetainedVisibleThreshold();
            assertThat(threshold).isEqualTo(0);
        } finally {
            timeoutField.set(null, originalTimeout);
        }

        // Verify GET_THRESHOLD intent was sent
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockContext).sendBroadcast(intentCaptor.capture());
        assertThat(intentCaptor.getValue().getAction())
                .isEqualTo(LeakCanaryManager.GET_THRESHOLD_INTENT);
    }

    /**
     * Verifies that the manager correctly broadcasts the heap dump completion signal to the app. It
     * checks that the intent contains the correct action and accurately bundles the provided
     * timestamp as an intent extra.
     */
    @Test
    public void testSignalHeapDumpComplete() {
        long timestamp = 123456789L;
        LeakCanaryManager.signalHeapDumpComplete(timestamp);

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockContext).sendBroadcast(intentCaptor.capture());

        Intent sentIntent = intentCaptor.getValue();
        assertThat(sentIntent.getAction()).isEqualTo(LeakCanaryManager.HEAP_DUMP_COMPLETE_INTENT);
        assertThat(sentIntent.getLongExtra(LeakCanaryManager.HEAP_DUMP_COMPLETE_EXTRA, 0))
                .isEqualTo(timestamp);
    }

    /**
     * Verifies that triggering an on-device heap dump from the host correctly fires the
     * 'FORCE_DUMP_ON_DEVICE_INTENT' broadcast so the app-side helper can catch it.
     */
    @Test
    public void testTriggerOnDeviceDump() {
        LeakCanaryManager.triggerOnDeviceDump();

        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockContext).sendBroadcast(intentCaptor.capture());

        Intent sentIntent = intentCaptor.getValue();
        assertThat(sentIntent.getAction()).isEqualTo(LeakCanaryManager.FORCE_DUMP_ON_DEVICE_INTENT);
    }

    /**
     * Verifies that when instructed to start listening (in host mode), the manager: 1. Successfully
     * registers a broadcast receiver to catch retained object counts with TIRAMISU export flags. 2.
     * Broadcasts the 'START_LISTENING_INTENT' with the correct mode down to the app helper.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testStartListeningRegistersReceiverAndSendsBroadcast_postTiramisu()
            throws Exception {
        int modeOnHost = 1;
        LeakCanaryManager.startListeningForRetainedObjects(modeOnHost);

        // Verify receiver was registered with RECEIVER_NOT_EXPORTED
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        ArgumentCaptor<IntentFilter> filterCaptor = ArgumentCaptor.forClass(IntentFilter.class);

        verify(mockContext)
                .registerReceiver(
                        receiverCaptor.capture(),
                        filterCaptor.capture(),
                        eq(Context.RECEIVER_NOT_EXPORTED));

        // Verify that the start listening broadcast was sent to the app
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockContext).sendBroadcast(intentCaptor.capture());

        Intent sentIntent = intentCaptor.getValue();
        assertThat(sentIntent.getAction()).isEqualTo(LeakCanaryManager.START_LISTENING_INTENT);
        assertThat(sentIntent.getIntExtra(LeakCanaryManager.LEAKCANARY_MODE_EXTRA, -1))
                .isEqualTo(modeOnHost);
    }

    /**
     * Verifies that when instructed to start listening (in host mode) on pre-Tiramisu APIs, the
     * manager: 1. Successfully registers a broadcast receiver using the app internal permission. 2.
     * Broadcasts the 'START_LISTENING_INTENT' with the correct mode down to the app helper.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.R)
    public void testStartListeningRegistersReceiverAndSendsBroadcast_preTiramisu()
            throws Exception {
        int modeOnHost = 1;
        LeakCanaryManager.startListeningForRetainedObjects(modeOnHost);

        // Verify receiver was registered with the internal permission
        ArgumentCaptor<BroadcastReceiver> receiverCaptor =
                ArgumentCaptor.forClass(BroadcastReceiver.class);
        ArgumentCaptor<IntentFilter> filterCaptor = ArgumentCaptor.forClass(IntentFilter.class);

        verify(mockContext)
                .registerReceiver(
                        receiverCaptor.capture(),
                        filterCaptor.capture(),
                        eq("com.test.app.permission.LEAK_CANARY_INTERNAL"),
                        eq(null));

        // Verify that the start listening broadcast was sent to the app
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockContext).sendBroadcast(intentCaptor.capture());

        Intent sentIntent = intentCaptor.getValue();
        assertThat(sentIntent.getAction()).isEqualTo(LeakCanaryManager.START_LISTENING_INTENT);
        assertThat(sentIntent.getIntExtra(LeakCanaryManager.LEAKCANARY_MODE_EXTRA, -1))
                .isEqualTo(modeOnHost);
    }

    /**
     * Verifies that stopping the listening process explicitly unregisters the broadcast receiver
     * and nullifies its reference to prevent context leaks.
     */
    @Test
    public void testStopListeningUnregistersReceiver() throws Exception {
        int modeOnHost = 1;
        LeakCanaryManager.startListeningForRetainedObjects(modeOnHost);

        // Unregister
        LeakCanaryManager.stopListeningForRetainedObjects();

        // Verify unregister was called
        verify(mockContext).unregisterReceiver(any(BroadcastReceiver.class));

        Field receiverField = LeakCanaryManager.class.getDeclaredField("sObjectCountReceiver");
        receiverField.setAccessible(true);
        assertThat(receiverField.get(null)).isNull();
    }

    /**
     * An advanced JNI boundary test. It simulates an incoming intent from the app containing an
     * updated object count. It verifies that the receiver properly unpacks the payload and attempts
     * to forward the count to the C++ Perfa daemon via the 'sendObjectCountNative' JNI method. (We
     * catch UnsatisfiedLinkError to prove the native call was attempted since libperfa.so isn't
     * loaded).
     */
    @Test
    public void testObjectCountUpdateReceiverHandlesIntent() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(1);

        Field receiverField = LeakCanaryManager.class.getDeclaredField("sObjectCountReceiver");
        receiverField.setAccessible(true);
        BroadcastReceiver receiver = (BroadcastReceiver) receiverField.get(null);

        assertThat(receiver).isNotNull();

        Intent updateIntent = new Intent(LeakCanaryManager.OBJECT_COUNT_UPDATE_INTENT);
        updateIntent.putExtra(LeakCanaryManager.OBJECT_COUNT_UPDATE_EXTRA, 42);

        // Call the receiver directly
        // Because sendObjectCountNative is a native method and we haven't loaded the native library
        // in this pure Robolectric JVM test, invoking the receiver will hit the native method
        // and throw a java.lang.UnsatisfiedLinkError.
        // We verify that the receiver correctly parses the intent and attempts the native call.
        java.lang.UnsatisfiedLinkError e =
                assertThrows(
                        java.lang.UnsatisfiedLinkError.class,
                        () -> receiver.onReceive(mockContext, updateIntent));
        assertThat(e.getMessage()).contains("sendObjectCountNative");
    }
}
