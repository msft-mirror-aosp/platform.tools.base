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

package com.android.tools.arttooling;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public final class HookRegistryTest {

    /** Records the (origin, signature) pairs that triggered native instrumentation. */
    private static final class RecordingInstaller implements HookRegistry.NativeInstaller {
        final List<String> installs = new ArrayList<>();

        @Override
        public void install(Class<?> origin, String methodSignature) {
            installs.add(origin.getName() + "->" + methodSignature);
        }
    }

    private final RecordingInstaller entryInstaller = new RecordingInstaller();
    private final RecordingInstaller exitInstaller = new RecordingInstaller();
    private final HookRegistry registry = new HookRegistry(entryInstaller, exitInstaller);

    @Test
    public void entryDispatchReachesHookByNormalizedSignature() {
        List<Object> received = new ArrayList<>();
        Object receiver = new Object();
        registry.registerEntryHook(
                String.class, "length()I", "owner", (thisObject, args) -> received.add(thisObject));

        registry.dispatchEntry(new Object[] {"Ljava/lang/String;->length()I", receiver});

        assertThat(received).containsExactly(receiver);
    }

    @Test
    public void firstRegistrationInstallsNativeHookOnlyOnce() {
        registry.registerEntryHook(String.class, "length()I", "a", (t, args) -> {});
        registry.registerEntryHook(String.class, "length()I", "b", (t, args) -> {});

        assertThat(entryInstaller.installs).containsExactly("java.lang.String->length()I");
    }

    @Test
    public void entryDispatchReachesEveryOwnersHook() {
        List<String> received = new ArrayList<>();
        registry.registerEntryHook(String.class, "length()I", "a", (t, args) -> received.add("a"));
        registry.registerEntryHook(String.class, "length()I", "b", (t, args) -> received.add("b"));

        registry.dispatchEntry(new Object[] {"Ljava/lang/String;->length()I", new Object()});

        assertThat(received).containsExactly("a", "b");
    }

    @Test
    public void exitHooksChainReturnValueInOrder() {
        registry.registerExitHook(
                String.class, "trim()Ljava/lang/String;", "a", (String v) -> v + "-a");
        registry.registerExitHook(
                String.class, "trim()Ljava/lang/String;", "b", (String v) -> v + "-b");

        String result = registry.dispatchExit("Ljava/lang/String;->trim()Ljava/lang/String;", "x");

        assertThat(result).isEqualTo("x-a-b");
    }

    @Test
    public void throwingHookDoesNotStopLaterHooks() {
        List<String> received = new ArrayList<>();
        registry.registerEntryHook(
                String.class,
                "length()I",
                "a",
                (t, args) -> {
                    throw new RuntimeException("boom");
                });
        registry.registerEntryHook(String.class, "length()I", "b", (t, args) -> received.add("b"));

        registry.dispatchEntry(new Object[] {"Ljava/lang/String;->length()I", new Object()});

        assertThat(received).containsExactly("b");
    }

    @Test
    public void clearRemovesOwnerHooksButKeepsMethodInstrumented() {
        List<String> received = new ArrayList<>();
        registry.registerEntryHook(String.class, "length()I", "a", (t, args) -> received.add("a"));

        registry.clear("a");
        registry.dispatchEntry(new Object[] {"Ljava/lang/String;->length()I", new Object()});
        assertThat(received).isEmpty();

        // Re-registering the same signature must not install a second native transform.
        registry.registerEntryHook(String.class, "length()I", "a", (t, args) -> received.add("a2"));
        assertThat(entryInstaller.installs).containsExactly("java.lang.String->length()I");

        registry.dispatchEntry(new Object[] {"Ljava/lang/String;->length()I", new Object()});
        assertThat(received).containsExactly("a2");
    }

    @Test
    public void exitDispatchReturnsValueUnchangedWhenNoHooks() {
        String result = registry.dispatchExit("Ljava/lang/String;->trim()Ljava/lang/String;", "x");

        assertThat(result).isEqualTo("x");
        assertThat(exitInstaller.installs).isEmpty();
    }

    @Test
    public void throwingExitHookIsIsolatedAndChainContinues() {
        registry.registerExitHook(
                String.class,
                "trim()Ljava/lang/String;",
                "a",
                (String v) -> {
                    throw new RuntimeException("boom");
                });
        registry.registerExitHook(
                String.class, "trim()Ljava/lang/String;", "b", (String v) -> v + "-b");

        String result = registry.dispatchExit("Ljava/lang/String;->trim()Ljava/lang/String;", "x");

        // The throwing hook leaves the value untouched; the next hook still runs.
        assertThat(result).isEqualTo("x-b");
    }

    @Test
    public void entryDispatchExtractsBoxedArguments() {
        List<Object> received = new ArrayList<>();
        registry.registerEntryHook(
                String.class, "indexOf(II)I", "owner", (thisObject, args) -> received.addAll(args));

        registry.dispatchEntry(new Object[] {"Ljava/lang/String;->indexOf(II)I", "recv", 7, 9});

        assertThat(received).containsExactly(7, 9).inOrder();
    }

    @Test
    public void entryDispatchPassesNullReceiverForStaticMethod() {
        List<Object> receivers = new ArrayList<>();
        registry.registerEntryHook(
                String.class,
                "valueOf(I)Ljava/lang/String;",
                "owner",
                (thisObject, args) -> receivers.add(thisObject));

        registry.dispatchEntry(
                new Object[] {"Ljava/lang/String;->valueOf(I)Ljava/lang/String;", null});

        assertThat(receivers).containsExactly((Object) null);
    }

    @Test
    public void clearRemovesOnlyTheNamedOwner() {
        List<String> received = new ArrayList<>();
        registry.registerEntryHook(String.class, "length()I", "a", (t, args) -> received.add("a"));
        registry.registerEntryHook(String.class, "length()I", "b", (t, args) -> received.add("b"));

        registry.clear("a");
        registry.dispatchEntry(new Object[] {"Ljava/lang/String;->length()I", new Object()});

        assertThat(received).containsExactly("b");
    }

    @Test
    public void clearRemovesExitHooksForOwner() {
        registry.registerExitHook(
                String.class, "trim()Ljava/lang/String;", "a", (String v) -> v + "-a");

        registry.clear("a");
        String result = registry.dispatchExit("Ljava/lang/String;->trim()Ljava/lang/String;", "x");

        assertThat(result).isEqualTo("x");
    }

    @Test
    public void primitiveExitDispatchUsesValidHookResult() {
        registry.registerExitHook(String.class, "isEmpty()Z", "a", (Boolean v) -> !v);

        boolean result =
                registry.dispatchPrimitiveExit(
                        "Ljava/lang/String;->isEmpty()Z", true, Boolean.class);

        assertThat(result).isFalse();
    }

    @Test
    public void primitiveExitDispatchKeepsValueWhenHookReturnsNull() {
        registry.registerExitHook(String.class, "isEmpty()Z", "a", (Boolean v) -> null);

        boolean result =
                registry.dispatchPrimitiveExit(
                        "Ljava/lang/String;->isEmpty()Z", true, Boolean.class);

        assertThat(result).isTrue();
    }

    @Test
    public void primitiveExitDispatchKeepsValueWhenHookReturnsWrongBox() {
        registry.registerExitHook(String.class, "isEmpty()Z", "a", (Object v) -> 42);

        boolean result =
                registry.dispatchPrimitiveExit(
                        "Ljava/lang/String;->isEmpty()Z", true, Boolean.class);

        assertThat(result).isTrue();
    }

    @Test
    public void primitiveExitDispatchDiscardsInvalidValueBeforeNextHook() {
        List<Object> seenBySecondHook = new ArrayList<>();
        registry.registerExitHook(String.class, "isEmpty()Z", "a", (Boolean v) -> null);
        registry.registerExitHook(
                String.class,
                "isEmpty()Z",
                "b",
                (Boolean v) -> {
                    seenBySecondHook.add(v);
                    return !v;
                });

        boolean result =
                registry.dispatchPrimitiveExit(
                        "Ljava/lang/String;->isEmpty()Z", true, Boolean.class);

        assertThat(seenBySecondHook).containsExactly(true);
        assertThat(result).isFalse();
    }

    @Test
    public void throwingInstallerLeavesMethodUninstalledSoNextRegistrationRetries() {
        List<String> installs = new ArrayList<>();
        HookRegistry.NativeInstaller failingOnce =
                (origin, methodSignature) -> {
                    installs.add(methodSignature);
                    if (installs.size() == 1) {
                        throw new IllegalStateException("retransform failed");
                    }
                };
        HookRegistry retrying = new HookRegistry(failingOnce, exitInstaller);
        List<Object> received = new ArrayList<>();

        assertThrows(
                IllegalStateException.class,
                () -> retrying.registerEntryHook(String.class, "length()I", "a", (t, args) -> {}));
        retrying.registerEntryHook(String.class, "length()I", "a", (t, args) -> received.add(t));
        Object receiver = new Object();
        retrying.dispatchEntry(new Object[] {"Ljava/lang/String;->length()I", receiver});

        assertThat(installs).containsExactly("length()I", "length()I");
        assertThat(received).containsExactly(receiver);
    }
}
