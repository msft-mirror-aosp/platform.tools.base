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

package com.android.build.gradle.options;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import com.android.annotations.NonNull;
import com.android.build.gradle.internal.fixtures.FakeProviderFactory;
import com.android.tools.analytics.AnalyticsSettings;
import com.android.tools.analytics.AnalyticsSettingsData;

import com.google.common.collect.ImmutableMap;

import groovy.util.Eval;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ProjectOptionsTest {

    private static boolean parseBoolean(Object input) {
        return OptionParsers.parseBoolean("myproperty", input, "project");
    }

    private static void assertFailsToParseBoolean(Object input) {
        try {
            parseBoolean(input);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            // expected.
        }
    }

    private static Integer parseInteger(@NonNull Object input) {
        return new ProjectOptions(
                        new FakeProviderFactory(
                                FakeProviderFactory.getFactory(),
                                ImmutableMap.of("android.injected.build.api", input)))
                .get(IntegerOption.IDE_TARGET_DEVICE_API);
    }

    private static Object asGroovyString(@NonNull Object input) {
        Object output = Eval.x(input, "\"$x\"");
        assertThat(output).isNotInstanceOf(String.class);
        return output;
    }

    @Test
    public void booleanParseTest() {
        assertThat(parseBoolean("true")).isTrue();
        assertThat(parseBoolean("false")).isFalse();
        assertFailsToParseBoolean("foo");
        assertThat(parseBoolean(asGroovyString("true"))).isTrue();
        assertThat(parseBoolean(asGroovyString("false"))).isFalse();
        assertFailsToParseBoolean(asGroovyString("foo"));
        assertThat(parseBoolean(true)).isTrue();
        assertThat(parseBoolean(false)).isFalse();
        assertThat(parseBoolean(1)).isTrue();
        assertThat(parseBoolean(0)).isFalse();
        assertFailsToParseBoolean(-1);
    }

    @Test
    public void booleanSanity() {
        assertThat(BooleanOption.IDE_INVOKED_FROM_IDE.getDefaultValue()).isFalse();

        assertThat(
                        new ProjectOptions(
                                        new FakeProviderFactory(
                                                FakeProviderFactory.getFactory(),
                                                ImmutableMap.of()))
                                .get(BooleanOption.IDE_INVOKED_FROM_IDE))
                .isFalse();

        ImmutableMap<String, Object> gradleProperties =
                ImmutableMap.of("android.injected.invoked.from.ide", "true");
        assertThat(
                        new ProjectOptions(
                                        new FakeProviderFactory(
                                                FakeProviderFactory.getFactory(), gradleProperties))
                                .get(BooleanOption.IDE_INVOKED_FROM_IDE))
                .isTrue();

        assertThat(
                        new ProjectOptions(
                                        new FakeProviderFactory(
                                                FakeProviderFactory.getFactory(), gradleProperties))
                                .getProvider(BooleanOption.IDE_INVOKED_FROM_IDE)
                                .get())
                .isTrue();

        gradleProperties = ImmutableMap.of("android.injected.invoked.from.ide", "false");
        assertThat(
                        new ProjectOptions(
                                        new FakeProviderFactory(
                                                FakeProviderFactory.getFactory(), gradleProperties))
                                .get(BooleanOption.IDE_INVOKED_FROM_IDE))
                .isFalse();

        assertThat(
                        new ProjectOptions(
                                        new FakeProviderFactory(
                                                FakeProviderFactory.getFactory(), gradleProperties))
                                .getProvider(BooleanOption.IDE_INVOKED_FROM_IDE)
                                .get())
                .isFalse();

        try {
            //noinspection ResultOfObjectAllocationIgnored
            gradleProperties = ImmutableMap.of("android.injected.invoked.from.ide", "?");
            new ProjectOptions(
                            new FakeProviderFactory(
                                    FakeProviderFactory.getFactory(), gradleProperties))
                    .get(BooleanOption.IDE_INVOKED_FROM_IDE);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("android.injected.invoked.from.ide");
        }
    }

    @Test
    public void integerSanity() {
        assertThat(IntegerOption.IDE_TARGET_DEVICE_API.getDefaultValue()).isNull();
        assertThat(
                        new ProjectOptions(
                                        new FakeProviderFactory(
                                                FakeProviderFactory.getFactory(),
                                                ImmutableMap.of()))
                                .get(IntegerOption.IDE_TARGET_DEVICE_API))
                .isNull();

        assertThat(parseInteger("20")).isEqualTo(20);
        assertThat(parseInteger(21)).isEqualTo(21);
        assertThat(parseInteger(22L)).isEqualTo(22);
        assertThat(parseInteger(asGroovyString(23))).isEqualTo(23);

        try {
            //noinspection ResultOfObjectAllocationIgnored
            new ProjectOptions(
                            new FakeProviderFactory(
                                    FakeProviderFactory.getFactory(),
                                    ImmutableMap.of("android.injected.build.api", new Object())))
                    .get(IntegerOption.IDE_TARGET_DEVICE_API);
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("android.injected.build.api");
        }
    }

    @Test
    public void stringSanity() {
        assertThat(StringOption.IDE_BUILD_TARGET_ABI.getDefaultValue()).isNull();
        assertThat(
                        new ProjectOptions(
                                        new FakeProviderFactory(
                                                FakeProviderFactory.getFactory(),
                                                ImmutableMap.of()))
                                .get(StringOption.IDE_BUILD_TARGET_ABI))
                .isNull();

        ProjectOptions options =
                new ProjectOptions(
                        new FakeProviderFactory(
                                FakeProviderFactory.getFactory(),
                                ImmutableMap.of(
                                        "android.injected.build.abi", asGroovyString("x86"))));
        assertThat(options.get(StringOption.IDE_BUILD_TARGET_ABI)).isEqualTo("x86");
        assertThat(options.getProvider(StringOption.IDE_BUILD_TARGET_ABI).get()).isEqualTo("x86");
    }

    @Test
    public void ensureUniqueness() {
        List<String> optionsNames =
                Stream.of(
                                BooleanOption.values(),
                                OptionalBooleanOption.values(),
                                IntegerOption.values(),
                                StringOption.values(),
                                ReplacedOption.values())
                        .flatMap(Arrays::stream)
                        .map(option -> option.getPropertyName())
                        .collect(Collectors.toList());

        assertThat(optionsNames).containsNoDuplicates();
    }

    @Test
    public void checkAnalyticsEnabledCondition() {
        AnalyticsSettingsData settingsData = new AnalyticsSettingsData();
        settingsData.setOptedIn(false);
        AnalyticsSettings.setInstanceForTest(settingsData);

        ProjectOptions projectOptions = new ProjectOptions(
                new FakeProviderFactory(
                        FakeProviderFactory.getFactory(), ImmutableMap.of()));
        assertThat(projectOptions.isAnalyticsEnabled()).isFalse();

        projectOptions =
                new ProjectOptions(
                        new FakeProviderFactory(
                                FakeProviderFactory.getFactory(),
                                ImmutableMap.of("android.enableProfileJson", "true")));
        assertThat(projectOptions.isAnalyticsEnabled()).isTrue();

        projectOptions = new ProjectOptions(
                new FakeProviderFactory(
                        FakeProviderFactory.getFactory(),
                        ImmutableMap.of("android.advanced.profileOutputDir", "path")));
        assertThat(projectOptions.isAnalyticsEnabled()).isTrue();

        settingsData.setOptedIn(true);
        projectOptions =
                new ProjectOptions(
                        new FakeProviderFactory(
                                FakeProviderFactory.getFactory(), ImmutableMap.of()));
        assertThat(projectOptions.isAnalyticsEnabled()).isTrue();
    }

    @Test
    public void checkSimulatedAgpVersionTest() {
        ProjectOptions projectOptions =
                new ProjectOptions(
                        new FakeProviderFactory(
                                FakeProviderFactory.getFactory(),
                                ImmutableMap.of("android.simulateAgpVersionBehavior", "9.0.0")));
        assertThat(projectOptions.get(BooleanOption.TEST_SIMULATE_AGP_VERSION_BEHAVIOR)).isFalse();

        projectOptions =
                new ProjectOptions(
                        new FakeProviderFactory(
                                FakeProviderFactory.getFactory(),
                                ImmutableMap.of("android.simulateAgpVersionBehavior", "10.0.0")));
        assertThat(projectOptions.get(BooleanOption.TEST_SIMULATE_AGP_VERSION_BEHAVIOR)).isTrue();

        projectOptions =
                new ProjectOptions(
                        new FakeProviderFactory(
                                FakeProviderFactory.getFactory(),
                                ImmutableMap.of("android.simulateAgpVersionBehavior", "11.0.0")));
        assertThat(projectOptions.get(BooleanOption.TEST_SIMULATE_AGP_VERSION_BEHAVIOR)).isTrue();
    }

    @Test
    public void checkTestRunnerArgs() {
        ProjectOptions projectOptions =
                new ProjectOptions(
                        new FakeProviderFactory(
                                FakeProviderFactory.getFactory(),
                                ImmutableMap.of(
                                        "android.testInstrumentationRunnerArguments.size", "large",
                                        "android.testInstrumentationRunnerArguments.custom",
                                                "customValue")));
        assertThat(projectOptions.getExtraInstrumentationTestRunnerArgs().get())
                .containsExactly(
                        "size", "large",
                        "android.testInstrumentationRunnerArguments.custom", "customValue");
    }
}
