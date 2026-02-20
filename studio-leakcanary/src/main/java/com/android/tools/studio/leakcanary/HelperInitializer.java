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

import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.IntentFilter;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Initializes the helper library by running its code on app startup. This ContentProvider is
 * automatically created by the OS before Application.onCreate().
 *
 * <p>Its primary purpose is to: 1. Disable LeakCanary's default heap dumping behavior (since Studio
 * handles that). 2. Swap LeakCanary's default internal listener with [StudioLeakCanaryListener]. 3.
 * Register broadcast receivers to listen for commands from Android Studio.
 */
@SuppressWarnings("unused")
public class HelperInitializer extends ContentProvider {

    @Override
    public boolean onCreate() {
        Log.d(HelperConfig.LOG_TAG, "Initializing Studio LeakCanary Bridge...");

        disableLeakCanaryHeapDump();
        replaceLeakCanaryListener();
        registerInternalReceivers();

        // Return true to indicate the provider loaded successfully (even if some setup failed).
        return true;
    }

    private void disableLeakCanaryHeapDump() {
        // 1. Disable LeakCanary's internal heap dumper.
        try {
            // Use reflection to modify the 'dumpHeap' field of the current Config object in-place.
            // This avoids the complexity of copying the immutable Kotlin data class via reflection.
            Class<?> leakCanaryClass = Class.forName(HelperConfig.LEAK_CANARY_CLASS);

            // LeakCanary is a Kotlin object, so we need its INSTANCE to access properties
            // unless they are @JvmStatic. To be safe, we get the instance.
            Field leakCanaryInstanceField =
                    leakCanaryClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
            Object leakCanaryInstance = leakCanaryInstanceField.get(null);

            // LeakCanary.config is a property backed by a static method 'getConfig' (due to
            // @JvmStatic)
            Method getConfigMethod =
                    leakCanaryClass.getDeclaredMethod(HelperConfig.GET_CONFIG_METHOD);
            Object currentConfig = getConfigMethod.invoke(leakCanaryInstance);

            if (currentConfig != null) {
                Class<?> configClass = currentConfig.getClass();
                Field dumpHeapField = configClass.getDeclaredField(HelperConfig.DUMP_HEAP_FIELD);
                dumpHeapField.setAccessible(true);
                dumpHeapField.setBoolean(currentConfig, false);
                Log.d(HelperConfig.LOG_TAG, "Successfully disabled heap dumping via reflection.");
            } else {
                // This should practically never happen as LeakCanary initializes with a default
                // config.
                Log.e(
                        HelperConfig.LOG_TAG,
                        "LeakCanary.getConfig() returned null. Cannot disable heap dumping.");
            }
        } catch (ClassNotFoundException e) {
            Log.w(HelperConfig.LOG_TAG, "LeakCanary class not found. Skipping heap dump disable.");
        } catch (Throwable t) {
            Log.e(
                    HelperConfig.LOG_TAG,
                    "Failed to disable LeakCanary heap dumping via reflection. "
                            + "LeakCanary version might be incompatible.",
                    t);
        }
    }

