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

import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.inspection.Connection;
import androidx.inspection.Inspector;
import androidx.inspection.InspectorEnvironment;

import com.android.tools.arttooling.ArtTooling;
import com.android.tools.ui.inspector.payload.appinspection.AppInspectionUtils;
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Bridges communication between the server and a specific [Inspector], enabling persistence across
 * host reconnections and ensuring sequential command processing.
 */
public final class InspectorBridge {
    private static final String THREAD_NAME_PREFIX = "ui_inspector_";

    /** Owns every ART Tooling hook that the inspector of this bridge registers. */
    private final String hookOwnerId;

    private final Inspector inspector;
    private final AppInspectionUtils.DelegatingConnection connection;
    private final HandlerThreadExecutor primaryExecutor;
    private final Consumer<String> clearHooks;

    /** The commands waiting for the inspector's reply, failed if the inspector crashes. */
    private final Set<CompletableFuture<byte[]>> pendingCommands;

    /**
     * Whether the bridge was disposed. A bridge disposes itself when its inspector throws from
     * {@code onReceiveCommand}, or when a task that the inspector runs on the bridge's executors
     * throws.
     */
    private final AtomicBoolean disposed = new AtomicBoolean();

    private InspectorBridge(
            String hookOwnerId,
            Inspector inspector,
            AppInspectionUtils.DelegatingConnection connection,
            HandlerThreadExecutor primaryExecutor,
            Consumer<String> clearHooks,
            Set<CompletableFuture<byte[]>> pendingCommands) {
        this.hookOwnerId = hookOwnerId;
        this.inspector = inspector;
        this.connection = connection;
        this.primaryExecutor = primaryExecutor;
        this.clearHooks = clearHooks;
        this.pendingCommands = pendingCommands;
    }

    /**
     * Updates the connection used by the inspector to send events. Since we re-use the inspectors
     * across different connections, this is required when a host reconnects and a new socket
     * session is established.
     */
    public void updateConnection(Connection newConnection) {
        connection.activeConnection = newConnection;
    }

    /**
     * Sends a command to the inspector and waits for the response. If the inspector throws or
     * crashes before replying, disposes the bridge and throws the inspector's exception.
     */
    public byte[] sendCommand(byte[] command) throws Exception {
        CompletableFuture<byte[]> future = new CompletableFuture<>();
        pendingCommands.add(future);
        try {
            return sendCommand(command, future);
        } finally {
            pendingCommands.remove(future);
        }
    }

