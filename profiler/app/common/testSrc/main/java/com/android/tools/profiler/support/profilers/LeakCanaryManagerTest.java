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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.StrictMode;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.invocation.Invocation;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class LeakCanaryManagerTest {

    private static final int MODE_ON_DEVICE = 0;
    private static final int MODE_ON_HOST = 1;
    private static final boolean OCCURRENCES_ON = true;
    private static final boolean OCCURRENCES_OFF = false;

    /** The receivers ON_DEVICE registers with the flag on, in order. */
    private static final List<String> ON_DEVICE_ACTIONS =
            Arrays.asList(
                    LeakCanaryManager.OBJECT_COUNT_UPDATE_INTENT,
                    LeakCanaryManager.ANALYSIS_PROGRESS_INTENT,
                    LeakCanaryManager.ANALYSIS_RESULT_INTENT,
                    LeakCanaryManager.DEVICE_ERROR_INTENT);

    /** The fields of the receivers that only ON_DEVICE with the flag on uses. */
    private static final List<String> ON_DEVICE_ONLY_FIELDS =
            Arrays.asList(
                    "sAnalysisProgressReceiver", "sAnalysisResultReceiver", "sDeviceErrorReceiver");

    @Rule public TemporaryFolder tempFolder = new TemporaryFolder();

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
     * Cleans up the environment after each test. Removes the mocked application context, clears the
     * saved library version, and ensures any dynamically registered broadcast receivers are
     * unregistered to prevent memory leaks or interference between tests.
     */
    @After
    public void tearDown() throws Exception {
        LeakCanaryManager.stopListeningForRetainedObjects();

        Field versionField = LeakCanaryManager.class.getDeclaredField("sStudioLeakCanaryVersion");
        versionField.setAccessible(true);
        versionField.set(null, "");

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

    /** Checks that the threshold call also keeps the library version the app sends. */
    @Test
    public void testThresholdKeepsLibraryVersion() {
        replyToThresholdRequest(thresholdResult(7, "1.1.0-alpha01"));

        assertThat(LeakCanaryManager.getRetainedVisibleThreshold()).isEqualTo(7);
        assertThat(LeakCanaryManager.getStudioLeakCanaryVersion()).isEqualTo("1.1.0-alpha01");
    }

    /** Checks that the version is empty when an old library sends no version. */
    @Test
    public void testThresholdWithoutVersionGivesEmptyVersion() {
        replyToThresholdRequest(thresholdResult(5, null));

        assertThat(LeakCanaryManager.getRetainedVisibleThreshold()).isEqualTo(5);
        assertThat(LeakCanaryManager.getStudioLeakCanaryVersion()).isEmpty();
    }

    /** Checks that the version is empty after a timeout, even if an earlier call saved one. */
    @Test
    public void testThresholdTimeoutGivesEmptyVersion() throws Exception {
        Field versionField = LeakCanaryManager.class.getDeclaredField("sStudioLeakCanaryVersion");
        versionField.setAccessible(true);
        versionField.set(null, "1.0.0");
        Field timeoutField =
                LeakCanaryManager.class.getDeclaredField("LEAKCANARY_CHECK_TIMEOUT_SECONDS");
        timeoutField.setAccessible(true);
        long originalTimeout = (long) timeoutField.get(null);
        timeoutField.set(null, 0L);

        try {
            assertThat(LeakCanaryManager.getRetainedVisibleThreshold()).isEqualTo(0);
        } finally {
            timeoutField.set(null, originalTimeout);
        }

        assertThat(LeakCanaryManager.getStudioLeakCanaryVersion()).isEmpty();
    }

    /** Checks that a later threshold call doesn't keep the version from an earlier call. */
    @Test
    public void testVersionFromEarlierCallIsNotKept() {
        replyToThresholdRequest(thresholdResult(5, "1.1.0"));
        LeakCanaryManager.getRetainedVisibleThreshold();
        assertThat(LeakCanaryManager.getStudioLeakCanaryVersion()).isEqualTo("1.1.0");

        replyToThresholdRequest(thresholdResult(5, null));
        LeakCanaryManager.getRetainedVisibleThreshold();

        assertThat(LeakCanaryManager.getStudioLeakCanaryVersion()).isEmpty();
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

    /**
     * Checks that ON_DEVICE mode listens for the count, the analysis progress, the analysis result
     * and device errors, and sends START_LISTENING with mode 0.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testStartOnDeviceRegistersAllReceivers() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);

        assertThat(registeredActions()).containsExactlyElementsIn(ON_DEVICE_ACTIONS).inOrder();
        assertThat(getStaticField("sObjectCountReceiver")).isNotNull();
        for (String field : ON_DEVICE_ONLY_FIELDS) {
            assertThat(getStaticField(field)).isNotNull();
        }
        assertStartListeningSent(MODE_ON_DEVICE);
    }

    /** Checks that ON_HOST mode listens for the count only. */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testStartOnHostRegistersCountOnly() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_HOST, OCCURRENCES_ON);

        assertThat(registeredActions())
                .containsExactly(LeakCanaryManager.OBJECT_COUNT_UPDATE_INTENT);
        assertOnDeviceOnlyReceiversNull();
        assertStartListeningSent(MODE_ON_HOST);
    }

    /** Checks that starting ON_DEVICE twice does not register any receiver twice. */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testStartOnDeviceTwiceRegistersEachReceiverOnce() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);

        assertThat(registeredActions()).containsExactlyElementsIn(ON_DEVICE_ACTIONS);
        verify(mockContext, never()).unregisterReceiver(any(BroadcastReceiver.class));
    }

    /**
     * Checks that going from ON_DEVICE to ON_HOST removes the ON_DEVICE only receivers and keeps
     * the count receiver.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testSwitchOnDeviceToOnHostRemovesOnDeviceReceivers() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);
        Object countReceiver = getStaticField("sObjectCountReceiver");
        List<BroadcastReceiver> onDeviceReceivers = getOnDeviceOnlyReceivers();

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_HOST, OCCURRENCES_ON);

        verify(mockContext, times(3)).unregisterReceiver(any(BroadcastReceiver.class));
        for (BroadcastReceiver receiver : onDeviceReceivers) {
            verify(mockContext).unregisterReceiver(receiver);
        }
        assertOnDeviceOnlyReceiversNull();
        assertThat(getStaticField("sObjectCountReceiver")).isSameInstanceAs(countReceiver);
        assertThat(registeredActions()).containsExactlyElementsIn(ON_DEVICE_ACTIONS);
    }

    /**
     * Checks that going from ON_HOST to ON_DEVICE keeps the count receiver and adds the ON_DEVICE
     * only receivers.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testSwitchOnHostToOnDeviceAddsOnDeviceReceivers() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_HOST, OCCURRENCES_ON);
        Object countReceiver = getStaticField("sObjectCountReceiver");

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);

        verify(mockContext, never()).unregisterReceiver(any(BroadcastReceiver.class));
        assertThat(getStaticField("sObjectCountReceiver")).isSameInstanceAs(countReceiver);
        for (String field : ON_DEVICE_ONLY_FIELDS) {
            assertThat(getStaticField(field)).isNotNull();
        }
        assertThat(registeredActions()).containsExactlyElementsIn(ON_DEVICE_ACTIONS).inOrder();
    }

    /** Checks that stop removes all receivers after an ON_DEVICE start. */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testStopAfterOnDeviceUnregistersAllReceivers() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);
        BroadcastReceiver countReceiver =
                (BroadcastReceiver) getStaticField("sObjectCountReceiver");
        List<BroadcastReceiver> onDeviceReceivers = getOnDeviceOnlyReceivers();

        LeakCanaryManager.stopListeningForRetainedObjects();

        verify(mockContext).unregisterReceiver(countReceiver);
        for (BroadcastReceiver receiver : onDeviceReceivers) {
            verify(mockContext).unregisterReceiver(receiver);
        }
        assertNoReceivers();
    }

    /**
     * Checks that ON_DEVICE starts nothing when the count receiver can't be registered: no receiver
     * is left and START_LISTENING is not sent.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testOnDeviceStartsNothingWhenCountReceiverFails() throws Exception {
        failRegisterFor(LeakCanaryManager.OBJECT_COUNT_UPDATE_INTENT);

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);

        assertNoReceivers();
        verify(mockContext, never()).sendBroadcast(any(Intent.class));
        // Only the failed count receiver is cleaned up.
        verify(mockContext, times(1)).unregisterReceiver(any(BroadcastReceiver.class));
    }

    /**
     * Checks that ON_HOST with the flag off starts nothing when the count receiver can't be
     * registered: no receiver is left and START_LISTENING is not sent.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testOnHostStartsNothingWhenCountReceiverFails() throws Exception {
        failRegisterFor(LeakCanaryManager.OBJECT_COUNT_UPDATE_INTENT);

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_HOST, OCCURRENCES_OFF);

        assertNoReceivers();
        verify(mockContext, never()).sendBroadcast(any(Intent.class));
        // Only the failed count receiver is cleaned up.
        verify(mockContext, times(1)).unregisterReceiver(any(BroadcastReceiver.class));
    }

    /**
     * Checks that on Android versions before 13, ON_HOST starts nothing when the count receiver
     * can't be registered.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.R)
    public void testStartsNothingWhenCountReceiverFails_preTiramisu() throws Exception {
        failRegisterFor(LeakCanaryManager.OBJECT_COUNT_UPDATE_INTENT);

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_HOST, OCCURRENCES_OFF);

        assertThat(getStaticField("sObjectCountReceiver")).isNull();
        verify(mockContext, never()).sendBroadcast(any(Intent.class));
    }

    /**
     * Checks that a failed start also removes the receivers from an earlier start, so nothing is
     * left registered.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testFailedStartRemovesReceiversFromEarlierStart() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_HOST, OCCURRENCES_ON);
        BroadcastReceiver countReceiver =
                (BroadcastReceiver) getStaticField("sObjectCountReceiver");
        failRegisterFor(LeakCanaryManager.ANALYSIS_PROGRESS_INTENT);

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);

        verify(mockContext).unregisterReceiver(countReceiver);
        assertNoReceivers();
        // Only the first start sent START_LISTENING.
        verify(mockContext, times(1)).sendBroadcast(any(Intent.class));
    }

    /**
     * Checks that ON_DEVICE on Android versions before 13 registers all receivers with the app
     * internal permission.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.R)
    public void testStartOnDeviceRegistersAllReceivers_preTiramisu() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);

        assertThat(registeredActions()).containsExactlyElementsIn(ON_DEVICE_ACTIONS).inOrder();
        verify(mockContext, times(ON_DEVICE_ACTIONS.size()))
                .registerReceiver(
                        any(BroadcastReceiver.class),
                        any(IntentFilter.class),
                        eq("com.test.app.permission.LEAK_CANARY_INTERNAL"),
                        eq(null));
        assertStartListeningSent(MODE_ON_DEVICE);
    }

    /**
     * Checks that ON_DEVICE starts nothing when the progress receiver can't be registered: the
     * count receiver is removed too and START_LISTENING is not sent.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testOnDeviceStartsNothingWhenProgressReceiverFails() throws Exception {
        failRegisterFor(LeakCanaryManager.ANALYSIS_PROGRESS_INTENT);

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);

        assertThat(registeredActions())
                .containsExactly(
                        LeakCanaryManager.OBJECT_COUNT_UPDATE_INTENT,
                        LeakCanaryManager.ANALYSIS_PROGRESS_INTENT)
                .inOrder();
        verify(mockContext, times(2)).unregisterReceiver(any(BroadcastReceiver.class));
        assertNoReceivers();
        verify(mockContext, never()).sendBroadcast(any(Intent.class));
    }

    /**
     * Checks that ON_DEVICE starts nothing when the result receiver can't be registered: the count
     * and progress receivers are removed too and START_LISTENING is not sent.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testOnDeviceStartsNothingWhenResultReceiverFails() throws Exception {
        failRegisterFor(LeakCanaryManager.ANALYSIS_RESULT_INTENT);

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);

        assertThat(registeredActions())
                .containsExactlyElementsIn(ON_DEVICE_ACTIONS.subList(0, 3))
                .inOrder();
        verify(mockContext, times(3)).unregisterReceiver(any(BroadcastReceiver.class));
        assertNoReceivers();
        verify(mockContext, never()).sendBroadcast(any(Intent.class));
    }

    /**
     * Checks that ON_DEVICE starts nothing when the device error receiver can't be registered: all
     * other receivers are removed too and START_LISTENING is not sent.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testOnDeviceStartsNothingWhenDeviceErrorReceiverFails() throws Exception {
        failRegisterFor(LeakCanaryManager.DEVICE_ERROR_INTENT);

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);

        assertThat(registeredActions()).containsExactlyElementsIn(ON_DEVICE_ACTIONS).inOrder();
        verify(mockContext, times(4)).unregisterReceiver(any(BroadcastReceiver.class));
        assertNoReceivers();
        verify(mockContext, never()).sendBroadcast(any(Intent.class));
    }

    /**
     * Checks that the progress receiver reads the percent and tries to send it to perfa through
     * 'sendAnalysisProgressNative'. The native library isn't loaded in this test, so the call
     * throws UnsatisfiedLinkError. That proves the call was tried.
     */
    @Test
    public void testAnalysisProgressReceiverHandlesIntent() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);
        BroadcastReceiver receiver =
                (BroadcastReceiver) getStaticField("sAnalysisProgressReceiver");
        assertThat(receiver).isNotNull();

        Intent progressIntent = new Intent(LeakCanaryManager.ANALYSIS_PROGRESS_INTENT);
        progressIntent.putExtra(LeakCanaryManager.ANALYSIS_PROGRESS_EXTRA, 42);

        UnsatisfiedLinkError e =
                assertThrows(
                        UnsatisfiedLinkError.class,
                        () -> receiver.onReceive(mockContext, progressIntent));
        assertThat(e.getMessage()).contains("sendAnalysisProgressNative");
    }

    /** Checks that the progress receiver ignores intents with a different action. */
    @Test
    public void testAnalysisProgressReceiverIgnoresOtherIntents() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);
        BroadcastReceiver receiver =
                (BroadcastReceiver) getStaticField("sAnalysisProgressReceiver");

        // No native call, so no UnsatisfiedLinkError.
        receiver.onReceive(mockContext, new Intent(LeakCanaryManager.OBJECT_COUNT_UPDATE_INTENT));
        receiver.onReceive(mockContext, null);

        // Only START_LISTENING was sent. The receiver sent nothing.
        verify(mockContext, times(1)).sendBroadcast(any(Intent.class));
    }

    /** Checks that with the flag off, ON_DEVICE registers no receiver but still sends START. */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testFlagOffOnDeviceRegistersNothing() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_OFF);

        assertThat(registeredActions()).isEmpty();
        assertNoReceivers();
        assertStartListeningSent(MODE_ON_DEVICE);
    }

    /** Checks that the one-argument start uses the flag off, its default value. */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testOneArgumentStartUsesFlagOff() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE);

        assertThat(registeredActions()).isEmpty();
        assertStartListeningSent(MODE_ON_DEVICE);
    }

    /** Checks that with the flag off, ON_HOST then ON_DEVICE removes the count receiver. */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testFlagOffOnHostToOnDeviceRemovesCountReceiver() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_HOST, OCCURRENCES_OFF);
        BroadcastReceiver countReceiver =
                (BroadcastReceiver) getStaticField("sObjectCountReceiver");

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_OFF);

        verify(mockContext).unregisterReceiver(countReceiver);
        assertThat(getStaticField("sObjectCountReceiver")).isNull();
    }

    /** Checks that with the flag off, ON_HOST registers the count receiver only. */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testFlagOffOnHostRegistersCountOnly() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_HOST, OCCURRENCES_OFF);

        assertThat(registeredActions())
                .containsExactly(LeakCanaryManager.OBJECT_COUNT_UPDATE_INTENT);
        assertOnDeviceOnlyReceiversNull();
        assertStartListeningSent(MODE_ON_HOST);
    }

    /**
     * Checks that a flag off ON_DEVICE start removes all receivers left from a flag on ON_DEVICE
     * start.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testFlagOffOnDeviceRemovesReceiversFromFlagOnStart() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);
        BroadcastReceiver countReceiver =
                (BroadcastReceiver) getStaticField("sObjectCountReceiver");
        List<BroadcastReceiver> onDeviceReceivers = getOnDeviceOnlyReceivers();

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_OFF);

        verify(mockContext).unregisterReceiver(countReceiver);
        for (BroadcastReceiver receiver : onDeviceReceivers) {
            verify(mockContext).unregisterReceiver(receiver);
        }
        assertNoReceivers();
    }

    /**
     * Checks that a flag off ON_HOST start removes the ON_DEVICE only receivers left from a flag on
     * ON_DEVICE start, and keeps the count receiver.
     */
    @Test
    @Config(sdk = Build.VERSION_CODES.TIRAMISU)
    public void testFlagOffOnHostRemovesOnDeviceReceiversFromFlagOnStart() throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);
        Object countReceiver = getStaticField("sObjectCountReceiver");
        List<BroadcastReceiver> onDeviceReceivers = getOnDeviceOnlyReceivers();

        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_HOST, OCCURRENCES_OFF);

        verify(mockContext, times(3)).unregisterReceiver(any(BroadcastReceiver.class));
        for (BroadcastReceiver receiver : onDeviceReceivers) {
            verify(mockContext).unregisterReceiver(receiver);
        }
        assertOnDeviceOnlyReceiversNull();
        assertThat(getStaticField("sObjectCountReceiver")).isSameInstanceAs(countReceiver);
    }

    /**
     * Checks that the result receiver reads the file, deletes it, and tries to send the bytes to
     * perfa through 'sendAnalysisResultNative'. The native library isn't loaded in this test, so
     * the call throws UnsatisfiedLinkError. That proves the call was tried.
     */
    @Test
    public void testAnalysisResultReceiverReadsDeletesAndSendsFile() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sAnalysisResultReceiver");
        File file = tempFolder.newFile("result.pb");
        Files.write(file.toPath(), new byte[] {1, 2, 3});

        UnsatisfiedLinkError e =
                assertThrows(
                        UnsatisfiedLinkError.class,
                        () -> receiver.onReceive(mockContext, resultIntent(file.getPath())));

        assertThat(e.getMessage()).contains("sendAnalysisResultNative");
        assertThat(file.exists()).isFalse();
    }

    /** Checks that the result receiver sends the read error when the file is missing. */
    @Test
    public void testAnalysisResultReceiverHandlesMissingFile() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sAnalysisResultReceiver");
        File file = new File(tempFolder.getRoot(), "missing.pb");

        assertSendsReadError(receiver, resultIntent(file.getPath()));
    }

    /**
     * Checks that the result receiver deletes the path even when it can't be read, and sends the
     * read error. A folder can't be opened as a file, so reading fails.
     */
    @Test
    public void testAnalysisResultReceiverDeletesPathWhenReadFails() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sAnalysisResultReceiver");
        File folder = tempFolder.newFolder("result.pb");

        assertSendsReadError(receiver, resultIntent(folder.getPath()));

        assertThat(folder.exists()).isFalse();
    }

    /** Checks that the result receiver deletes an empty file and sends the read error. */
    @Test
    public void testAnalysisResultReceiverDropsEmptyFile() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sAnalysisResultReceiver");
        File file = tempFolder.newFile("empty.pb");

        assertSendsReadError(receiver, resultIntent(file.getPath()));

        assertThat(file.exists()).isFalse();
    }

    /**
     * Checks that the result receiver deletes a file larger than 3 MB without reading it, and sends
     * the read error. perfa can't send messages larger than 4 MB to the daemon.
     */
    @Test
    public void testAnalysisResultReceiverDropsTooLargeFile() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sAnalysisResultReceiver");
        File file = tempFolder.newFile("large.pb");
        try (RandomAccessFile large = new RandomAccessFile(file, "rw")) {
            large.setLength(3 * 1024 * 1024 + 1);
        }

        assertSendsReadError(receiver, resultIntent(file.getPath()));

        assertThat(file.exists()).isFalse();
    }

    /** Checks that a file of exactly 3 MB is still read and sent. */
    @Test
    public void testAnalysisResultReceiverSendsFileAtSizeLimit() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sAnalysisResultReceiver");
        File file = tempFolder.newFile("limit.pb");
        try (RandomAccessFile limit = new RandomAccessFile(file, "rw")) {
            limit.setLength(3 * 1024 * 1024);
        }

        UnsatisfiedLinkError e =
                assertThrows(
                        UnsatisfiedLinkError.class,
                        () -> receiver.onReceive(mockContext, resultIntent(file.getPath())));

        assertThat(e.getMessage()).contains("sendAnalysisResultNative");
        assertThat(file.exists()).isFalse();
    }

    /** Checks that the result receiver sends the read error when the intent has no file path. */
    @Test
    public void testAnalysisResultReceiverDropsIntentWithoutPath() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sAnalysisResultReceiver");

        assertSendsReadError(receiver, new Intent(LeakCanaryManager.ANALYSIS_RESULT_INTENT));
    }

    /**
     * Checks that the receiver sends an error to perfa through 'sendErrorNative' and doesn't send a
     * result. The native library isn't loaded in this test, so the call throws
     * UnsatisfiedLinkError. That proves which call was tried.
     */
    private void assertSendsReadError(BroadcastReceiver receiver, Intent intent) {
        UnsatisfiedLinkError e =
                assertThrows(
                        UnsatisfiedLinkError.class, () -> receiver.onReceive(mockContext, intent));
        assertThat(e.getMessage()).contains("sendErrorNative");
    }

    /** Checks that the result receiver ignores other intents and leaves the file alone. */
    @Test
    public void testAnalysisResultReceiverIgnoresOtherIntents() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sAnalysisResultReceiver");
        File file = tempFolder.newFile("result.pb");
        Files.write(file.toPath(), new byte[] {1, 2, 3});
        Intent otherIntent = new Intent(LeakCanaryManager.DEVICE_ERROR_INTENT);
        otherIntent.putExtra(LeakCanaryManager.ANALYSIS_RESULT_FILE_PATH_EXTRA, file.getPath());

        // No native call, so no UnsatisfiedLinkError.
        receiver.onReceive(mockContext, otherIntent);
        receiver.onReceive(mockContext, null);

        assertThat(file.exists()).isTrue();
    }

    /** Checks that reading the result puts back the app's StrictMode policy afterwards. */
    @Test
    public void testAnalysisResultReceiverRestoresStrictModePolicy() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sAnalysisResultReceiver");
        File file = tempFolder.newFile("result.pb");
        Files.write(file.toPath(), new byte[] {1, 2, 3});
        StrictMode.ThreadPolicy appPolicy =
                new StrictMode.ThreadPolicy.Builder()
                        .detectDiskReads()
                        .detectDiskWrites()
                        .penaltyLog()
                        .build();
        StrictMode.ThreadPolicy before = StrictMode.getThreadPolicy();
        StrictMode.setThreadPolicy(appPolicy);
        try {
            assertThrows(
                    UnsatisfiedLinkError.class,
                    () -> receiver.onReceive(mockContext, resultIntent(file.getPath())));

            assertThat(StrictMode.getThreadPolicy().toString()).isEqualTo(appPolicy.toString());
        } finally {
            StrictMode.setThreadPolicy(before);
        }
    }

    /**
     * Checks that the error receiver sends each known error code to perfa through
     * 'sendErrorNative'. The native library isn't loaded in this test, so the call throws
     * UnsatisfiedLinkError. That proves the call was tried.
     */
    @Test
    public void testDeviceErrorReceiverSendsKnownCodes() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sDeviceErrorReceiver");

        for (int errorCode : new int[] {4, 5, 6}) {
            UnsatisfiedLinkError e =
                    assertThrows(
                            UnsatisfiedLinkError.class,
                            () -> receiver.onReceive(mockContext, deviceErrorIntent(errorCode)));
            assertThat(e.getMessage()).contains("sendErrorNative");
        }
    }

    /** Checks that the error receiver drops unknown error codes and intents with no code. */
    @Test
    public void testDeviceErrorReceiverDropsUnknownCodes() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sDeviceErrorReceiver");

        // No native call, so no UnsatisfiedLinkError.
        for (int errorCode : new int[] {0, 1, 2, 3, 7}) {
            receiver.onReceive(mockContext, deviceErrorIntent(errorCode));
        }
        receiver.onReceive(mockContext, new Intent(LeakCanaryManager.DEVICE_ERROR_INTENT));
    }

    /** Checks that the error receiver ignores other intents. */
    @Test
    public void testDeviceErrorReceiverIgnoresOtherIntents() throws Exception {
        BroadcastReceiver receiver = startOnDeviceAndGetReceiver("sDeviceErrorReceiver");
        Intent otherIntent = new Intent(LeakCanaryManager.ANALYSIS_RESULT_INTENT);
        otherIntent.putExtra(LeakCanaryManager.DEVICE_ERROR_CODE_EXTRA, 4);

        // No native call, so no UnsatisfiedLinkError.
        receiver.onReceive(mockContext, otherIntent);
        receiver.onReceive(mockContext, null);
    }

    /**
     * Makes the mock app answer GET_THRESHOLD with the given reply, sent to the threshold receiver
     * registered last.
     */
    private void replyToThresholdRequest(Intent reply) {
        doAnswer(
                        invocation -> {
                            Intent sent = invocation.getArgument(0);
                            if (!LeakCanaryManager.GET_THRESHOLD_INTENT.equals(sent.getAction())) {
                                return null;
                            }
                            BroadcastReceiver thresholdReceiver = null;
                            for (Invocation call : mockingDetails(mockContext).getInvocations()) {
                                if (call.getMethod().getName().equals("registerReceiver")) {
                                    IntentFilter filter = call.getArgument(1);
                                    if (filter.hasAction(
                                            LeakCanaryManager.THRESHOLD_RESULT_INTENT)) {
                                        thresholdReceiver = call.getArgument(0);
                                    }
                                }
                            }
                            assertThat(thresholdReceiver).isNotNull();
                            thresholdReceiver.onReceive(mockContext, reply);
                            return null;
                        })
                .when(mockContext)
                .sendBroadcast(any(Intent.class));
    }

    private static Intent thresholdResult(int threshold, String version) {
        Intent intent = new Intent(LeakCanaryManager.THRESHOLD_RESULT_INTENT);
        intent.putExtra(LeakCanaryManager.THRESHOLD_EXTRA, threshold);
        if (version != null) {
            intent.putExtra(LeakCanaryManager.STUDIO_LEAKCANARY_VERSION_EXTRA, version);
        }
        return intent;
    }

    /** Starts ON_DEVICE with the flag on, and returns the receiver kept in the given field. */
    private static BroadcastReceiver startOnDeviceAndGetReceiver(String field) throws Exception {
        LeakCanaryManager.startListeningForRetainedObjects(MODE_ON_DEVICE, OCCURRENCES_ON);
        BroadcastReceiver receiver = (BroadcastReceiver) getStaticField(field);
        assertThat(receiver).isNotNull();
        return receiver;
    }

    private static Intent resultIntent(String path) {
        Intent intent = new Intent(LeakCanaryManager.ANALYSIS_RESULT_INTENT);
        intent.putExtra(LeakCanaryManager.ANALYSIS_RESULT_FILE_PATH_EXTRA, path);
        return intent;
    }

    private static Intent deviceErrorIntent(int errorCode) {
        Intent intent = new Intent(LeakCanaryManager.DEVICE_ERROR_INTENT);
        intent.putExtra(LeakCanaryManager.DEVICE_ERROR_CODE_EXTRA, errorCode);
        return intent;
    }

    private static List<BroadcastReceiver> getOnDeviceOnlyReceivers() throws Exception {
        List<BroadcastReceiver> receivers = new ArrayList<>();
        for (String field : ON_DEVICE_ONLY_FIELDS) {
            BroadcastReceiver receiver = (BroadcastReceiver) getStaticField(field);
            assertThat(receiver).isNotNull();
            receivers.add(receiver);
        }
        return receivers;
    }

    private static void assertOnDeviceOnlyReceiversNull() throws Exception {
        for (String field : ON_DEVICE_ONLY_FIELDS) {
            assertThat(getStaticField(field)).isNull();
        }
    }

    private static void assertNoReceivers() throws Exception {
        assertThat(getStaticField("sObjectCountReceiver")).isNull();
        assertOnDeviceOnlyReceiversNull();
    }

    private static Object getStaticField(String name) throws Exception {
        Field field = LeakCanaryManager.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(null);
    }

    /** Returns the action of each receiver registered on the mock context, in order. */
    private List<String> registeredActions() {
        List<String> actions = new ArrayList<>();
        for (Invocation invocation : mockingDetails(mockContext).getInvocations()) {
            if (invocation.getMethod().getName().equals("registerReceiver")) {
                IntentFilter filter = invocation.getArgument(1);
                actions.add(filter.getAction(0));
            }
        }
        return actions;
    }

    /** Makes registerReceiver throw for the given action, for both the new and the old API. */
    private void failRegisterFor(String action) {
        when(mockContext.registerReceiver(
                        any(BroadcastReceiver.class),
                        argThat(filter -> filter != null && filter.hasAction(action)),
                        anyInt()))
                .thenThrow(new SecurityException("Test failure"));
        when(mockContext.registerReceiver(
                        any(BroadcastReceiver.class),
                        argThat(filter -> filter != null && filter.hasAction(action)),
                        any(),
                        any()))
                .thenThrow(new SecurityException("Test failure"));
    }

    private void assertStartListeningSent(int mode) {
        ArgumentCaptor<Intent> intentCaptor = ArgumentCaptor.forClass(Intent.class);
        verify(mockContext).sendBroadcast(intentCaptor.capture());
        Intent sentIntent = intentCaptor.getValue();
        assertThat(sentIntent.getAction()).isEqualTo(LeakCanaryManager.START_LISTENING_INTENT);
        assertThat(sentIntent.getIntExtra(LeakCanaryManager.LEAKCANARY_MODE_EXTRA, -1))
                .isEqualTo(mode);
    }
}