    private void replaceLeakCanaryListener() {
        // 2. Swap LeakCanary's default internal listener with StudioLeakCanaryListener.
        try {
            // Use reflection to get the singleton instance of LeakCanary's
            // internal listener.
            Class<?> internalLcClass = Class.forName(HelperConfig.LEAK_CANARY_INTERNAL_CLASS);
            Field instanceField = internalLcClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
            instanceField.setAccessible(true);
            Object internalLcInstance = instanceField.get(null);

            Context context =
                    getContext(); // getContext() is a method from the ContentProvider superclass.
            Application app = null;
            if (context != null && context.getApplicationContext() instanceof Application) {
                app = (Application) context.getApplicationContext();
            }

            Object myListener = null;
            if (app != null) {
                // Create the proxy listener using our new factory method
                myListener = StudioLeakCanaryListener.createInstance(app);
            }

            if (app == null) {
                Log.e(
                        HelperConfig.LOG_TAG,
                        "Could not get Application instance. Aborting listener swap.");
            } else if (myListener == null) {
                Log.e(
                        HelperConfig.LOG_TAG,
                        "Failed to create StudioLeakCanaryListener proxy. Aborting listener swap.");
            } else {
                Class<?> listenerInterface =
                        Class.forName(HelperConfig.ON_OBJECT_RETAINED_LISTENER_CLASS);

                if (listenerInterface.isInstance(internalLcInstance)) {
                    // We found it. Now, perform the swap.

                    // Get AppWatcher.objectWatcher
                    Class<?> appWatcherClass = Class.forName(HelperConfig.APP_WATCHER_CLASS);
                    Field appWatcherInstanceField =
                            appWatcherClass.getDeclaredField(HelperConfig.INSTANCE_FIELD);
                    Object appWatcherInstance = appWatcherInstanceField.get(null);

                    Method getObjectWatcherMethod =
                            appWatcherClass.getMethod(HelperConfig.GET_OBJECT_WATCHER_METHOD);
                    Object objectWatcher = getObjectWatcherMethod.invoke(appWatcherInstance);

                    // Get methods for add/remove listener
                    Class<?> objectWatcherClass = objectWatcher.getClass();
                    Method removeListenerMethod =
                            objectWatcherClass.getMethod(
                                    HelperConfig.REMOVE_ON_OBJECT_RETAINED_LISTENER_METHOD,
                                    listenerInterface);
                    Method addListenerMethod =
                            objectWatcherClass.getMethod(
                                    HelperConfig.ADD_ON_OBJECT_RETAINED_LISTENER_METHOD,
                                    listenerInterface);

                    // Swap
                    removeListenerMethod.invoke(objectWatcher, internalLcInstance);
                    addListenerMethod.invoke(objectWatcher, myListener);

                    Log.d(
                            HelperConfig.LOG_TAG,
                            "Successfully replaced LeakCanary default listener with Studio"
                                    + " listener.");
                } else {
                    // This should not happen if the class name is correct, but we
                    // log it just in case.
                    Log.e(
                            HelperConfig.LOG_TAG,
                            "InternalLeakCanary class found but does not implement"
                                    + " OnObjectRetainedListener.");
                }
            }
        } catch (ClassNotFoundException e) {
            Log.w(HelperConfig.LOG_TAG, "LeakCanary classes not found. Skipping listener swap.");
        } catch (Throwable t) {
            Log.e(
                    HelperConfig.LOG_TAG,
                    "Failed to replace LeakCanary default listener. "
                            + "LeakCanary version might be incompatible.",
                    t);
        }
    }

    private void registerInternalReceivers() {
        // 3. Register broadcast receivers.
        try {
            IntentFilter intentFilter = new IntentFilter(HelperConfig.HEAP_DUMP_COMPLETE_INTENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getContext()
                        .registerReceiver(
                                new HeapDumpFinishedReceiver(),
                                intentFilter,
                                Context.RECEIVER_NOT_EXPORTED);
            } else {
                String permissionName =
                        getContext().getPackageName() + HelperConfig.INTERNAL_PERMISSION_SUFFIX;
                getContext()
                        .registerReceiver(
                                new HeapDumpFinishedReceiver(), intentFilter, permissionName, null);
            }

            IntentFilter startListeningFilter =
                    new IntentFilter(HelperConfig.START_LISTENING_INTENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getContext()
                        .registerReceiver(
                                new StartListeningReceiver(),
                                startListeningFilter,
                                Context.RECEIVER_NOT_EXPORTED);
            } else {
                String permissionName =
                        getContext().getPackageName() + HelperConfig.INTERNAL_PERMISSION_SUFFIX;
                getContext()
                        .registerReceiver(
                                new StartListeningReceiver(),
                                startListeningFilter,
                                permissionName,
                                null);
            }

            IntentFilter getThresholdFilter = new IntentFilter(HelperConfig.GET_THRESHOLD_INTENT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                getContext()
                        .registerReceiver(
                                new GetThresholdReceiver(),
                                getThresholdFilter,
                                Context.RECEIVER_NOT_EXPORTED);
            } else {
                String permissionName =
                        getContext().getPackageName() + HelperConfig.INTERNAL_PERMISSION_SUFFIX;
                getContext()
                        .registerReceiver(
                                new GetThresholdReceiver(),
                                getThresholdFilter,
                                permissionName,
                                null);
            }
        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to register broadcast receivers.", t);
        }
    }

    // --- Unused ContentProvider methods ---

    @Override
    public Cursor query(
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
