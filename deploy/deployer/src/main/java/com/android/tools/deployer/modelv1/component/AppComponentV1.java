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
import com.android.tools.deployer.common.DeployerIShellOutputReceiver;
import com.android.tools.deployer.common.DeviceHolder;
import com.android.tools.deployer.model.ModelException;
import com.android.tools.deployer.model.component.AppComponent;

import java.util.concurrent.TimeUnit;

public interface AppComponentV1 {
    // The timeout is quite large to accommodate ARM emulators.
    long SHELL_TIMEOUT = 15;

    TimeUnit SHELL_TIMEUNIT = TimeUnit.SECONDS;

    void activate(
            @NonNull String extraFlags,
            AppComponent.Mode activationMode,
            @NonNull DeployerIShellOutputReceiver receiver,
            @NonNull DeviceHolder device)
            throws ModelException;

    default void runShellCommand(
            @NonNull String command,
            @NonNull DeployerIShellOutputReceiver receiver,
            @NonNull DeviceHolder device)
            throws ModelException {
        try {
            device.executeShellCommand(command, receiver, SHELL_TIMEOUT, SHELL_TIMEUNIT);
        } catch (Exception e) {
            throw new ModelException(e.getMessage());
        }
    }
}
