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
package com.android.tools.deployer.model.component;

import com.android.annotations.NonNull;
import com.android.tools.deployer.model.ModelException;
import com.android.tools.deployer.model.activate.ActivationCommand;
import com.android.tools.deployer.model.activate.ActivationCommandResultChecker;
import com.android.tools.deployer.model.activate.ActivationCommands;
import com.android.tools.deployer.model.activate.ActivationContext;
import com.android.tools.deployer.model.activate.BroadcastResultChecker;
import com.android.tools.manifest.parser.components.ManifestServiceInfo;
import com.android.utils.ILogger;

import com.google.common.base.Strings;

import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WearWidget extends WearComponent {

    public static final String REQUIRED_PROTOLAYOUT_RENDERER_MIN_VERSION = "1.6.1";
    private static final String PROTOLAYOUT_RENDERER_REQUIREMENTS =
            "Use an emulator with Wear 7 or a physical device with automatic updates from the play"
                + " store. Read more at"
                + " https://developer.android.com/training/wearables/widgets/get_started#runtime-requirements";

    public static class ShellCommand {
        // Widgets use the same commands as tiles, cf
        // https://developer.android.com/training/wearables/widgets/get_started#add-preview-widget
        public static String SET_WEAR_WIDGET =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " add-tile --ecn component "; // + component name

        public static String UNSET_WEAR_WIDGET =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SURFACE --es operation"
                        + " remove-tile --ecn component "; // + component name

        public static String SHOW_WEAR_WIDGET_COMMAND =
                "am broadcast -a com.google.android.wearable.app.DEBUG_SYSUI --es operation"
                        + " show-tile --ei index "; // + index

        public static String GET_PROTOLAYOUT_RENDERER_VERSION =
                "dumpsys package com.google.android.wearable.protolayout.renderer";
    }

    public WearWidget(
            @NonNull ManifestServiceInfo info, @NonNull String appId, @NonNull ILogger logger) {
        super(appId, info, logger);
    }

    protected void validate(String extraFlags) throws ModelException {
        if (!extraFlags.isEmpty()) {
            throw new ModelException(
                    String.format(
                            "Extra flags are not supported by WearWidget. Detected flags `%s`",
                            extraFlags));
        }
    }

    @NonNull
    protected String getStartWearWidgetCommand() {
        return ShellCommand.SET_WEAR_WIDGET + getFQEscapedName();
    }

    @Override
    public ActivationCommands getActivationCommands(
            @NonNull String extraFlags, @NonNull Mode activationMode) throws ModelException {
        ActivationContext context = new ActivationContext();
        if (activationMode.equals(Mode.DEBUG)) {
            return new ActivationCommands(
                    getWearDebugSurfaceVersionActivationCommand(context),
                    getProtolayoutRendererVersionActivationCommand(context),
                    getSetUpAmDebugAppActivationCommand(context),
                    getSetUpDebugSurfaceDebugAppActivationCommand(context),
                    getSetWearWidgetActivationCommand(context),
                    getShowWearWidgetActivationCommand(context));
        } else {
            return new ActivationCommands(
                    getWearDebugSurfaceVersionActivationCommand(context),
                    getProtolayoutRendererVersionActivationCommand(context),
                    getSetWearWidgetActivationCommand(context),
                    getShowWearWidgetActivationCommand(context));
        }
    }

    private ActivationCommand getWearDebugSurfaceVersionActivationCommand(
            ActivationContext context) {
        return new ActivationCommand(
                WearComponent.ShellCommand.GET_WEAR_DEBUG_SURFACE_VERSION,
                "Checking Wear OS Surface API version",
                // Widgets use the same debug surface as tiles
                new Tile.WearDebugSurfaceVersionChecker(logger, context),
                context);
    }

    private ActivationCommand getShowWearWidgetActivationCommand(ActivationContext context) {
        return new ActivationCommand(
                ShellCommand.SHOW_WEAR_WIDGET_COMMAND + "${wear_widget_index}",
                "Showing Wear Widget",
                new BroadcastResultChecker(null, msg -> logger.warning(msg), context),
                context);
    }

    private ActivationCommand getSetWearWidgetActivationCommand(ActivationContext context) {
        return new ActivationCommand(
                getStartWearWidgetCommand(),
                "Setting Wear Widget for " + appId,
                new SetWearWidgetResultChecker(null, msg -> logger.warning(msg), context),
                context);
    }

    static class SetWearWidgetResultChecker extends BroadcastResultChecker {
        private int index = -1;
        private final Pattern indexPattern = Pattern.compile("Index=\\[(\\d+)]");

        public SetWearWidgetResultChecker(
                Consumer<String> onWarning, Consumer<String> onError, ActivationContext context) {
            super(onWarning, onError, context);
        }

        @Override
        public void processLines(String[] lines) {
            super.processLines(lines);
            for (String line : lines) {
                Matcher matcher = indexPattern.matcher(line);
                if (matcher.find()) {
                    index = Integer.parseInt(matcher.group(1));
                    getContext().put("wear_widget_index", String.valueOf(index));
                }
            }
        }

        public int getIndex() {
            return index;
        }
    }

    private ActivationCommand getProtolayoutRendererVersionActivationCommand(
            ActivationContext context) {
        return new ActivationCommand(
                ShellCommand.GET_PROTOLAYOUT_RENDERER_VERSION,
                "Checking Protolayout Renderer version",
                new ProtoLayoutRendererVersionChecker(logger, context),
                context);
    }

    private static class ProtoLayoutRendererVersionChecker extends ActivationCommandResultChecker {
        private String version = "";
        private final Pattern versionPattern = Pattern.compile("versionName=(\\S+)");
        private final ILogger logger;

        public ProtoLayoutRendererVersionChecker(ILogger logger, ActivationContext context) {
            super(null, msg -> logger.warning(msg), context);
            this.logger = logger;
        }

        @Override
        public void processLines(String[] lines) {
            for (String line : lines) {
                Matcher matcher = versionPattern.matcher(line);
                if (matcher.find()) {
                    version = matcher.group(1);
                }
            }
        }

        @Override
        public Status check() {
            if (Strings.isNullOrEmpty(version)) {
                reportError(
                        "Protolayout Renderer version not found. "
                                + PROTOLAYOUT_RENDERER_REQUIREMENTS);
                return Status.ERROR;
            }
            if (!isProtolayoutVersionAtLeast(version, REQUIRED_PROTOLAYOUT_RENDERER_MIN_VERSION)) {
                reportError(
                        "Protolayout Renderer version too low: "
                                + version
                                + ". Must be at least "
                                + REQUIRED_PROTOLAYOUT_RENDERER_MIN_VERSION
                                + ". "
                                + PROTOLAYOUT_RENDERER_REQUIREMENTS);
                return Status.ERROR;
            }
            return Status.SUCCESS;
        }
    }

    /** Inline implementation of a version check to limit dependencies in android-cli. */
    public static boolean isProtolayoutVersionAtLeast(String version, String minVersion) {
        if (version == null) {
            return false;
        }
        if (minVersion == null) {
            return true;
        }
        String cleanVersion = version.replace("\"", "").trim();
        String cleanMinVersion = minVersion.replace("\"", "").trim();

        String[] parts1 = cleanVersion.split("-", 2);
        String[] parts2 = cleanMinVersion.split("-", 2);

        String[] base1 = parts1[0].split("\\.");
        String[] base2 = parts2[0].split("\\.");

        int length = Math.max(base1.length, base2.length);
        for (int i = 0; i < length; i++) {
            int v1 = 0;
            if (i < base1.length) {
                try {
                    v1 = Integer.parseInt(base1[i]);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
            int v2 = 0;
            if (i < base2.length) {
                try {
                    v2 = Integer.parseInt(base2[i]);
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
            if (v1 != v2) {
                return v1 > v2;
            }
        }

        // Base versions are equal. Now check suffixes.
        if (parts1.length > 1 && parts2.length == 1) {
            // cleanVersion is a pre-release, cleanMinVersion is a release (e.g. 1.6.1-alpha01 vs
            // 1.6.1)
            return false;
        }
        if (parts2.length > 1 && parts1.length == 1) {
            // cleanVersion is a release, cleanMinVersion is a pre-release (e.g. 1.6.1 vs
            // 1.6.1-alpha01)
            return true;
        }
        if (parts1.length > 1 && parts2.length > 1) {
            // Both are pre-releases, compare them lexicographically
            return parts1[1].compareTo(parts2[1]) >= 0;
        }

        return true;
    }
}
