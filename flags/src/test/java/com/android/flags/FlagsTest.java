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

import static com.google.common.truth.Truth.assertThat;

import com.android.flags.overrides.InMemoryFlagValueContainer;
import com.android.flags.overrides.PropertyOverrides;

import com.google.common.collect.ImmutableList;

import org.jetbrains.annotations.NotNull;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;
import java.util.Properties;

public class FlagsTest {

    /** Enum for testing enum flags below. */
    private enum TestingEnum {
        FOO,
        BAR,
        BAZ,
        @SuppressWarnings({"NonAsciiCharacters", "UnicodeInCode"})
        DOTLESS_ı
    }

    private record CustomValue(String content) {}

    private static CustomValue customValue(String content) {
        return new CustomValue(content);
    }

    private static class CustomValueFlag extends CustomTypeFlag<CustomValue> {
        CustomValueFlag(
                FlagGroup group,
                String name,
                String displayName,
                String description,
                CustomValue defaultValue,
                List<CustomValue> examples) {
            super(
                    CustomValue.class,
                    group,
                    name,
                    displayName,
                    description,
                    defaultValue,
                    new Flag.ValueConverter<>() {
                        @Override
                        public @NotNull String serialize(CustomValue value) {
                            return value.content;
                        }

                        @Override
                        public CustomValue deserialize(@NotNull String strValue) {
                            return new CustomValue(strValue);
                        }
                    },
                    examples);
        }
    }

    @Test
    public void propertiesCanOverrideFlagValues() throws Exception {
        Properties properties = new Properties();
        properties.setProperty("test.int", "123");
        properties.setProperty("test.bool", "true");
        properties.setProperty("test.str", "Property override");
        properties.setProperty("test.enum", "bar");
        properties.setProperty("test.custom", "baz");

        PropertyOverrides propertyOverrides = new PropertyOverrides(properties);
        Flags flags = new Flags(propertyOverrides);
        FlagGroup group = new FlagGroup(flags, "test", "Test Group");

        Flag<Integer> flagInt = new IntFlag(group, "int", "Unused", "Unused", 10);
        Flag<Boolean> flagBool = new BooleanFlag(group, "bool", "Unused", "Unused");
        Flag<String> flagStr = new StringFlag(group, "str", "Unused", "Unused", "Default value");
        Flag<TestingEnum> flagEnum =
                new EnumFlag(group, "enum", "Unused", "Unused", TestingEnum.FOO);
        Flag<CustomValue> flagCustom =
                new CustomValueFlag(
                        group,
                        "custom",
                        "Unused",
                        "Unused",
                        customValue("foo"),
                        ImmutableList.of());

        assertThat(flagInt.get()).isEqualTo(123);
        assertThat(flagBool.get()).isEqualTo(true);
        assertThat(flagStr.get()).isEqualTo("Property override");
        assertThat(flagEnum.get()).isEqualTo(TestingEnum.BAR);
        assertThat(flagCustom.get()).isEqualTo(customValue("baz"));
    }

    @Test
    public void overrideFlagAndClearOverrideWorks() throws Exception {
        Flags flags = new Flags();

        FlagGroup group = new FlagGroup(flags, "test", "Test Group");

        Flag<Integer> flagInt = new IntFlag(group, "int", "Unused", "Unused", 10);
        Flag<Boolean> flagBool = new BooleanFlag(group, "bool", "Unused", "Unused");
        Flag<String> flagStr = new StringFlag(group, "str", "Unused", "Unused", "Default value");
        Flag<TestingEnum> flagEnum =
                new EnumFlag(group, "enum", "Unused", "Unused", TestingEnum.FOO);
        Flag<CustomValue> flagCustom =
                new CustomValueFlag(
                        group,
                        "custom",
                        "Unused",
                        "Unused",
                        customValue("foo"),
                        ImmutableList.of());

        flags.getUserOverrides().put(flagInt, "456");
        flags.getUserOverrides().put(flagBool, "true");
        flags.getUserOverrides().put(flagStr, "Manual override");
        flags.getUserOverrides().put(flagEnum, "bar");
        flags.getUserOverrides().put(flagCustom, "baz");

        assertThat(flagInt.get()).isEqualTo(456);
        assertThat(flagBool.get()).isEqualTo(true);
        assertThat(flagStr.get()).isEqualTo("Manual override");
        assertThat(flagEnum.get()).isEqualTo(TestingEnum.BAR);
        assertThat(flagCustom.get()).isEqualTo(customValue("baz"));

        flags.getUserOverrides().remove(flagInt);
        flags.getUserOverrides().remove(flagBool);
        flags.getUserOverrides().remove(flagStr);
        flags.getUserOverrides().remove(flagEnum);
        flags.getUserOverrides().remove(flagCustom);

        assertThat(flagInt.get()).isEqualTo(10);
        assertThat(flagBool.get()).isEqualTo(false);
        assertThat(flagStr.get()).isEqualTo("Default value");
        assertThat(flagEnum.get()).isEqualTo(TestingEnum.FOO);
        assertThat(flagCustom.get()).isEqualTo(customValue("foo"));
    }

