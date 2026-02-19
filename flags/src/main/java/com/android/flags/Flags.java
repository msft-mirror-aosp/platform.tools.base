/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.flags;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.flags.overrides.InMemoryFlagValueContainer;
import com.android.flags.overrides.PropertyOverrides;

import org.jetbrains.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Class which represents a collection of flags, usually for a whole program.
 *
 * <p>The recommended way to use this class is by creating a container class, as follows, with a
 * list of publicly exposed static flags:
 *
 * <pre>
 *     public final class GameFlags {
 *         private static final Flags FLAGS = new Flags();
 *
 *         private static final FlagGroup AUDIO = new FlagGroup(FLAGS, "audio", "Audio");
 *         private static final FlagGroup GRAPHICS = new FlagGroup(FLAGS<, "graphics", "Graphics");
 *
 *         public static final Flag{Boolean} USE_3D_AUDIO = new BooleanFlag(AUDIO, ...);
 *         public static final Flag{Integer} FPS_CAP = new IntFlag(GRAPHICS, ...);
 *     }
 *
 *     // Elsewhere...
 *     if (GameFlags.USE_3D_AUDIO.get()) {
 *         ...
 *     }
 * </pre>
 *
 * Flags values come from several places. There is the {@link Flag#getDefault()}, but this class
 * provide overrides over this default value. Inside this class values can come from 3 different
 * types of sources (in decreasing order of priority) - {@link Flags#getUserOverrides()}: a mutable
 * container of flag values, generally used for user-settable flag override - a list of {@link
 * Flags#fallbackProviders} for read-only overrides coming from other places (e.g. server side
 * override, properties) - {@link Flags#fileBasedDefaultProvider} for a read-only file-based source
 * of default. This is generally only used for boolean flags. See {@link
 * com.android.tools.idea.flags.overrides.FeatureConfigurationProvider}
 */
public final class Flags {
    private final Map<String, Flag<?>> registeredFlags =
            Collections.synchronizedMap(new HashMap<>());

    /**
     * This provider reads default flag values from a file embedded in Studio. It's used as a last
     * resort when querying for values as other override must take precedence.
     */
    private final FlagValueProvider fileBasedDefaultProvider;

    /** A container of flags to record user-overridden flag values */
    private final FlagValueContainer userOverrides;

    /**
     * An array of flag value providers that give access to some automatic overrides, for example
     * using server-flags.
     *
     * <p>They are used as fallbacks if the {@link Flags#userOverrides} does not contain the
     * requested flag
     */
    private final FlagValueProvider[] fallbackProviders;

    /**
     * Construct a new collection of flags, providing both a main, mutable {@link
     * FlagValueContainer} and a list of 0 or more fallback {@link FlagValueContainer}. The fallback
     * overrides will be checked in the order they were added.
     *
     * <p>It is likely you will want to pass in at least a {@link PropertyOverrides} instance as a
     * fallback handler, enabling flag defaults to be specified on the command line.
     */
    public Flags(
            @NonNull FlagValueProvider fileBasedDefaultProvider,
            @NonNull FlagValueContainer userOverrides,
            FlagValueProvider... fallbackProviders) {
        this.fileBasedDefaultProvider = fileBasedDefaultProvider;
        this.userOverrides = userOverrides;
        this.fallbackProviders = fallbackProviders;
    }

    @VisibleForTesting
    public Flags(FlagValueProvider... immutableOverrides) {
        this(
                new InMemoryFlagValueContainer("fileBasedDefault"),
                new InMemoryFlagValueContainer("user_overrides"),
                immutableOverrides);
    }

    /**
     * The container of user-set overrides.
     *
     * <p>This container does not include any of the fallback providers, or the file based default
     * provider.
     */
    @NonNull
    public FlagValueContainer getUserOverrides() {
        return userOverrides;
    }

    /**
     * Returns a flag value, if any, or {@code null} if the value is not returned by any of the
     * {@link FlagValueContainer} instances.
     *
     * <p>This does not include the default value set as {@link Flag#getDefault()}.
     *
     * <p>To set a flag, overriding its default, use {@link #getUserOverrides()} and set it through
     * its API.
     */
    @Nullable
    String getValue(@NonNull Flag<?> flag) {
        String flagValue = userOverrides.get(flag);
        if (flagValue != null) {
            return flagValue;
        }

        for (FlagValueProvider flagOverrides : fallbackProviders) {
            flagValue = flagOverrides.get(flag);
            if (flagValue != null) {
                return flagValue;
            }
        }

        return fileBasedDefaultProvider.get(flag);
    }

    /**
     * Registers a new flag with this flag registry.
     *
     * <p>Also verifies that the target flag has a unique ID across all flags registered with this
     * Flags instance.
     *
     * <p>Although it's unlikely one would define flags across multiple threads, this method is
     * still thread-safe just in case.
     */
    void register(@NonNull Flag<?> flag) {
        Flag<?> existingFlag = registeredFlags.putIfAbsent(flag.getId(), flag);
        if (existingFlag != null) {
            throw new IllegalArgumentException(
                    String.format(
                            "Flag \"%s\" shares duplicate ID \"%s\" with flag \"%s\"",
                            flag.getDisplayName(), flag.getId(), existingFlag.getDisplayName()));
        }
    }

    /** Returns the flag with the given ID, or {@code null} if no such flag exists. */
    @Nullable
    public Flag<?> getFlag(String id) {
        return registeredFlags.get(id);
    }

    /** Validates the flags registered with this flags registry. */
    public void validate() {
        for (Flag<?> flag : registeredFlags.values()) {
            flag.validate();
        }
    }

    /**
     * Useful for debugging, reflects the getValue implementation, but keeping shadowed overrides,
     * and also recording the source of the override.
     */
    public String toString() {
        return toString("Flags");
    }

    public String toString(String name) {
        // Map of override source -> (map of flag to overridden value)
        Map<FlagValueProvider, Map<Flag<?>, String>> overrides = new HashMap<>();
        for (Flag<?> flag : registeredFlags.values()) {
            String flagValue = userOverrides.get(flag);
            if (flagValue != null) {
                overrides
                        .computeIfAbsent(userOverrides, item -> new HashMap<>())
                        .put(flag, flagValue);
            }
            for (FlagValueProvider flagOverrides : fallbackProviders) {
                flagValue = flagOverrides.get(flag);
                if (flagValue != null) {
                    overrides
                            .computeIfAbsent(flagOverrides, item -> new HashMap<>())
                            .put(flag, flagValue);
                }
            }
        }

        if (overrides.values().stream().allMatch(Map::isEmpty)) {
            return name + ": No current overrides";
        }

        StringBuilder builder = new StringBuilder(name).append(" with current overrides:");
        Map<Flag<?>, FlagValueProvider> seen = new HashMap<>();

        List<FlagValueProvider> displayOrder = new ArrayList<>();
        displayOrder.add(userOverrides);
        Collections.addAll(displayOrder, fallbackProviders);

        for (FlagValueProvider valueProvider : displayOrder) {
            builder.append("\n  ").append(valueProvider.toString()).append(":");
            Map<Flag<?>, String> thisProvidersOverrides = overrides.get(valueProvider);
            if (thisProvidersOverrides == null) continue;
            for (Flag<?> flag :
                    thisProvidersOverrides.keySet().stream()
                            .sorted(Comparator.comparing(Flag::getId))
                            .toList()) {
                builder.append("\n    ");
                FlagValueProvider overriddenBy = seen.putIfAbsent(flag, valueProvider);
                if (overriddenBy != null) {
                    builder.append("(");
                }
                builder.append(flag.getId()).append("=").append(thisProvidersOverrides.get(flag));
                if (overriddenBy != null) {
                    builder.append(" overridden above by ").append(overriddenBy).append(")");
                }
            }
        }
        return builder.toString();
    }
}
