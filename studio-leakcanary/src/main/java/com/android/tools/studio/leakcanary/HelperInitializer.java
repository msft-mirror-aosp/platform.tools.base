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

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

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

        StudioLeakCanaryManager.getInstance().initialize(getContext());
        registerInternalReceivers();

        // Return true to indicate the provider loaded successfully (even if some setup failed).
        return true;
    }

    private void registerInternalReceivers() {
        // Register broadcast receivers.
        try {
            Context context = getContext();
            if (context == null) return;

            registerReceiver(
                    context,
                    new HeapDumpFinishedReceiver(),
                    HelperConfig.HEAP_DUMP_COMPLETE_INTENT);
            registerReceiver(
                    context, new StartListeningReceiver(), HelperConfig.START_LISTENING_INTENT);
            registerReceiver(
                    context, new GetThresholdReceiver(), HelperConfig.GET_THRESHOLD_INTENT);
            registerReceiver(
                    context, new ForceDumpReceiver(), HelperConfig.FORCE_DUMP_ON_DEVICE_INTENT);

        } catch (Throwable t) {
            Log.e(HelperConfig.LOG_TAG, "Failed to register broadcast receivers.", t);
        }
    }

    /**
     * Helper method to safely register a receiver, handling API level differences for export status
     * and permissions.
     */
    private void registerReceiver(
            Context context, android.content.BroadcastReceiver receiver, String intentAction) {
        android.content.IntentFilter filter = new android.content.IntentFilter(intentAction);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            String permissionName =
                    context.getPackageName() + HelperConfig.INTERNAL_PERMISSION_SUFFIX;
            context.registerReceiver(receiver, filter, permissionName, null);
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