    @Test
    public void mutableOverridesTakePrecedenceOverImmutableOverrides() throws Exception {
        Properties properties = new Properties();
        properties.put("test.str", "Property override");

        PropertyOverrides propertyOverrides = new PropertyOverrides(properties);
        Flags flags = new Flags(propertyOverrides);

        FlagGroup group = new FlagGroup(flags, "test", "Test Group");
        Flag<String> flagStr = new StringFlag(group, "str", "Unused", "Unused", "Default value");

        flags.getUserOverrides().put(flagStr, "Manual override");
        assertThat(flagStr.get()).isEqualTo("Manual override");

        flags.getUserOverrides().remove(flagStr);
        assertThat(flagStr.get()).isEqualTo("Property override");
    }

    @Test
    public void canSpecifyCustomUserOveriddes() throws Exception {
        InMemoryFlagValueContainer customMutableOverrides = new InMemoryFlagValueContainer();
        Flags flags = new Flags(customMutableOverrides);
        FlagGroup group = new FlagGroup(flags, "test", "Test Group");
        Flag<String> flagStr = new StringFlag(group, "str", "Unused", "Unused", "Default value");

        customMutableOverrides.put(flagStr, "Overridden value");

        assertThat(flagStr.get()).isEqualTo("Overridden value");

        customMutableOverrides.clear();

        assertThat(flagStr.get()).isEqualTo("Default value");
    }

    @Test
    public void flagsThrowsExceptionIfFlagsWithDuplicateIdsAreRegistered() throws Exception {
        Flags flags = new Flags();
        FlagGroup group = new FlagGroup(flags, "test", "Test Group");
        Flag<String> flag1 = new StringFlag(group, "str1", "Unused", "Unused", "Str 1");
        Flag<String> flag2 = new StringFlag(group, "str2", "Unused", "Unused", "Str 2");

        try {
            // Oops. Copy/paste error...
            Flag<String> flag3 = new StringFlag(group, "str2", "Unused", "Unused", "Str 3");
            Assert.fail();
        } catch (IllegalArgumentException ignored) {
        }
    }

    @Test
    public void flagsCanBeRetrievedById() {
        Flags flags = new Flags();
        FlagGroup group = new FlagGroup(flags, "test", "Test Group");
        Flag<String> flag1 = new StringFlag(group, "str1", "Unused", "Unused", "Str 1");
        Flag<String> flag2 = new StringFlag(group, "str2", "Unused", "Unused", "Str 2");

        Flag<?> got1 = flags.getFlag("test.str1");
        assertThat(got1).isEqualTo(flag1);

        Flag<?> got3 = flags.getFlag("test.not.present");
        assertThat(got3).isNull();
    }

    @Test
    public void invalidOverrideValue() {
        Properties properties = new Properties();
        properties.setProperty("test.int", "madeup");
        properties.setProperty("test.enum", "madeup");

        PropertyOverrides propertyOverrides = new PropertyOverrides(properties);
        Flags flags = new Flags(propertyOverrides);
        FlagGroup group = new FlagGroup(flags, "test", "Test Group");

        Flag<Integer> flagInt = new IntFlag(group, "int", "Unused", "Unused", 10);
        Flag<TestingEnum> flagEnum =
                new EnumFlag(group, "enum", "Unused", "Unused", TestingEnum.FOO);

        assertThat(flagInt.get()).isEqualTo(10);
        assertThat(flagEnum.get()).isEqualTo(TestingEnum.FOO);
    }

    @Test
    public void validation() {
        Properties properties = new Properties();
        PropertyOverrides propertyOverrides = new PropertyOverrides(properties);
        Flags flags = new Flags(propertyOverrides);
        FlagGroup group = new FlagGroup(flags, "test", "Test Group");

        try {
            Flag<Integer> flag = new IntFlag(group, "Invalid Id", "", "", 10);
            flag.validate();
            Assert.fail("Expected validation Assert.failure");
        } catch (IllegalArgumentException error) {
            assertThat(error.getLocalizedMessage()).isEqualTo("Invalid id: test.Invalid Id");
        }

        try {
            Flag<Integer> flag = new IntFlag(group, "id", " wrong", "", 10);
            flag.validate();
            Assert.fail("Expected validation Assert.failure");
        } catch (IllegalArgumentException error) {
            assertThat(error.getLocalizedMessage()).isEqualTo("Invalid name:  wrong");
        }

        try {
            // Fail serialization: serialization is pretty locked down by typed serializers,
            // but for enums it's using capitalization which we can break
            @SuppressWarnings({"NonAsciiCharacters", "UnicodeInCode"})
            Flag<TestingEnum> flag = new EnumFlag(group, "id2", "", "", TestingEnum.DOTLESS_ı);
            flag.validate();
            Assert.fail("Expected validation Assert.failure");
        } catch (IllegalArgumentException error) {
            assertThat(error.getLocalizedMessage())
                    .isEqualTo("Default value 'DOTLESS_ı' cannot be deserialized.");
        }

        try {
            // We've already registered id above
            Flag<Integer> flag = new IntFlag(group, "id", "Id 2", "", 10);
            flag.validate();
            Assert.fail("Expected validation Assert.failure");
        } catch (IllegalArgumentException error) {
            assertThat(error.getLocalizedMessage())
                    .isEqualTo(
                            "Flag \"Id 2\" shares duplicate ID \"test.id\" with flag \" wrong\"");
        }
    }
}
