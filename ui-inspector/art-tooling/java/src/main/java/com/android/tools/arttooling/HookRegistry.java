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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Holds the registered entry/exit hooks and runs them when an instrumented method fires. Each hook
 * is filed under its method's signature. An exception from a hook is swallowed, so it does not
 * reach the instrumented method.
 *
 * <p>Registering the first hook for a method triggers native instrumentation (through the installer
 * given at construction). The registry never uninstalls that instrumentation. Undoing it would cost
 * another retransform before the next tool hooks the same method, while an idle trampoline costs
 * one map lookup per call. The registry therefore keeps a method's entry after all its hooks are
 * removed, so a later registration does not add a duplicate transform.
 */
final class HookRegistry {

    /** Installs native instrumentation for a method the first time it gains a callback. */
    interface NativeInstaller {
        void install(Class<?> origin, String methodSignature);
    }

    private static final class EntryRegistration {
        final String ownerId;
        final ArtTooling.EntryHook hook;

        EntryRegistration(String ownerId, ArtTooling.EntryHook hook) {
            this.ownerId = ownerId;
            this.hook = hook;
        }
    }

    private static final class ExitRegistration {
        final String ownerId;
        final ArtTooling.ExitHook<?> hook;

        ExitRegistration(String ownerId, ArtTooling.ExitHook<?> hook) {
            this.ownerId = ownerId;
            this.hook = hook;
        }
    }

    private final NativeInstaller entryInstaller;
    private final NativeInstaller exitInstaller;

    private final Map<String, List<EntryRegistration>> entryHooks = new ConcurrentHashMap<>();
    private final Map<String, List<ExitRegistration>> exitHooks = new ConcurrentHashMap<>();

    HookRegistry(NativeInstaller entryInstaller, NativeInstaller exitInstaller) {
        this.entryInstaller = entryInstaller;
        this.exitInstaller = exitInstaller;
    }

    synchronized void registerEntryHook(
            Class<?> origin, String methodSignature, String ownerId, ArtTooling.EntryHook hook) {
        String label = createLabel(origin, methodSignature);
        List<EntryRegistration> hooks = entryHooks.get(label);
        if (hooks == null) {
            entryInstaller.install(origin, methodSignature);
            hooks = new CopyOnWriteArrayList<>();
            entryHooks.put(label, hooks);
        }
        hooks.add(new EntryRegistration(ownerId, hook));
    }

    synchronized void registerExitHook(
            Class<?> origin, String methodSignature, String ownerId, ArtTooling.ExitHook<?> hook) {
        String label = createLabel(origin, methodSignature);
        List<ExitRegistration> hooks = exitHooks.get(label);
        if (hooks == null) {
            exitInstaller.install(origin, methodSignature);
            hooks = new CopyOnWriteArrayList<>();
            exitHooks.put(label, hooks);
        }
        hooks.add(new ExitRegistration(ownerId, hook));
    }

    /** Removes every entry and exit callback owned by {@code ownerId}, keeping empty signatures. */
    void clear(String ownerId) {
        for (List<EntryRegistration> list : entryHooks.values()) {
            list.removeIf(registration -> ownerId.equals(registration.ownerId));
        }
        for (List<ExitRegistration> list : exitHooks.values()) {
            list.removeIf(registration -> ownerId.equals(registration.ownerId));
        }
    }

    /**
     * Dispatches a method entry event. The instrumented bytecode packs the call as {@code
     * [methodSignature, thisObject, arg0, arg1, ...]}.
     */
    void dispatchEntry(Object[] signatureThisParams) {
        if (signatureThisParams == null || signatureThisParams.length < 2) {
            return;
        }
        String label = normalizeSignature((String) signatureThisParams[0]);
        List<EntryRegistration> hooks = entryHooks.get(label);
        if (hooks == null) {
            return;
        }
        Object thisObject = signatureThisParams[1];
        List<Object> params =
                signatureThisParams.length > 2
                        ? Arrays.asList(
                                Arrays.copyOfRange(
                                        signatureThisParams, 2, signatureThisParams.length))
                        : Collections.emptyList();
        for (EntryRegistration registration : hooks) {
            try {
                registration.hook.onEntry(thisObject, params);
            } catch (Throwable ignored) {
                // An exception from a hook must not reach the instrumented method.
            }
        }
    }

    /**
     * Dispatches a method exit event, threading the return value through every exit callback so
     * each one can observe or replace it.
     */
    <T> T dispatchExit(String methodSignature, T returnValue) {
        return runExitHooks(methodSignature, returnValue, result -> true);
    }

    /**
     * Dispatches a method exit event for a method that returns a primitive. The instrumented
     * bytecode unboxes the value it gets back, so a null or a wrong box type would crash the
     * method. The registry discards such a value and keeps the value the previous hook produced.
     */
    <T> T dispatchPrimitiveExit(String methodSignature, T returnValue, Class<T> box) {
        return runExitHooks(methodSignature, returnValue, box::isInstance);
    }

    @SuppressWarnings("unchecked")
    private <T> T runExitHooks(String methodSignature, T returnValue, Predicate<Object> accepts) {
        String label = normalizeSignature(methodSignature);
        List<ExitRegistration> hooks = exitHooks.get(label);
        if (hooks == null) {
            return returnValue;
        }
        for (ExitRegistration registration : hooks) {
            try {
                T result = ((ArtTooling.ExitHook<T>) registration.hook).onExit(returnValue);
                if (accepts.test(result)) {
                    returnValue = result;
                }
            } catch (Throwable ignored) {
                // An exception from a hook must not reach the instrumented method.
            }
        }
        return returnValue;
    }

    private static String createLabel(Class<?> origin, String methodSignature) {
        return normalizeSignature(origin.getName() + "->" + methodSignature);
    }

    /**
     * Normalizes a method descriptor to JVM form. A dotted class name (e.g. {@code
     * com.example.Foo->bar()V}) becomes slash-notation prefixed with {@code L} and suffixed with
     * {@code ;} (e.g. {@code Lcom/example/Foo;->bar()V}). A descriptor already in JVM form is
     * returned unchanged.
     */
    private static String normalizeSignature(String signature) {
        if (signature.startsWith("L") && signature.contains(";->")) {
            return signature;
        }
        int index = signature.indexOf("->");
        if (index != -1) {
            String className = signature.substring(0, index);
            String methodAndSig = signature.substring(index);
            String jvmClassName = "L" + className.replace('.', '/') + ";";
            return jvmClassName + methodAndSig;
        }
        return signature;
    }
}
