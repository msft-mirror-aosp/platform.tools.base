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

import com.android.tools.idea.protobuf.ByteString;
import com.android.tools.ui.inspector.common.FramingProtocol;
import com.android.tools.ui.inspector.common.ProtocolConstants;
import com.android.tools.ui.inspector.payload.appinspection.AppInspectionUtils;
import com.android.tools.ui.inspector.payload.appinspection.HandlerThreadExecutor;
import com.android.tools.ui.inspector.protocol.UiInspectorProtocol;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Handles a single connection session with the host. */
public final class SessionHandler {
    private static final String TAG = ProtocolConstants.LOG_TAG_PREFIX + ".SessionHandler";

    private final InputStream inputStream;
    private final OutputStream outputStream;
    private final HandlerThreadExecutor.CrashListener crashListener;
    private final CountDownLatch shutdownLatch;
    private final Map<String, InspectorBridge> inspectorBridges;
    private final ClassLoader classLoader;

    public SessionHandler(
            InputStream inputStream,
            OutputStream outputStream,
            HandlerThreadExecutor.CrashListener crashListener,
            CountDownLatch shutdownLatch,
            Map<String, InspectorBridge> inspectorBridges) {
        this(
                inputStream,
                outputStream,
                crashListener,
                shutdownLatch,
                inspectorBridges,
                SessionHandler.class.getClassLoader());
    }

    @VisibleForTesting
    public SessionHandler(
            InputStream inputStream,
            OutputStream outputStream,
            HandlerThreadExecutor.CrashListener crashListener,
            CountDownLatch shutdownLatch,
            Map<String, InspectorBridge> inspectorBridges,
            ClassLoader classLoader) {
        this.inputStream = inputStream;
        this.outputStream = outputStream;
        this.crashListener = crashListener;
        this.shutdownLatch = shutdownLatch;
        this.inspectorBridges = inspectorBridges;
        this.classLoader = classLoader;
    }

    /**
     * Reads and executes a continuous stream of commands from the host. Loops until the connection
     * is broken or a shutdown command is received.
     */
    public void processCommands() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                byte[] commandBytes = FramingProtocol.readMessage(inputStream);
                UiInspectorProtocol.Command command =
                        UiInspectorProtocol.Command.parseFrom(commandBytes);
                int commandId = command.getCommandId();