    private byte[] sendCommand(byte[] command, CompletableFuture<byte[]> future) throws Exception {
        primaryExecutor.execute(
                () -> {
                    try {
                        inspector.onReceiveCommand(
                                command,
                                new Inspector.CommandCallback() {
                                    @Override
                                    public void reply(byte[] responseBytes) {
                                        future.complete(responseBytes);
                                    }

                                    @Override
                                    public void addCancellationListener(
                                            Executor executor, Runnable runnable) {
                                        future.whenComplete(
                                                (result, exception) -> {
                                                    if (future.isCancelled()) {
                                                        executor.execute(runnable);
                                                    }
                                                });
                                    }
                                });
                    } catch (Throwable t) {
                        // The exception escaped the inspector, which may be left in an
                        // inconsistent state: dispose it, as on a crash.
                        dispose();
                        future.completeExceptionally(t);
                    }
                });
        try {
            return future.get();
        } catch (InterruptedException e) {
            // Cancel the future to trigger the cancellation listener on the inspector.
            future.cancel(true);
            // Restore the interrupted status so that calling threads/frameworks are aware of the
            // interrupt.
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            // Unwrap ExecutionException to propagate the inspector's original failure cause
            // directly, avoiding leaking the internal CompletableFuture/threading wrapper to the
            // caller.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    /** Disposes the bridge and the underlying inspector. Does nothing if already disposed. */
    public void dispose() {
        // A bridge can be disposed concurrently, when its inspector fails and at server shutdown.
        if (!disposed.compareAndSet(false, true)) {
            return;
        }
        try {
            primaryExecutor.execute(
                    () -> {
                        try {
                            inspector.onDispose();
                        } catch (Throwable t) {
                            Log.e("InspectorBridge", "Error during inspector disposal", t);
                        } finally {
                            // Clear the bytecode hooks that this bridge's inspector registered, to
                            // prevent class loader memory leaks.
                            clearHooks.accept(hookOwnerId);
                        }
                    });
        } catch (RejectedExecutionException e) {
            // The executor has already quit, so the inspector cannot be disposed on its thread, but
            // its hooks can still be cleared.
            clearHooks.accept(hookOwnerId);
        }
        primaryExecutor.quitSafely();
    }

    /** Whether the bridge was disposed, and can no longer run its inspector's commands. */
    public boolean isDisposed() {
        return disposed.get();
    }

    /**
     * Creates and initializes a new [InspectorBridge] containing a dynamically loaded [Inspector]
     * instance.
     */
    public static InspectorBridge create(
            String inspectorId,
            String dexPath,
            AppInspectionUtils.DelegatingConnection connection,
            HandlerThreadExecutor.CrashListener crashListener)
            throws Exception {
        return create(
                inspectorId,
                environment ->
                        AppInspectionUtils.loadInspectorDynamically(
                                inspectorId, dexPath, connection, environment),
                connection,
                crashListener,
                ArtTooling::clear);
    }

    /**
     * Creates and initializes a new [InspectorBridge] containing the [Inspector] returned by {@code
     * loader}.
     *
     * @param inspectorId the ID of the inspector, used in the name of the bridge's primary thread
     *     and in its hook owner ID
     * @param loader loads the inspector, given the environment the bridge provides to it
     * @param connection the connection that {@link #updateConnection} points to the active session,
     *     and that the inspector should send its events through
     * @param crashListener notified when a task of the inspector crashes, before the bridge is
     *     disposed and the commands waiting for a reply fail
     * @param clearHooks removes the hooks of the given owner ID when the bridge is disposed
     */
    @VisibleForTesting
    static InspectorBridge create(
            String inspectorId,
            InspectorLoader loader,
            AppInspectionUtils.DelegatingConnection connection,
            HandlerThreadExecutor.CrashListener crashListener,
            Consumer<String> clearHooks)
            throws Exception {
        Set<CompletableFuture<byte[]>> pendingCommands = ConcurrentHashMap.newKeySet();
        AtomicReference<InspectorBridge> bridgeReference = new AtomicReference<>();
        // A crash can happen after onReceiveCommand returned, in a task that would have replied,
        // so the commands waiting for a reply fail with it instead of waiting forever. The crash
        // is reported first, so that the host receives it with its stack trace before
        // SessionHandler replies to the command with an error.
        HandlerThreadExecutor.CrashListener bridgeCrashListener =
                t -> {
                    try {
                        crashListener.onCrash(t);
                    } finally {
                        // A crashed inspector can be left in an inconsistent state, so the bridge
                        // is disposed and the next session creates a new one. It is disposed
                        // before the commands fail, so that it is already disposed when they
                        // return. A crash before the bridge is constructed does not dispose it.
                        InspectorBridge bridge = bridgeReference.get();
                        if (bridge != null) {
                            bridge.dispose();
                        }
                        for (CompletableFuture<byte[]> pendingCommand : pendingCommands) {
                            pendingCommand.completeExceptionally(t);
                        }
                    }
                };
        HandlerThreadExecutor primaryExecutor =
                new HandlerThreadExecutor(THREAD_NAME_PREFIX + inspectorId, bridgeCrashListener);
        String hookOwnerId = createHookOwnerId(inspectorId);
        InspectorEnvironment inspectorEnvironment =
                AppInspectionUtils.createInspectorEnvironment(
                        hookOwnerId, primaryExecutor, bridgeCrashListener);

        CompletableFuture<Inspector> future = new CompletableFuture<>();
        primaryExecutor.execute(
                () -> {
                    try {
                        // Instantiate the inspector on the primary executor thread so that its
                        // internally captured Thread.currentThread() matches the thread used for
                        // executing subsequent commands.
                        // This respects the convention from AppInspection.
                        Inspector inspector = loader.load(inspectorEnvironment);
                        future.complete(inspector);
                    } catch (Throwable t) {
                        future.completeExceptionally(t);
                    }
                });

        Inspector inspector;
        try {
            inspector = future.get();
        } catch (InterruptedException e) {
            // In case of interruption, clean up resources to prevent background thread leaks.
            primaryExecutor.quitSafely();
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            // Ensure the executor is closed to clean up the HandlerThread if initialization fails.
            primaryExecutor.quitSafely();
            // Unwrap ExecutionException to propagate the original initialization failure cause
            // directly.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new RuntimeException(cause);
        }

        InspectorBridge bridge =
                new InspectorBridge(
                        hookOwnerId,
                        inspector,
                        connection,
                        primaryExecutor,
                        clearHooks,
                        pendingCommands);
        bridgeReference.set(bridge);
        return bridge;
    }

    /**
     * Waits up to {@code timeout} for the bridge's primary thread to finish after {@link #dispose},
     * and returns whether it finished.
     */
    @VisibleForTesting
    boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return primaryExecutor.awaitTermination(timeout, unit);
    }

    /**
     * Creates a hook owner ID that is unique in the process. ART Tooling keeps one hook registry
     * per process, and several servers can each hold an inspector with the same inspector ID. A
     * random UUID stays unique even across payload versions, whose classes do not share state.
     */
    private static String createHookOwnerId(String inspectorId) {
        return inspectorId + "/" + UUID.randomUUID();
    }

    /** Loads the inspector of a bridge, given the environment the bridge provides to it. */
    @VisibleForTesting
    interface InspectorLoader {
        Inspector load(InspectorEnvironment environment) throws Exception;
    }
}
