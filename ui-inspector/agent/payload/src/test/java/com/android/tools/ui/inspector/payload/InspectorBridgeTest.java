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

package com.android.tools.ui.inspector.payload;

import static com.google.common.truth.Truth.assertThat;

import androidx.inspection.Connection;
import androidx.inspection.Inspector;
import androidx.inspection.InspectorEnvironment;

import com.android.tools.arttooling.ArtTooling;
import com.android.tools.ui.inspector.payload.appinspection.AppInspectionUtils;
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

@RunWith(RobolectricTestRunner.class)
public final class InspectorBridgeTest {

    private final Connection mockConnection =
            new Connection() {
                @Override
                public void sendEvent(byte[] data) {}
            };

    private HandlerThreadExecutor primaryExecutor;

    @Before
    public void setUp() {
        primaryExecutor =
                new HandlerThreadExecutor(
                        "test_bridge_thread",
                        t -> {
                            throw new RuntimeException(t);
                        });
    }

    @After
    public void tearDown() {
        primaryExecutor.quitSafely();
    }

    @Test
    public void testSendCommand_sequentialProcessing() throws Exception {
        AtomicBoolean inProgress = new AtomicBoolean(false);
        Inspector mockInspectorSeq =
                new Inspector(mockConnection) {
                    @Override
                    public void onReceiveCommand(byte[] data, CommandCallback callback) {
                        assertThat(inProgress.get()).isFalse();
                        inProgress.set(true);
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        inProgress.set(false);
                        callback.reply(data);
                    }

                    @Override
                    public void onDispose() {}
                };

        InspectorBridge bridgeSeq =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        mockInspectorSeq,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor,
                        ArtTooling::clear);

        CountDownLatch latch = new CountDownLatch(2);
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        new Thread(
                        () -> {
                            try {
                                bridgeSeq.sendCommand(new byte[] {1});
                            } catch (Throwable t) {
                                errors.add(t);
                            } finally {
                                latch.countDown();
                            }
                        })
                .start();

        new Thread(
                        () -> {
                            try {
                                bridgeSeq.sendCommand(new byte[] {2});
                            } catch (Throwable t) {
                                errors.add(t);
                            } finally {
                                latch.countDown();
                            }
                        })
                .start();