                boolean shouldTerminate = handleCommand(command, commandId);
                if (shouldTerminate) {
                    break;
                }
            }
        } catch (EOFException e) {
            Log.i(TAG, "Client disconnected (EOF)");
        } catch (IOException e) {
            Log.i(TAG, "Client connection lost: " + e.getMessage());
        } catch (Throwable t) {
            Log.e(TAG, "Error handling client session", t);
            crashListener.onCrash(t);
        } finally {
            // Clear active connections in each inspector
            for (InspectorBridge bridge : inspectorBridges.values()) {
                bridge.updateConnection(null);
            }
        }
    }

    /**
     * Handles a single command and returns whether to terminate the session. Every command must
     * receive a response.
     *
     * <p>Running the command, including serializing its response, is kept apart from writing the
     * response, so that only a failure to write it, which means the connection to the host is lost,
     * ends the session. A failure of the command, whatever its type, is replied as an error.
     */
    private boolean handleCommand(UiInspectorProtocol.Command command, int commandId)
            throws IOException {
        byte[] message;
        try {
            UiInspectorProtocol.Response.Builder response;
            switch (command.getSpecializedCase()) {
                case INSPECTOR_MESSAGE:
                    response = handleInspectorMessage(command.getInspectorMessage());
                    break;
                case CREATE_INSPECTOR:
                    response = handleCreateInspector(command.getCreateInspector());
                    break;
                case SHUTDOWN:
                    response = handleShutdown();
                    break;
                case GET_VERSION:
                    response = handleGetVersion(command.getGetVersion());
                    break;
                default:
                    throw new IllegalStateException(
                            "Unhandled top-level command: " + command.getSpecializedCase());
            }
            response.setStatus(UiInspectorProtocol.Response.Status.SUCCESS);
            message = toAgentMessage(commandId, response);
        } catch (Throwable t) {
            Log.e(TAG, "Error handling command", t);
            message = toAgentMessage(commandId, errorResponse("Error handling command: " + t));
        }
        boolean isShutdown =
                command.getSpecializedCase()
                        == UiInspectorProtocol.Command.SpecializedCase.SHUTDOWN;
        try {
            FramingProtocol.writeMessage(outputStream, message);
        } finally {
            // The host asked to shut down: failing to acknowledge it does not cancel the shutdown.
            // The server shuts down after the write, so that it is not torn down while replying.
            if (isShutdown) {
                shutdownLatch.countDown();
            }
        }
        return isShutdown;
    }

    private UiInspectorProtocol.Response.Builder handleInspectorMessage(
            UiInspectorProtocol.InspectorMessageCommand message) throws Exception {
        InspectorBridge inspectorBridge = inspectorBridges.get(message.getInspectorId());
        if (inspectorBridge == null) {
            throw new IllegalStateException("Unknown inspector ID: " + message.getInspectorId());
        }
        if (inspectorBridge.isDisposed()) {
            throw new IllegalStateException(
                    "Inspector " + message.getInspectorId() + " crashed and was disposed");
        }
        byte[] responseBytes = inspectorBridge.sendCommand(message.getPayload().toByteArray());

        return UiInspectorProtocol.Response.newBuilder()
                .setInspectorMessage(
                        UiInspectorProtocol.InspectorMessageResponse.newBuilder()
                                .setInspectorId(message.getInspectorId())
                                .setPayload(ByteString.copyFrom(responseBytes))
                                .build());
    }

    private UiInspectorProtocol.Response.Builder handleCreateInspector(
            UiInspectorProtocol.CreateInspectorCommand command) throws Exception {
        String inspectorId = command.getInspectorId();
        String dexPath = command.getDexPath();

        InspectorBridge bridge = inspectorBridges.get(inspectorId);
        if (bridge != null && bridge.isDisposed()) {
            // The inspector crashed: replace it with a new one.
            inspectorBridges.remove(inspectorId, bridge);
            bridge = null;
        }
        if (bridge != null) {
            // Update the bridge with the new session's connection to resume event sending.
            Connection newRealConnection =
                    AppInspectionUtils.createAppInspectionConnection(
                            inspectorId, outputStream, crashListener);
            bridge.updateConnection(newRealConnection);
        } else {
            AppInspectionUtils.DelegatingConnection delegatingConnection =
                    new AppInspectionUtils.DelegatingConnection();
            Connection realConnection =
                    AppInspectionUtils.createAppInspectionConnection(
                            inspectorId, outputStream, crashListener);
            delegatingConnection.activeConnection = realConnection;
            InspectorBridge newBridge =
                    InspectorBridge.create(
                            inspectorId, dexPath, delegatingConnection, crashListener);

            inspectorBridges.put(inspectorId, newBridge);
        }

        return UiInspectorProtocol.Response.newBuilder()
                .setCreateInspector(
                        UiInspectorProtocol.CreateInspectorResponse.newBuilder().build());
    }

    /**
     * Returns the response to a shutdown command. The server shuts down after writing the response,
     * even if the write fails, in {@link #handleCommand}.
     */
    private UiInspectorProtocol.Response.Builder handleShutdown() {
        return UiInspectorProtocol.Response.newBuilder()
                .setShutdown(UiInspectorProtocol.ShutdownResponse.newBuilder().build());
    }

    private UiInspectorProtocol.Response.Builder handleGetVersion(
            UiInspectorProtocol.GetVersionCommand command) {
        UiInspectorProtocol.GetVersionResponse.Builder versionResponse =
                UiInspectorProtocol.GetVersionResponse.newBuilder();
        for (String libraryId : command.getLibraryIdsList()) {
            performVersionDetection(libraryId, versionResponse);
        }

        return UiInspectorProtocol.Response.newBuilder().setGetVersion(versionResponse.build());
    }

    /**
     * Records {@code libraryId} in {@code versionResponse}: in its versions when the library is
     * present and its version can be read, in its libraries without version when the library is
     * present but its version can't be read, and nowhere when the library is absent.
     */
    private void performVersionDetection(
            String libraryId, UiInspectorProtocol.GetVersionResponse.Builder versionResponse) {
        if (!ProtocolConstants.COMPOSE_UI_LIBRARY_ID.equals(libraryId)) {
            throw new IllegalArgumentException("Unsupported library ID: " + libraryId);
        }
        if (!ComposeInspectionUtils.isComposePresent(classLoader)) {
            return;
        }
        String version = ComposeInspectionUtils.readComposeVersion(classLoader);
        if (version != null) {
            versionResponse.putVersions(libraryId, version);
        } else {
            versionResponse.addLibrariesWithoutVersion(libraryId);
        }
    }

    /** Returns an error response with the given message. */
    private static UiInspectorProtocol.Response.Builder errorResponse(String errorMessage) {
        return UiInspectorProtocol.Response.newBuilder()
                .setStatus(UiInspectorProtocol.Response.Status.ERROR)
                .setErrorMessage(errorMessage);
    }

    /** Serializes the response to a command into the message sent to the host. */
    private static byte[] toAgentMessage(
            int commandId, UiInspectorProtocol.Response.Builder response) {
        return UiInspectorProtocol.AgentMessage.newBuilder()
                .setResponse(response.setCommandId(commandId).build())
                .build()
                .toByteArray();
    }
}
