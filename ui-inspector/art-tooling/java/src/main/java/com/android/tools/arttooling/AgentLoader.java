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

import android.app.Application;
import android.os.Looper;
import android.util.Log;

import dalvik.system.DexClassLoader;

import java.io.File;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Loads a tool's {@link Agent} from its dex and starts it inside the target process — the handoff
 * from ART Tooling's native attach to the tool's own Java code.
 */
final class AgentLoader {

    private static final String TAG = "studio.arttooling";

    private AgentLoader() {}

    /**
     * Runs the Java side of attachment: makes the {@link ArtTooling} API usable, loads the tool's
     * agent dex in a class loader that descends from the app's (so it can see the app's own
     * classes), creates the tool's {@link Agent}, and calls its {@code onAttach}.
     *
     * <p>Called from native code, which turns any exception thrown here into an attach failure.
     *
     * @param agentDex absolute path to the tool's agent dex/jar
     * @param agentClass binary name of the {@link Agent} implementation within {@code agentDex}
     * @param agentOptions opaque options passed through to {@link Agent#onAttach}
     * @param toolingPtr pointer to the native engine
     */
    static void attach(String agentDex, String agentClass, String agentOptions, long toolingPtr)
            throws ReflectiveOperationException {
        File agentDexFile = new File(agentDex);
        if (!agentDexFile.isFile() || !agentDexFile.canRead()) {
            throw new IllegalArgumentException("Agent dex is missing or not readable: " + agentDex);
        }

        ArtTooling.initialize(toolingPtr);

        ClassLoader appClassLoader = findAppClassLoader();
        if (appClassLoader == null) {
            throw new IllegalStateException("Could not find the application class loader");
        }

        DexClassLoader dexClassLoader = new DexClassLoader(agentDex, null, null, appClassLoader);
        Class<? extends Agent> loadedClass =
                Class.forName(agentClass, true, dexClassLoader).asSubclass(Agent.class);
        Agent agent = loadedClass.getDeclaredConstructor().newInstance();
        agent.onAttach(agentOptions);
    }

    /**
     * Resolves the target application's class loader, preferring the {@link Application} instance
     * found on the heap and falling back to the main looper thread's context class loader.
     */
    private static ClassLoader findAppClassLoader() {
        // The heap walk visits every live object, so its duration grows with the heap.
        long startNanos = System.nanoTime();
        List<Application> applications = ArtTooling.findInstances(Application.class);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        Log.i(
                TAG,
                "Found "
                        + applications.size()
                        + " Application instance(s) in "
                        + elapsedMillis
                        + " ms");
        for (Application application : applications) {
            if (application != null) {
                ClassLoader classLoader = application.getClassLoader();
                if (classLoader != null) {
                    return classLoader;
                }
            }
        }

        Looper looper = Looper.getMainLooper();
        if (looper != null && looper.getThread() != null) {
            return looper.getThread().getContextClassLoader();
        }
        return null;
    }
}
