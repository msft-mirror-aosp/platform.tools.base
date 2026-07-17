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
package com.android.tools.deployer.modelv1.component;

import com.android.annotations.NonNull;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.ddmlib.MultiLineReceiver;
import com.android.ddmlib.MultiReceiver;
import com.android.tools.deployer.common.DeviceHolder;
import com.android.tools.deployer.model.ModelException;
import com.android.tools.deployer.model.component.WearComponent;
import com.android.utils.ILogger;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface WearComponentV1 extends AppComponentV1 {

    public static class DebugCommandReceiver extends MultiLineReceiver {
        private final @NotNull Pattern exceptionPattern = Pattern.compile("(Exception)");
        private boolean exceptionStatus = false;

        public boolean hasException() {
            return exceptionStatus;
        }

        @Override
        public void processNewLines(@NotNull String[] lines) {
            for (String line : lines) {
                Matcher matcher = exceptionPattern.matcher(line);
                if (matcher.find()) {
                    exceptionStatus = true;
                }
            }
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }

    default void setUpAmDebugApp(@NonNull DeviceHolder device, String appId) throws ModelException {
        DebugCommandReceiver amReceiver = new DebugCommandReceiver();
        runShellCommand(
                String.format("%s '%s'", WearComponent.ShellCommand.AM_SET_DEBUG_APP, appId),
                amReceiver,
                device);
        if (amReceiver.hasException()) {
            throw new ModelException("Activity Manager failed to set up the app for debugging.");
        }
    }

    default void setUpDebugSurfaceDebugApp(
            @NonNull DeviceHolder device, String appId, ILogger logger) throws ModelException {
        CommandResultReceiverV1 surfaceReceiver = new CommandResultReceiverV1();
        runShellCommand(
                String.format(
                        "%s '%s'", WearComponent.ShellCommand.DEBUG_SURFACE_SET_DEBUG_APP, appId),
                surfaceReceiver,
                device);
        if (surfaceReceiver.getResultCode() != CommandResultReceiverV1.SUCCESS_CODE) {
            logger.warning("Warning: Debug Surface failed to set the debug app.");
        }
    }

    default void runStartCommand(
            @NonNull String command,
            @NonNull IShellOutputReceiver receiver,
            @NonNull ILogger logger,
            @NonNull DeviceHolder device)
            throws ModelException {
        logger.info("$ adb shell " + command);
        CommandResultReceiverV1 resultReceiver = new CommandResultReceiverV1();
        MultiReceiver multiReceiver = new MultiReceiver(resultReceiver, receiver);
        runShellCommand(command, multiReceiver, device);
        if (resultReceiver.getResultCode() != CommandResultReceiverV1.SUCCESS_CODE) {
            throw new ModelException(
                    String.format("Invalid Success code `%d`", resultReceiver.getResultCode()));
        }
    }
}
