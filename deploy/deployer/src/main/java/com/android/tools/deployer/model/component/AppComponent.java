/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.tools.deployer.model.component;

import com.android.annotations.NonNull;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.IShellOutputReceiver;
import com.android.tools.deployer.model.ModelException;
import com.android.tools.deployer.model.activate.ActivationCommands;
import com.android.tools.manifest.parser.components.ManifestAppComponentInfo;
import com.android.utils.ILogger;

import java.util.concurrent.TimeUnit;

public abstract class AppComponent {
    @NonNull public final String appId;

    @NonNull public final ManifestAppComponentInfo info;

    @NonNull protected final ILogger logger;

    // The timeout is quite large to accommodate ARM emulators.
    private final long SHELL_TIMEOUT = 15;

    private final TimeUnit SHELL_TIMEUNIT = TimeUnit.SECONDS;

    /**
     * IMPORTANT! ---------- The model API will be completely implementation free. It will provide
     * information about the APK, services it has and what sort of ADB command needs to activate
     * each service.
     *
     * <p>This mean each component should not directly address ddmlib objects anymore!
     *
     * <p>We are going to perform this migration step-wise by removing activate() and then replace
     * it with calls to getActivationCommands. If the caller wants to error handle differently per
     * individual command, they cal also call getXXXCommands(). For example, the previous
     * setUpWatchFace() would now have a getSetUpWatchFaceCommand() so we can customize which error
     * goes where.
     */
    public abstract ActivationCommands getActivationCommands(
            @NonNull String extraFlags, Mode activationMode) throws ModelException;

    protected String getFQEscapedName() {
        return getFQEscapedName(appId, info.getQualifiedName());
    }

    protected AppComponent(
            @NonNull String appId,
            @NonNull ManifestAppComponentInfo info,
            @NonNull ILogger logger) {
        this.appId = appId;
        this.info = info;
        this.logger = logger;
    }

    public abstract void activate(
            @NonNull String extraFlags,
            Mode activationMode,
            @NonNull IShellOutputReceiver receiver,
            @NonNull IDevice device)
            throws ModelException;

    protected void runShellCommand(
            @NonNull String command,
            @NonNull IShellOutputReceiver receiver,
            @NonNull IDevice device)
            throws ModelException {
        try {
            device.executeShellCommand(command, receiver, SHELL_TIMEOUT, SHELL_TIMEUNIT);
        } catch (Exception e) {
            throw new ModelException(e.getMessage());
        }
    }

    @NonNull
    public static String getFQEscapedName(@NonNull String appId, @NonNull String componentFqName) {
        // Escape name declared as inner class name (resulting in foo.bar.Activity$SubActivity).
        return appId + "/" + componentFqName.replace("$", "\\$");
    }

    public enum Mode {
        RUN,
        DEBUG
    }

}
