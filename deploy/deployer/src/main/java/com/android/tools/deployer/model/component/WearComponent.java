/*
 * Copyright (C) 2022 The Android Open Source Project
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
import com.android.tools.deployer.model.activate.ActivationCommand;
import com.android.tools.deployer.model.activate.ActivationContext;
import com.android.tools.deployer.model.activate.AmDebugAppResultChecker;
import com.android.tools.deployer.model.activate.BroadcastResultChecker;
import com.android.tools.manifest.parser.components.ManifestAppComponentInfo;
import com.android.utils.ILogger;

import org.jetbrains.annotations.NotNull;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class WearComponent extends AppComponent {

    public static class ShellCommand {
        public static final String GET_WEAR_DEBUG_SURFACE_VERSION =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " version";
        public static final String DEBUG_SURFACE_SET_DEBUG_APP =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " set-debug-app --es package"; // + package name

        public static final String AM_SET_DEBUG_APP = "am set-debug-app -w";
    }

    protected WearComponent(
            @NonNull String appId,
            @NonNull ManifestAppComponentInfo info,
            @NonNull ILogger logger) {
        super(appId, info, logger);
    }

    protected ActivationCommand getSetUpAmDebugAppActivationCommand() {
        return getSetUpAmDebugAppActivationCommand(new ActivationContext());
    }

    protected ActivationCommand getSetUpAmDebugAppActivationCommand(ActivationContext context) {
        return new ActivationCommand(
                String.format("%s '%s'", ShellCommand.AM_SET_DEBUG_APP, appId),
                "Setting debug app for " + appId,
                new AmDebugAppResultChecker(null, msg -> logger.warning(msg)),
                context);
    }

    protected ActivationCommand getSetUpDebugSurfaceDebugAppActivationCommand() {
        return getSetUpDebugSurfaceDebugAppActivationCommand(new ActivationContext());
    }

    protected ActivationCommand getSetUpDebugSurfaceDebugAppActivationCommand(
            ActivationContext context) {
        return new ActivationCommand(
                String.format("%s '%s'", ShellCommand.DEBUG_SURFACE_SET_DEBUG_APP, appId),
                "Setting debug app in Debug Surface for " + appId,
                new BroadcastResultChecker(null, msg -> logger.warning(msg)),
                context);
    }
}