        latch.await(5, TimeUnit.SECONDS);
        assertThat(errors).isEmpty();
    }

    @Test
    public void testSendCommand_executesOnPrimaryExecutorThread() throws Exception {
        AtomicReference<String> executionThreadName = new AtomicReference<>();
        Inspector mockInspector =
                new Inspector(mockConnection) {
                    @Override
                    public void onReceiveCommand(byte[] data, CommandCallback callback) {
                        executionThreadName.set(Thread.currentThread().getName());
                        callback.reply(data);
                    }

                    @Override
                    public void onDispose() {}
                };

        InspectorBridge bridge =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        mockInspector,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor,
                        ArtTooling::clear);

        bridge.sendCommand(new byte[] {1});

        assertThat(executionThreadName.get()).startsWith("test_bridge_thread");
    }

    @Test
    public void testSendCommand_errorPropagation() {
        Inspector mockInspector =
                new Inspector(mockConnection) {
                    @Override
                    public void onReceiveCommand(byte[] data, CommandCallback callback) {
                        throw new RuntimeException("Test exception");
                    }

                    @Override
                    public void onDispose() {}
                };

        InspectorBridge bridge =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        mockInspector,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor,
                        ArtTooling::clear);

        boolean exceptionThrown = false;
        try {
            bridge.sendCommand(new byte[] {1});
        } catch (Exception e) {
            exceptionThrown = true;
            assertThat(e.getMessage()).contains("Test exception");
        }
        assertThat(exceptionThrown).isTrue();
    }

    @Test
    public void testSendCommand_throwFromOnReceiveCommand_disposesBridge() throws Exception {
        IllegalStateException failure = new IllegalStateException("Thrown by onReceiveCommand");
        CountDownLatch disposed = new CountDownLatch(1);
        InspectorBridge bridge =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        new Inspector(mockConnection) {
                            @Override
                            public void onReceiveCommand(byte[] data, CommandCallback callback) {
                                throw failure;
                            }

                            @Override
                            public void onDispose() {
                                disposed.countDown();
                            }
                        },
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor,
                        hookOwnerId -> {});
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            assertThat(sendCommandForThrowable(caller, bridge)).isSameAs(failure);
            assertThat(bridge.isDisposed()).isTrue();
            assertThat(disposed.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            caller.shutdownNow();
            bridge.dispose();
        }
    }

    @Test
    public void testSendCommand_crashOnPrimaryExecutorBeforeReplying_failsCommand()
            throws Exception {
        assertCrashBeforeReplyingFailsCommand(environment -> environment.executors().primary());
    }

    @Test
    public void testSendCommand_crashOnIoExecutorBeforeReplying_failsCommand() throws Exception {
        assertCrashBeforeReplyingFailsCommand(environment -> environment.executors().io());
    }

    /**
     * Asserts that a command fails with the crash of the task that would have replied to it, when
     * that task runs on the executor returned by {@code executorOf}.
     */
    private void assertCrashBeforeReplyingFailsCommand(
            Function<InspectorEnvironment, Executor> executorOf) throws Exception {
        List<Throwable> crashes = new CopyOnWriteArrayList<>();
        IllegalStateException failure = new IllegalStateException("Failure before replying");
        InspectorBridge bridge =
                InspectorBridge.create(
                        "test_inspector",
                        environment ->
                                new Inspector(mockConnection) {
                                    @Override
                                    public void onReceiveCommand(
                                            byte[] data, CommandCallback callback) {
                                        // Replies from a later task, which fails before replying.
                                        executorOf
                                                .apply(environment)
                                                .execute(
                                                        () -> {
                                                            throw failure;
                                                        });
                                    }

                                    @Override
                                    public void onDispose() {}
                                },
                        new AppInspectionUtils.DelegatingConnection(),
                        crashes::add,
                        hookOwnerId -> {});
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> thrown =
                    caller.submit(
                            () -> {
                                try {
                                    bridge.sendCommand(new byte[] {1});
                                    return null;
                                } catch (Throwable t) {
                                    return t;
                                }
                            });

            assertThat(thrown.get(5, TimeUnit.SECONDS)).isSameAs(failure);
            // No need to wait for the crash report: the bridge reports a crash before failing the
            // commands.
            assertThat(crashes).containsExactly(failure);
        } finally {
            // Interrupts the command if it is still waiting, so that a failure does not leak a
            // blocked thread.
            caller.shutdownNow();
            bridge.dispose();
        }
    }

    @Test
    public void testCrash_disposesBridge() throws Exception {
        IllegalStateException crash = new IllegalStateException("Crash");
        CountDownLatch disposed = new CountDownLatch(1);
        List<String> clearedOwnerIds = new CopyOnWriteArrayList<>();
        CountDownLatch hooksCleared = new CountDownLatch(1);
        InspectorBridge bridge =
                InspectorBridge.create(
                        "test_inspector",
                        environment ->
                                new Inspector(mockConnection) {
                                    @Override
                                    public void onReceiveCommand(
                                            byte[] data, CommandCallback callback) {
                                        environment
                                                .executors()
                                                .primary()
                                                .execute(
                                                        () -> {
                                                            throw crash;
                                                        });
                                    }

                                    @Override
                                    public void onDispose() {
                                        disposed.countDown();
                                    }
                                },
                        new AppInspectionUtils.DelegatingConnection(),
                        t -> {},
                        hookOwnerId -> {
                            clearedOwnerIds.add(hookOwnerId);
                            hooksCleared.countDown();
                        });
        assertThat(bridge.isDisposed()).isFalse();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            assertThat(sendCommandForThrowable(caller, bridge)).isSameAs(crash);
            assertThat(bridge.isDisposed()).isTrue();
            assertThat(disposed.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(hooksCleared.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(clearedOwnerIds).hasSize(1);
            assertThat(sendCommandForThrowable(caller, bridge))
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            caller.shutdownNow();
            bridge.dispose();
        }
        // Disposing again does nothing.
        assertThat(clearedOwnerIds).hasSize(1);
    }

    /** Sends a command to {@code bridge} from {@code caller}, and returns what it throws. */
    private static Throwable sendCommandForThrowable(ExecutorService caller, InspectorBridge bridge)
            throws Exception {
        return caller.submit(
                        () -> {
                            try {
                                bridge.sendCommand(new byte[] {1});
                                return null;
                            } catch (Throwable t) {
                                return t;
                            }
                        })
                .get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testUpdateConnection_updatesDelegatingConnection() {
        AppInspectionUtils.DelegatingConnection delegatingConnection =
                new AppInspectionUtils.DelegatingConnection();
        Inspector mockInspector =
                new Inspector(mockConnection) {
                    @Override
                    public void onReceiveCommand(byte[] data, CommandCallback callback) {}

                    @Override
                    public void onDispose() {}
                };

        InspectorBridge bridge =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        mockInspector,
                        delegatingConnection,
                        primaryExecutor,
                        ArtTooling::clear);

        Connection newConnection =
                new Connection() {
                    @Override
                    public void sendEvent(byte[] data) {}
                };

        bridge.updateConnection(newConnection);

        assertThat(delegatingConnection.activeConnection).isSameAs(newConnection);
    }

    @Test
    public void testDispose_callsOnDisposeAndQuitsExecutor() throws Exception {
        AtomicBoolean disposed = new AtomicBoolean(false);
        Inspector mockInspector =
                new Inspector(mockConnection) {
                    @Override
                    public void onReceiveCommand(byte[] data, CommandCallback callback) {}

                    @Override
                    public void onDispose() {
                        disposed.set(true);
                    }
                };

        InspectorBridge bridge =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        mockInspector,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor,
                        ArtTooling::clear);

        bridge.dispose();

        primaryExecutor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(disposed.get()).isTrue();

        boolean exceptionThrown = false;
        try {
            bridge.sendCommand(new byte[] {1});
        } catch (Exception e) {
            exceptionThrown = true;
        }
        assertThat(exceptionThrown).isTrue();
    }

    @Test
    public void testDispose_preventsCommandRaceCondition() throws Exception {
        AtomicBoolean disposed = new AtomicBoolean(false);
        AtomicBoolean commandExecutedAfterDispose = new AtomicBoolean(false);

        Inspector mockInspector =
                new Inspector(mockConnection) {
                    @Override
                    public void onReceiveCommand(byte[] data, CommandCallback callback) {
                        if (disposed.get()) {
                            commandExecutedAfterDispose.set(true);
                        }
                        callback.reply(data);
                    }

                    @Override
                    public void onDispose() {
                        disposed.set(true);
                    }
                };

        InspectorBridge bridge =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        mockInspector,
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor,
                        ArtTooling::clear);

        // Call dispose
        bridge.dispose();

        // Immediately try to send a command.
        // Under the race condition (current code), this succeeds to queue and executes after
        // disposal.
        // Under the fixed code, this should fail immediately with a RejectedExecutionException.
        boolean rejected = false;
        try {
            bridge.sendCommand(new byte[] {1});
        } catch (RejectedExecutionException e) {
            rejected = true;
        }

        primaryExecutor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(rejected).isTrue();
        assertThat(commandExecutedAfterDispose.get()).isFalse();
    }

    @Test
    public void testDispose_clearsOnlyTheHooksOfItsOwnBridge() throws Exception {
        List<String> clearedOwnerIds = new CopyOnWriteArrayList<>();
        HandlerThreadExecutor secondExecutor =
                new HandlerThreadExecutor(
                        "test_bridge_thread_2",
                        t -> {
                            throw new RuntimeException(t);
                        });
        // Two servers in one process can each hold an inspector with the same inspector ID.
        InspectorBridge firstBridge =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        createIdleInspector(),
                        new AppInspectionUtils.DelegatingConnection(),
                        primaryExecutor,
                        clearedOwnerIds::add);
        InspectorBridge secondBridge =
                InspectorBridge.createForTesting(
                        "test_inspector",
                        createIdleInspector(),
                        new AppInspectionUtils.DelegatingConnection(),
                        secondExecutor,
                        clearedOwnerIds::add);

        firstBridge.dispose();
        primaryExecutor.awaitTermination(5, TimeUnit.SECONDS);
        secondBridge.dispose();
        secondExecutor.awaitTermination(5, TimeUnit.SECONDS);

        assertThat(clearedOwnerIds).hasSize(2);
        assertThat(clearedOwnerIds.get(0)).isNotEqualTo(clearedOwnerIds.get(1));
        assertThat(clearedOwnerIds.get(0)).startsWith("test_inspector/");
        assertThat(clearedOwnerIds.get(1)).startsWith("test_inspector/");
    }

    private Inspector createIdleInspector() {
        return new Inspector(mockConnection) {
            @Override
            public void onReceiveCommand(byte[] data, CommandCallback callback) {}

            @Override
            public void onDispose() {}
        };
    }
}
