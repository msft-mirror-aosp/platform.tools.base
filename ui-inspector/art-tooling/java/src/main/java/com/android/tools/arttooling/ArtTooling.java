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
import java.util.Objects;

/**
 * The API a tool uses to inspect the process it is running in: find live instances of a type
 * ({@link #findInstances}) and hook method entry and exit ({@link #registerEntryHook} / {@link
 * #registerExitHook}). It is a thin Java wrapper over the native JVMTI engine.
 *
 * <p>Must be loaded in the bootstrap class loader (the agent does this via {@code
 * AddToBootstrapClassLoaderSearch}) so that its {@code onEntry} and {@code onExit} dispatch methods
 * are reachable from arbitrary class loaders.
 *
 * <p>This class exists once per process, so one hook registry serves every tool in the process. The
 * native engine belongs to a loaded copy of the agent library, and a process can hold several
 * copies. This class uses the engine of the copy that performed the latest attach. Until {@link
 * #initialize} runs, {@link #findInstances} and hook registration throw {@link
 * IllegalStateException}.
 */
public final class ArtTooling {

    /** A callback invoked when a hooked method is entered. */
    public interface EntryHook {
        /**
         * @param thisObject the receiver, or {@code null} for a static method
         * @param arguments the method arguments, primitives boxed
         */
        void onEntry(Object thisObject, List<Object> arguments);
    }

    /** A callback invoked when a hooked method returns. */
    public interface ExitHook<T> {
        /**
         * @param returnValue the value the method is about to return
         * @return the value that actually gets returned; hooks are chained in registration order.
         */
        T onExit(T returnValue);
    }

    /** The native engine pointer, or 0 until the engine attaches. */
    private static volatile long nativeHandle;

    private static final HookRegistry REGISTRY =
            new HookRegistry(
                    (origin, signature) -> nativeRegisterEntryHook(nativeHandle, origin, signature),
                    (origin, signature) -> nativeRegisterExitHook(nativeHandle, origin, signature));

    private ArtTooling() {}

    /**
     * Finds every live instance of {@code type} on the heap. Passing {@code Class.class} returns
     * all loaded classes. For every other type, the search walks the whole heap.
     *
     * @throws IllegalArgumentException if {@code type} is a primitive or {@code Object}
     * @throws IllegalStateException if the native engine is not attached
     */
    public static <T> List<T> findInstances(Class<T> type) {
        Objects.requireNonNull(type, "type");
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("type must not be a primitive: " + type);
        }
        if (type == Object.class) {
            // Every object on the heap is an instance of Object, which would make this call very
            // expensive.
            throw new IllegalArgumentException("type must not be Object");
        }
        checkAttached();
        T[] instances = nativeFindInstances(nativeHandle, type);
        return instances != null ? Arrays.asList(instances) : Collections.emptyList();
    }

    /**
     * Registers a callback invoked when {@code methodSignature} on {@code origin} is entered.
     *
     * @param methodSignature the method in {@code name(paramsDescriptor)returnDescriptor} form
     * @param ownerId identifies the caller, so {@link #clear} can remove its callbacks
     * @throws IllegalStateException if the native engine is not attached
     */
    public static void registerEntryHook(
            Class<?> origin, String methodSignature, String ownerId, EntryHook hook) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(methodSignature, "methodSignature");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(hook, "hook");
        checkAttached();
        REGISTRY.registerEntryHook(origin, methodSignature, ownerId, hook);
    }

    /**
     * Registers a callback invoked when {@code methodSignature} on {@code origin} returns.
     *
     * @param methodSignature the method in {@code name(paramsDescriptor)returnDescriptor} form
     * @param ownerId identifies the caller, so {@link #clear} can remove its callbacks
     * @throws IllegalStateException if the native engine is not attached
     */
    public static <T> void registerExitHook(
            Class<?> origin, String methodSignature, String ownerId, ExitHook<T> hook) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(methodSignature, "methodSignature");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(hook, "hook");
        checkAttached();
        REGISTRY.registerExitHook(origin, methodSignature, ownerId, hook);
    }

    /** Removes every entry and exit callback registered under {@code ownerId}. */
    public static void clear(String ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        REGISTRY.clear(ownerId);
    }

    /**
     * Gives this class the pointer to the attached native engine, after which its methods work.
     * {@link AgentLoader} calls this on every attach. The pointer changes when a newly loaded copy
     * of the agent library performs the attach.
     */
    static void initialize(long toolingPtr) {
        nativeHandle = toolingPtr;
    }

    // The dispatch entry points below are invoked by instrumented bytecode from arbitrary class
    // loaders, so they must be public. They are not part of the tool-facing API.

    /** Entry dispatch target. {@code signatureThisParams} is {@code [signature, this, args...]}. */
    public static void onEntry(Object[] signatureThisParams) {
        REGISTRY.dispatchEntry(signatureThisParams);
    }

    public static Object onExit(String methodSignature, Object returnValue) {
        return REGISTRY.dispatchExit(methodSignature, returnValue);
    }

    public static void onExit(String methodSignature) {
        REGISTRY.dispatchExit(methodSignature, null);
    }

    public static boolean onExit(String methodSignature, boolean returnValue) {
        return REGISTRY.dispatchPrimitiveExit(methodSignature, returnValue, Boolean.class);
    }

    public static byte onExit(String methodSignature, byte returnValue) {
        return REGISTRY.dispatchPrimitiveExit(methodSignature, returnValue, Byte.class);
    }

    public static char onExit(String methodSignature, char returnValue) {
        return REGISTRY.dispatchPrimitiveExit(methodSignature, returnValue, Character.class);
    }

    public static short onExit(String methodSignature, short returnValue) {
        return REGISTRY.dispatchPrimitiveExit(methodSignature, returnValue, Short.class);
    }

    public static int onExit(String methodSignature, int returnValue) {
        return REGISTRY.dispatchPrimitiveExit(methodSignature, returnValue, Integer.class);
    }

    public static float onExit(String methodSignature, float returnValue) {
        return REGISTRY.dispatchPrimitiveExit(methodSignature, returnValue, Float.class);
    }

    public static long onExit(String methodSignature, long returnValue) {
        return REGISTRY.dispatchPrimitiveExit(methodSignature, returnValue, Long.class);
    }

    public static double onExit(String methodSignature, double returnValue) {
        return REGISTRY.dispatchPrimitiveExit(methodSignature, returnValue, Double.class);
    }

    private static void checkAttached() {
        if (nativeHandle == 0) {
            throw new IllegalStateException("ART Tooling is not attached");
        }
    }

    private static native <T> T[] nativeFindInstances(long toolingPtr, Class<T> type);

    private static native void nativeRegisterEntryHook(
            long toolingPtr, Class<?> origin, String methodSignature);

    private static native void nativeRegisterExitHook(
            long toolingPtr, Class<?> origin, String methodSignature);
}
