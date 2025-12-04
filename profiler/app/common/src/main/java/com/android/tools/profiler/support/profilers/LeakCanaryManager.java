/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.tools.profiler.support.profilers;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.annotation.Keep;

import java.lang.reflect.Method;

/**
 * A helper class that acts as a bridge between the native profiler agent (perfa) and the
 * Studio-LeakCanary library in the app.
 */
public class LeakCanaryManager {
    private static final String TAG = "studio.profiler";
    public static final String HEAP_DUMP_COMPLETE_INTENT = "studio.leakcanary.HEAP_DUMP_FINISHED";
    public static final String HEAP_DUMP_COMPLETE_EXTRA = "heap_dump_timestamp";

    private static Context sApplicationContext;

    /** Broadcasts the heap dump completion signal to the app. Called via JNI. */
    @Keep
    @SuppressWarnings("unused")
    public static void signalHeapDumpComplete(long heapDumpTimestamp) {
        if (sApplicationContext == null) {
            try {
                Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
                Method currentApplicationMethod =
                        activityThreadClass.getMethod("currentApplication");
                sApplicationContext = (Context) currentApplicationMethod.invoke(null);
            } catch (Exception e) {
                Log.e(TAG, "Failed to get application context.", e);
                return;
            }
        }

        try {
            Intent intent = new Intent(HEAP_DUMP_COMPLETE_INTENT);
            intent.putExtra(HEAP_DUMP_COMPLETE_EXTRA, heapDumpTimestamp);
            intent.setPackage(sApplicationContext.getPackageName());
            sApplicationContext.sendBroadcast(intent);
            Log.d(TAG, "HEAP_DUMP_FINISHED broadcast sent successfully.");
        } catch (Exception e) {
            Log.e(TAG, "Failed to send HEAP_DUMP_FINISHED broadcast.", e);
        }
    }
}
