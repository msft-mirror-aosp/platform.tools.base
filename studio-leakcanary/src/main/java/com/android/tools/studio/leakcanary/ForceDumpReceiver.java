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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Receives the broadcast to trigger a manual heap dump on the device.
 *
 * <p>This is only applicable when the application is running in ON_DEVICE mode. It checks if there
 * are actually any retained objects before delegating to LeakCanary's internal heap dumper to avoid
 * unnecessary freezes.
 */
public class ForceDumpReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (HelperConfig.FORCE_DUMP_ON_DEVICE_INTENT.equals(intent.getAction())) {
            Log.d(HelperConfig.LOG_TAG, "Received FORCE_DUMP_ON_DEVICE broadcast.");

            if (StudioLeakCanaryManager.getInstance().getCurrentMode()
                    != StudioLeakCanaryManager.MODE_ON_DEVICE) {
                Log.w(
                        HelperConfig.LOG_TAG,
                        "Force dump on device requested but not in ON_DEVICE mode.");
                return;
            }

            int retainedCount = LeakCanaryReflectionHelper.getRetainedObjectCount();
            if (retainedCount > 0) {
                Log.d(
                        HelperConfig.LOG_TAG,
                        "Retained objects found ("
                                + retainedCount
                                + "). Triggering LeakCanary.dumpHeap().");
                LeakCanaryReflectionHelper.triggerDumpHeap();
            } else {
                Log.d(
                        HelperConfig.LOG_TAG,
                        "No retained objects found. Skipping LeakCanary.dumpHeap().");
            }
        }
    }
}
