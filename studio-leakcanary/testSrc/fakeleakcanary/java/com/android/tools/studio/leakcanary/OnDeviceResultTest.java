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

import static org.mockito.ArgumentMatchers.argThat;
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

import com.android.tools.profiler.proto.LeakCanary.LeakCanaryAnalysisData;

import leakcanary.AppWatcher;
import leakcanary.EventListener;
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisFailed;
import leakcanary.EventListener.Event.HeapAnalysisDone.HeapAnalysisSucceeded;
import leakcanary.EventListener.Event.HeapDumpFailed;
import leakcanary.LeakCanary;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import shark.HeapAnalysisException;
import shark.HeapAnalysisFailure;
import shark.HeapAnalysisSuccess;
import shark.LeakTrace;
import shark.LeakTraceObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Tests that, in ON_DEVICE, the analysis result is saved to a file and its path is sent, that
 * errors are sent, and that a forced dump always dumps. Uses small fakes of LeakCanary and Shark
 * (see the leakcanary and shark packages in this folder).
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class OnDeviceResultTest {

    @Rule public final TemporaryFolder cacheDir = new TemporaryFolder();

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
        when(mockApplication.getCacheDir()).thenReturn(cacheDir.getRoot());

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

    /** The result is saved as our proto, and its full path is sent only to our own app. */
    @Test
    public void testResultIsSavedAndPathIsSent() throws Exception {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        HeapAnalysisSuccess success = FakeSharkResults.success();

        ours.onEvent(new HeapAnalysisSucceeded(success));

        File file = resultFile();
        assertThat(readResult(file)).isEqualTo(SharkResultConverter.convert(success));
        List<Intent> sent = sentIntents(HelperConfig.ANALYSIS_RESULT_INTENT);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getStringExtra(HelperConfig.ANALYSIS_RESULT_FILE_PATH_EXTRA))
                .isEqualTo(file.getAbsolutePath());
        assertThat(sent.get(0).getPackage()).isEqualTo("com.test.app");
        assertThat(sentIntents(HelperConfig.DEVICE_ERROR_INTENT)).isEmpty();
    }

    /** Every result uses the same file, so the cache folder never holds more than one. */
    @Test
    public void testEachResultReplacesTheLastOne() throws Exception {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        HeapAnalysisFailure failure =
                new HeapAnalysisFailure(
                        5L, 6L, 7L, new HeapAnalysisException(new RuntimeException("test")));

        ours.onEvent(new HeapAnalysisSucceeded(FakeSharkResults.success()));
        ours.onEvent(new HeapAnalysisFailed(failure));

        assertThat(cacheDir.getRoot().list())
                .asList()
                .containsExactly(HelperConfig.ON_DEVICE_ANALYSIS_FILE_NAME);
        assertThat(readResult(resultFile())).isEqualTo(SharkResultConverter.convert(failure));
        assertThat(sentIntents(HelperConfig.ANALYSIS_RESULT_INTENT)).hasSize(2);
    }

    /** A failed analysis is a normal result (with the failure part), not a device error. */
    @Test
    public void testFailedAnalysisIsSentAsResult() throws Exception {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        HeapAnalysisFailure failure =
                new HeapAnalysisFailure(
                        5L, 6L, 7L, new HeapAnalysisException(new RuntimeException("test")));

        ours.onEvent(new HeapAnalysisFailed(failure));

        assertThat(readResult(resultFile()).hasFailure()).isTrue();
        assertThat(sentIntents(HelperConfig.ANALYSIS_RESULT_INTENT)).hasSize(1);
        assertThat(sentIntents(HelperConfig.DEVICE_ERROR_INTENT)).isEmpty();
    }

    /**
     * Right after the result, the current retained count is sent, so Studio shows the objects
     * retained during the analysis instead of 0.
     */
    @Test
    public void testCurrentCountIsSentAfterResult() throws Exception {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        AppWatcher.INSTANCE.getObjectWatcher().retainedObjectCount = 3;

        ours.onEvent(new HeapAnalysisSucceeded(FakeSharkResults.success()));
        idleListenerThread();

        List<Intent> sent = allSentIntents();
        assertThat(actions(sent))
                .containsExactly(
                        HelperConfig.ANALYSIS_RESULT_INTENT,
                        HelperConfig.OBJECT_COUNT_UPDATE_INTENT)
                .inOrder();
        assertThat(sent.get(1).getIntExtra(HelperConfig.OBJECT_COUNT_UPDATE_EXTRA, -1))
                .isEqualTo(3);
    }

    /** A value from a newer LeakCanary: no result, and the unsupported version error is sent. */
    @Test
    public void testUnknownValueSendsUnsupportedVersionError() {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        HeapAnalysisSuccess success =
                FakeSharkResults.success(
                        FakeSharkResults.trace(
                                LeakTrace.GcRootType.STICKY_CLASS,
                                LeakTraceObject.ObjectType.NEW_IN_LATER_VERSION));

        ours.onEvent(new HeapAnalysisSucceeded(success));

        assertThat(sentErrorCodes()).containsExactly(HelperConfig.ERROR_CODE_UNSUPPORTED_VERSION);
        assertThat(sentIntents(HelperConfig.ANALYSIS_RESULT_INTENT)).isEmpty();
        assertThat(cacheDir.getRoot().list()).isEmpty();
    }

    /** If the file can't be saved, no path is sent, and the save failed error is sent instead. */
    @Test
    public void testNoCacheFolderSendsSaveFailedError() {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        when(mockApplication.getCacheDir()).thenReturn(null);

        ours.onEvent(new HeapAnalysisSucceeded(FakeSharkResults.success()));

        assertThat(sentErrorCodes()).containsExactly(HelperConfig.ERROR_CODE_ON_DEVICE_SAVE_FAILED);
        assertThat(sentIntents(HelperConfig.ANALYSIS_RESULT_INTENT)).isEmpty();
    }

    /**
     * If the new file can't replace the old one, the save failed error is sent and no temp file is
     * left.
     */
    @Test
    public void testRenameFailureSendsSaveFailedErrorAndLeavesNoTempFile() throws Exception {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        // A non-empty folder with the result file's name: the rename can't replace it.
        File blocker = cacheDir.newFolder(HelperConfig.ON_DEVICE_ANALYSIS_FILE_NAME);
        new File(blocker, "keep").createNewFile();
        AppWatcher.INSTANCE.getObjectWatcher().retainedObjectCount = 4;

        ours.onEvent(new HeapAnalysisSucceeded(FakeSharkResults.success()));

        assertThat(cacheDir.getRoot().list())
                .asList()
                .containsExactly(HelperConfig.ON_DEVICE_ANALYSIS_FILE_NAME);
        List<Intent> sent = allSentIntents();
        assertThat(actions(sent))
                .containsExactly(
                        HelperConfig.DEVICE_ERROR_INTENT, HelperConfig.OBJECT_COUNT_UPDATE_INTENT)
                .inOrder();
        assertThat(sent.get(0).getIntExtra(HelperConfig.DEVICE_ERROR_CODE_EXTRA, -1))
                .isEqualTo(HelperConfig.ERROR_CODE_ON_DEVICE_SAVE_FAILED);
        assertThat(sent.get(1).getIntExtra(HelperConfig.OBJECT_COUNT_UPDATE_EXTRA, -1))
                .isEqualTo(4);
    }

    /**
     * On an error, the last good result is deleted (Studio was not told about it), and the current
     * count is still sent after the error.
     */
    @Test
    public void testErrorDeletesOldResultAndStillSendsCount() throws Exception {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        ours.onEvent(new HeapAnalysisSucceeded(FakeSharkResults.success()));
        idleListenerThread();
        clearInvocations(mockApplication);
        AppWatcher.INSTANCE.getObjectWatcher().retainedObjectCount = 2;

        ours.onEvent(
                new HeapAnalysisSucceeded(
                        FakeSharkResults.success(
                                FakeSharkResults.trace(
                                        LeakTrace.GcRootType.STICKY_CLASS,
                                        LeakTraceObject.ObjectType.NEW_IN_LATER_VERSION))));
        idleListenerThread();

        assertThat(cacheDir.getRoot().list()).isEmpty();
        List<Intent> sent = allSentIntents();
        assertThat(actions(sent))
                .containsExactly(
                        HelperConfig.DEVICE_ERROR_INTENT, HelperConfig.OBJECT_COUNT_UPDATE_INTENT)
                .inOrder();
        assertThat(sent.get(1).getIntExtra(HelperConfig.OBJECT_COUNT_UPDATE_EXTRA, -1))
                .isEqualTo(2);
    }

    /** A failed dump that LeakCanary won't try again (Studio's dump) sends the error. */
    @Test
    public void testHeapDumpFailedWithoutRetrySendsError() {
        EventListener ours = setOnDeviceModeAndGetOurListener();

        ours.onEvent(new HeapDumpFailed(new RuntimeException("disk full"), false));

        List<Intent> sent = sentIntents(HelperConfig.DEVICE_ERROR_INTENT);
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).getIntExtra(HelperConfig.DEVICE_ERROR_CODE_EXTRA, -1))
                .isEqualTo(HelperConfig.ERROR_CODE_ON_DEVICE_HEAP_DUMP_FAILED);
        assertThat(sent.get(0).getPackage()).isEqualTo("com.test.app");
    }

    /** A failed dump that LeakCanary will try again by itself sends nothing. */
    @Test
    public void testHeapDumpFailedWithRetrySendsNothing() {
        EventListener ours = setOnDeviceModeAndGetOurListener();

        ours.onEvent(new HeapDumpFailed(new RuntimeException("disk full"), true));

        assertThat(allSentIntents()).isEmpty();
    }

    /** After a switch back to ON_HOST, our listener stays but sends and saves nothing. */
    @Test
    public void testOnHostSendsNothing() throws Exception {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        manager.setMode(StudioLeakCanaryManager.MODE_ON_HOST);
        idleListenerThread();
        clearInvocations(mockApplication);

        ours.onEvent(new HeapAnalysisSucceeded(FakeSharkResults.success()));
        ours.onEvent(new HeapDumpFailed(new RuntimeException("disk full"), false));

        assertThat(allSentIntents()).isEmpty();
        assertThat(cacheDir.getRoot().list()).isEmpty();
    }

    /**
     * An error while sending must not reach LeakCanary, so the app can't crash. Each event still
     * tries its own send, and the count is still sent after each result.
     */
    @Test
    public void testSendErrorDoesNotEscape() {
        EventListener ours = setOnDeviceModeAndGetOurListener();
        // Count broadcasts (sent later on the count thread) still work, so that thread keeps
        // running.
        doThrow(new RuntimeException("test"))
                .when(mockApplication)
                .sendBroadcast(
                        argThat(
                                intent ->
                                        !HelperConfig.OBJECT_COUNT_UPDATE_INTENT.equals(
                                                intent.getAction())));

        // None of these may throw.
        ours.onEvent(new HeapAnalysisSucceeded(FakeSharkResults.success()));
        ours.onEvent(
                new HeapAnalysisSucceeded(
                        FakeSharkResults.success(
                                FakeSharkResults.trace(
                                        LeakTrace.GcRootType.STICKY_CLASS,
                                        LeakTraceObject.ObjectType.NEW_IN_LATER_VERSION))));
        ours.onEvent(new HeapDumpFailed(new RuntimeException("disk full"), false));

        List<Intent> nonCount = new ArrayList<>();
        for (Intent intent : allSentIntents()) {
            if (!HelperConfig.OBJECT_COUNT_UPDATE_INTENT.equals(intent.getAction())) {
                nonCount.add(intent);
            }
        }
        assertThat(actions(nonCount))
                .containsExactly(
                        HelperConfig.ANALYSIS_RESULT_INTENT,
                        HelperConfig.DEVICE_ERROR_INTENT,
                        HelperConfig.DEVICE_ERROR_INTENT)
                .inOrder();
        assertThat(sentErrorCodes())
                .containsExactly(
                        HelperConfig.ERROR_CODE_UNSUPPORTED_VERSION,
                        HelperConfig.ERROR_CODE_ON_DEVICE_HEAP_DUMP_FAILED)
                .inOrder();
        assertThat(sentIntents(HelperConfig.OBJECT_COUNT_UPDATE_INTENT)).hasSize(2);
    }

    /** ON_DEVICE: a forced dump always dumps, also when the count is 0. */
    @Test
    public void testForceDumpAlwaysDumpsInOnDevice() {
        setOnDeviceModeAndGetOurListener();
        AppWatcher.INSTANCE.getObjectWatcher().retainedObjectCount = 0;

        new ForceDumpReceiver()
                .onReceive(mockApplication, new Intent(HelperConfig.FORCE_DUMP_ON_DEVICE_INTENT));

        assertThat(LeakCanary.INSTANCE.dumpHeapCount).isEqualTo(1);
    }

    /** ON_HOST: a forced dump does nothing (Studio dumps the heap itself). */
    @Test
    public void testForceDumpDoesNothingInOnHost() {
        AppWatcher.INSTANCE.getObjectWatcher().retainedObjectCount = 3;

        new ForceDumpReceiver()
                .onReceive(mockApplication, new Intent(HelperConfig.FORCE_DUMP_ON_DEVICE_INTENT));

        assertThat(LeakCanary.INSTANCE.dumpHeapCount).isEqualTo(0);
    }

    private EventListener setOnDeviceModeAndGetOurListener() {
        manager.setMode(StudioLeakCanaryManager.MODE_ON_DEVICE);
        try {
            idleListenerThread();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        clearInvocations(mockApplication);
        List<EventListener> listeners = LeakCanary.INSTANCE.getConfig().getEventListeners();
        return listeners.get(listeners.size() - 1);
    }

    private File resultFile() {
        File file = new File(cacheDir.getRoot(), HelperConfig.ON_DEVICE_ANALYSIS_FILE_NAME);
        assertThat(file.exists()).isTrue();
        return file;
    }

    private static LeakCanaryAnalysisData readResult(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return LeakCanaryAnalysisData.parseFrom(in);
        }
    }

    /** Returns every broadcast sent, after the count thread has finished its tasks. */
    private List<Intent> allSentIntents() {
        try {
            idleListenerThread();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        ArgumentCaptor<Intent> captor = ArgumentCaptor.forClass(Intent.class);
        verify(mockApplication, atLeast(0)).sendBroadcast(captor.capture());
        return captor.getAllValues();
    }

    private List<Intent> sentIntents(String action) {
        List<Intent> matching = new ArrayList<>();
        for (Intent intent : allSentIntents()) {
            if (action.equals(intent.getAction())) {
                matching.add(intent);
            }
        }
        return matching;
    }

    private List<Integer> sentErrorCodes() {
        List<Integer> codes = new ArrayList<>();
        for (Intent intent : sentIntents(HelperConfig.DEVICE_ERROR_INTENT)) {
            codes.add(intent.getIntExtra(HelperConfig.DEVICE_ERROR_CODE_EXTRA, -1));
        }
        return codes;
    }

    private static List<String> actions(List<Intent> intents) {
        List<String> actions = new ArrayList<>();
        for (Intent intent : intents) {
            actions.add(intent.getAction());
        }
        return actions;
    }

    /** Runs all tasks waiting on the retained count listener's background thread. */
    private void idleListenerThread() throws Exception {
        StudioLeakCanaryListener listener = listenerInstance();
        if (listener != null) {
            shadowOf(handlerThread(listener).getLooper()).idle();
        }
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
