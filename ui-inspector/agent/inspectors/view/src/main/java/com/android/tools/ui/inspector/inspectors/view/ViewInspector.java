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

public final class ViewInspector extends Inspector {
    // Instantiate marker class and hold strong reference so Compose Inspector's reflection contract
    // (matching the class name "com.android.tools.agent.appinspection.ViewLayoutInspector")
    // locates this agent's ClassLoader to find XrHelper on the heap without being garbage
    // collected.
    @SuppressWarnings("unused")
    private final ViewLayoutInspector viewLayoutInspector;

    private final Executor primaryExecutor;
    private final Executor mainThreadExecutor = new MainThreadExecutor();
    private final XrHelper xrHelper;
    private final PropertyCache<View> viewPropertyCache = PropertyCache.createViewPropertyCache();
    private final PropertyCache<ViewGroup.LayoutParams> layoutParamsPropertyCache =
            PropertyCache.createLayoutParamsPropertyCache();

    public ViewInspector(Connection connection, InspectorEnvironment environment) {
        super(connection);
        viewLayoutInspector = new ViewLayoutInspector(connection);
        primaryExecutor = environment.executors().primary();
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
                    mainThreadExecutor.execute(
                            () -> {
                                try {
                                    List<View> roots = RootsDetector.getRootViews(xrHelper);
                                    List<WindowInfo> windows = new ArrayList<>(roots.size());
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
                                    List<Display> displays =
                                            roots.isEmpty()
                                                    ? Collections.emptyList()
                                                    : ViewNodes.buildDisplayInfo(
                                                            roots.get(0).getContext());
                                    primaryExecutor.execute(
                                            () -> {
                                                try {
                                                    reply(callback, stringTable, windows, displays);
                                                } catch (Throwable t) {
                                                    reportUncaught(t);
                                                }
                                            });
                                } catch (Throwable t) {
                                    primaryExecutor.execute(() -> reportUncaught(t));
                                }
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
     * Reports a dump failure through the primary thread's uncaught exception handler. Invoking the
     * handler directly (instead of letting the throwable escape the runnable) bypasses the primary
     * executor's catch-all crash reporting, keeping inspector failures fatal to the app process.
     */
    private static void reportUncaught(Throwable t) {
        Thread thread = Thread.currentThread();
        thread.getUncaughtExceptionHandler().uncaughtException(thread, t);
    }

    @Override
    public void onDispose() {}
}
