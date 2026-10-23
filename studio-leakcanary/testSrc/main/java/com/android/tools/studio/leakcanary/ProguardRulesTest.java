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

package com.android.tools.studio.leakcanary;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.android.testutils.TestUtils;
import com.android.tools.profiler.proto.LeakCanary;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Checks that the consumer R8/ProGuard rules keep every LeakCanary class this library reaches
 * through reflection, so apps that minify their debug build keep working (b/512795977).
 *
 * <p>The class names are read from the {@code *_CLASS} constants in {@link HelperConfig}, so a new
 * constant without a matching rule fails this test. Each rule must keep all members ({@code { *;
 * }}), because the reflection needs the fields and methods, not only the class name.
 *
 * <p>It also checks the rule for the generated proto classes. These tests only read the rules file;
 * they don't run R8.
 */
@RunWith(JUnit4.class)
public class ProguardRulesTest {

    @Test
    public void rulesKeepEveryReflectedLeakCanaryClass() throws Exception {
        String rules = readRules();
        Set<String> reflectedClasses = reflectedClasses();
        assertThat(reflectedClasses).isNotEmpty();

        for (String className : reflectedClasses) {
            Pattern keepRule =
                    Pattern.compile(
                            "^-keep (class|interface) "
                                    + Pattern.quote(className)
                                    + " \\{\\s*\\*;\\s*\\}",
                            Pattern.MULTILINE);
            assertWithMessage(
                            "proguard-rules.pro must have a \"-keep ... { *; }\" rule for "
                                    + className
                                    + " (from HelperConfig)")
                    .that(keepRule.matcher(rules).find())
                    .isTrue();
        }
    }

    @Test
    public void rulesKeepFieldsOfGeneratedProtoMessages() throws Exception {
        // The rule names the generated proto class, so it must match that class's real name.
        Pattern keepRule =
                Pattern.compile(
                        "^-keepclassmembers\\s+class\\s+"
                                + Pattern.quote(LeakCanary.class.getName() + "$*")
                                + "\\s+extends\\s+"
                                + Pattern.quote("com.google.protobuf.GeneratedMessageLite")
                                + "\\s*\\{\\s*<fields>;\\s*\\}",
                        Pattern.MULTILINE);

        assertWithMessage("proguard-rules.pro must keep the fields of the generated proto messages")
                .that(keepRule.matcher(readRules()).find())
                .isTrue();
    }

    /** Every LeakCanary class this library reflects on, read from HelperConfig's *_CLASS fields. */
    private static Set<String> reflectedClasses() throws IllegalAccessException {
        Set<String> classes = new TreeSet<>();
        for (Field field : HelperConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class
                    && field.getName().endsWith("_CLASS")) {
                field.setAccessible(true);
                classes.add((String) field.get(null));
            }
        }
        return classes;
    }

    private static String readRules() throws Exception {
        return new String(
                Files.readAllBytes(
                        TestUtils.resolveWorkspacePath(
                                "tools/base/studio-leakcanary/proguard-rules.pro")),
                StandardCharsets.UTF_8);
    }
}
