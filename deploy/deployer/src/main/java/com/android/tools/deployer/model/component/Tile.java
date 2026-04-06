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
import com.android.tools.deployer.model.activate.ActivationCommand;
import com.android.tools.deployer.model.activate.ActivationCommandResultChecker;
import com.android.tools.deployer.model.activate.ActivationCommands;
import com.android.tools.deployer.model.activate.ActivationContext;
import com.android.tools.deployer.model.activate.BroadcastResultChecker;
import com.android.tools.manifest.parser.components.ManifestServiceInfo;
import com.android.utils.ILogger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Tile extends WearComponent {

    public static class ShellCommand {
        public static String SET_TILE =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " 'add-tile' --ecn component "; // + component name

        public static String UNSET_TILE =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " remove-tile --ecn component "; // + component name

        public static String SHOW_TILE_COMMAND =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation"
                        + " show-tile --ei index "; // + index
    }

    public Tile(@NonNull ManifestServiceInfo info, @NonNull String appId, @NonNull ILogger logger) {
        super(appId, info, logger);
    }

    @Override
    public void activate(
            @NonNull String extraFlags,
            @NonNull Mode activationMode,
            @NonNull IShellOutputReceiver addTileReceiver,
            @NonNull IDevice device)
            throws ModelException {
        validate(extraFlags);
        logger.info(
                "Activating Tile '%s' %s",
                info.getQualifiedName(), activationMode.equals(Mode.DEBUG) ? "for debug" : "");

        if (activationMode.equals(Mode.DEBUG)) {
            setUpAmDebugApp(device);
            setUpDebugSurfaceDebugApp(device);
        }
        String command = getStartTileCommand();
        runStartCommand(command, addTileReceiver, logger, device);
    }

    private void validate(String extraFlags) throws ModelException {
        if (!extraFlags.isEmpty()) {
            throw new ModelException(
                    String.format(
                            "Extra flags are not supported by Tile. Detected flags `%s`",
                            extraFlags));
        }
    }

    private int parseIndex(String extraFlags) throws ModelException {
        try {
            return Integer.parseInt(extraFlags.trim());
        } catch (NumberFormatException e) {
            throw new ModelException("Invalid tile index in extra flags: " + extraFlags);
        }
    }

    @Override
    public ActivationCommands getActivationCommands(
            @NonNull String extraFlags, @NonNull Mode activationMode) throws ModelException {
        ActivationContext context = new ActivationContext();
        if (activationMode.equals(Mode.DEBUG)) {
            return new ActivationCommands(
                    getWearDebugSurfaceVersionActivationCommand(context),
                    getSetUpAmDebugAppActivationCommand(context),
                    getSetUpDebugSurfaceDebugAppActivationCommand(context),
                    getSetWatchTileActivationCommand(context),
                    getShowTileActivationCommand(context));
        } else {
            return new ActivationCommands(
                    getWearDebugSurfaceVersionActivationCommand(context),
                    getSetWatchTileActivationCommand(context),
                    getShowTileActivationCommand(context));
        }
    }

    private ActivationCommand getWearDebugSurfaceVersionActivationCommand(
            ActivationContext context) {
        return new ActivationCommand(
                WearComponent.ShellCommand.GET_WEAR_DEBUG_SURFACE_VERSION,
                "Checking Wear OS Surface API version",
                new WearDebugSurfaceVersionChecker(logger, context),
                context);
    }

    static class WearDebugSurfaceVersionChecker extends ActivationCommandResultChecker {
        private int version = -1;
        private int resultCode = -1;
        private final Pattern versionPattern = Pattern.compile("data=\"(\\d+)\"");
        private final Pattern resultCodePattern = Pattern.compile("result=(\\d+)");
        private final ILogger logger;

        public WearDebugSurfaceVersionChecker(ILogger logger, ActivationContext context) {
            super(null, msg -> logger.warning(msg), context);
            this.logger = logger;
        }

        @Override
        public void processLines(String[] lines) {
            for (String line : lines) {
                Matcher matcher = versionPattern.matcher(line);
                if (matcher.find()) {
                    version = Integer.parseInt(matcher.group(1));
                }
                matcher = resultCodePattern.matcher(line);
                if (matcher.find()) {
                    resultCode = Integer.parseInt(matcher.group(1));
                }
            }
        }

        @Override
        public Status check() {
            if (resultCode != 1) {
                reportError("Broadcast failed with result=" + resultCode);
                return Status.ERROR;
            }
            if (version < 2) {
                reportError("Wear OS Surface API version too low: " + version);
                return Status.ERROR;
            }
            return Status.SUCCESS;
        }
    }

    private ActivationCommand getShowTileActivationCommand(ActivationContext context) {
        return new ActivationCommand(
                ShellCommand.SHOW_TILE_COMMAND + "${tile_index}",
                "Showing Tile",
                new BroadcastResultChecker(null, msg -> logger.warning(msg), context),
                context);
    }

    @NonNull
    private String getStartTileCommand() {
        return ShellCommand.SET_TILE + getFQEscapedName();
    }

    private ActivationCommand getSetWatchTileActivationCommand(ActivationContext context) {
        return new ActivationCommand(
                getStartTileCommand(),
                "Setting Tile for " + appId,
                new SetWatchTileResultChecker(null, msg -> logger.warning(msg), context),
                context);
    }

    static class SetWatchTileResultChecker extends BroadcastResultChecker {
        private int index = -1;
        private final Pattern indexPattern = Pattern.compile("Index=\\[(\\d+)]");

        public SetWatchTileResultChecker(
                java.util.function.Consumer<String> onWarning,
                java.util.function.Consumer<String> onError,
                ActivationContext context) {
            super(onWarning, onError, context);
        }

        @Override
        public void processLines(String[] lines) {
            super.processLines(lines);
            for (String line : lines) {
                Matcher matcher = indexPattern.matcher(line);
                if (matcher.find()) {
                    index = Integer.parseInt(matcher.group(1));
                    getContext().put("tile_index", String.valueOf(index));
                }
            }
        }

        public int getIndex() {
            return index;
        }
    }
}
