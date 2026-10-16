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

package com.android.tools.ui.inspector.inspectors.view;

import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import androidx.inspection.Connection;
import androidx.inspection.Inspector;
import androidx.inspection.InspectorEnvironment;

import com.android.tools.agent.appinspection.ViewLayoutInspector;
import com.android.tools.agent.appinspection.XrHelper;
import com.android.tools.idea.protobuf.InvalidProtocolBufferException;
import com.android.tools.ui.inspector.inspectors.view.property.PropertyCache;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Command;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Display;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.DumpViewsCommand;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.DumpViewsResponse;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.Response;
import com.android.tools.ui.inspector.view.inspector.protocol.ViewInspectorProtocol.WindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ViewInspector extends Inspector {
    /** How long a dump waits for the main thread to start it before failing. */
    private static final long MAIN_THREAD_TIMEOUT_SECONDS = 60;

    /** How many frames of the main thread's stack a timeout error shows. */
    private static final int MAX_MAIN_THREAD_FRAMES = 15;

    // Instantiate marker class and hold strong reference so Compose Inspector's reflection contract
    // (matching the class name "com.android.tools.agent.appinspection.ViewLayoutInspector")
    // locates this agent's ClassLoader to find XrHelper on the heap without being garbage
    // collected.
    @SuppressWarnings("unused")
    private final ViewLayoutInspector viewLayoutInspector;

    private final Executor primaryExecutor;
    private final Handler primaryHandler;
    private final Executor mainThreadExecutor = new MainThreadExecutor();
    private final XrHelper xrHelper;
    private final PropertyCache<View> viewPropertyCache = PropertyCache.createViewPropertyCache();
    private final PropertyCache<ViewGroup.LayoutParams> layoutParamsPropertyCache =
            PropertyCache.createLayoutParamsPropertyCache();

    public ViewInspector(Connection connection, InspectorEnvironment environment) {
        super(connection);
        viewLayoutInspector = new ViewLayoutInspector(connection);
        primaryExecutor = environment.executors().primary();
        primaryHandler = environment.executors().handler();
        xrHelper = new XrHelper(environment);
    }

    @Override
    public void onReceiveCommand(byte[] data, CommandCallback callback) {
        Command command;
        try {
            command = Command.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalStateException(e);
        }
        switch (command.getSpecializedCase()) {
            case DUMP_VIEWS_COMMAND:
                handleDumpViewsCommand(command.getDumpViewsCommand(), callback);
                break;
            default:
                throw new IllegalStateException("Unknown command: " + command.getSpecializedCase());
        }
    }

    private void handleDumpViewsCommand(
            DumpViewsCommand dumpViewsCommand, CommandCallback callback) {
        boolean includeAttributes = dumpViewsCommand.getIncludeAttributes();
        boolean includeResolutionStack = dumpViewsCommand.getIncludeResolutionStack();
        primaryExecutor.execute(
                () -> {
                    StringTable stringTable = new StringTable();
                    // Ensures the command gets a single reply: the dump if it starts within the
                    // timeout, otherwise the timeout error. A dump that has started is never cut
                    // off.
                    AtomicBoolean claimed = new AtomicBoolean();
                    Runnable timeout =
                            () -> {
                                if (claimed.compareAndSet(false, true)) {
                                    replyMainThreadTimeout(callback);
                                }
                            };
                    // Posted before the dump, so that the dump can always remove it.
                    primaryHandler.postDelayed(
                            timeout, TimeUnit.SECONDS.toMillis(MAIN_THREAD_TIMEOUT_SECONDS));
                    mainThreadExecutor.execute(
                            () -> {
                                if (!claimed.compareAndSet(false, true)) {
                                    // The main thread was blocked past the timeout and only now
                                    // runs this dump. The timeout has already replied.
                                    return;
                                }
                                primaryHandler.removeCallbacks(timeout);
                                List<WindowInfo> windows;
                                List<Display> displays;
                                try {
                                    List<View> roots = RootsDetector.getRootViews(xrHelper);
                                    windows = new ArrayList<>(roots.size());
                                    for (View root : roots) {
                                        windows.add(
                                                ViewNodes.toWindowInfo(
                                                        root,
                                                        stringTable,
                                                        includeAttributes,
                                                        includeResolutionStack,
                                                        viewPropertyCache,
                                                        layoutParamsPropertyCache));
                                    }
                                    displays =
                                            roots.isEmpty()
                                                    ? Collections.emptyList()
                                                    : ViewNodes.buildDisplayInfo(
                                                            roots.get(0).getContext());
                                } catch (Throwable t) {
                                    primaryExecutor.execute(() -> rethrow(t));
                                    return;
                                }
                                primaryExecutor.execute(
                                        () -> reply(callback, stringTable, windows, displays));
                            });
                });
    }

    private static void reply(
            CommandCallback callback,
            StringTable stringTable,
            List<WindowInfo> windows,
            List<Display> displays) {
        DumpViewsResponse response =
                DumpViewsResponse.newBuilder()
                        .addAllWindows(windows)
                        .addAllDisplays(displays)
                        .addAllStrings(stringTable.toStringEntries())
                        .build();
        callback.reply(Response.newBuilder().setDumpViewsResponse(response).build().toByteArray());
    }

    /**
     * Replies with an error describing what the main thread is doing, so that the host does not
     * wait for a dump that may never start, for example while the app is paused at a breakpoint.
     */
    private static void replyMainThreadTimeout(CommandCallback callback) {
        Thread mainThread = Looper.getMainLooper().getThread();
        StringBuilder error = new StringBuilder();
        error.append("The app's main thread did not respond within ")
                .append(MAIN_THREAD_TIMEOUT_SECONDS)
                .append(" seconds.");
        if (Debug.isDebuggerConnected()) {
            error.append(" A debugger is attached; the app may be paused at a breakpoint.");
        }
        error.append("\nMain thread state: ").append(mainThread.getState());
        StackTraceElement[] frames = mainThread.getStackTrace();
        int shownFrames = Math.min(frames.length, MAX_MAIN_THREAD_FRAMES);
        for (int i = 0; i < shownFrames; i++) {
            error.append("\n    at ").append(frames[i]);
        }
        if (frames.length > shownFrames) {
            error.append("\n    ...");
        }
        DumpViewsResponse response =
                DumpViewsResponse.newBuilder().setErrorMessage(error.toString()).build();
        callback.reply(Response.newBuilder().setDumpViewsResponse(response).build().toByteArray());
    }

    /**
     * Rethrows a dump failure caught on the main thread. Called on the primary executor to report
     * the failure to the host instead of crashing the app.
     */
    private static void rethrow(Throwable t) {
        if (t instanceof RuntimeException) {
            throw (RuntimeException) t;
        }
        if (t instanceof Error) {
            throw (Error) t;
        }
        // Checked exceptions are wrapped, as a Runnable cannot throw them.
        throw new RuntimeException(t);
    }

    @Override
    public void onDispose() {}
}
