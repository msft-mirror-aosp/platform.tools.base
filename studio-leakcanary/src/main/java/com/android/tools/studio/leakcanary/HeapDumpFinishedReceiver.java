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

import java.util.concurrent.TimeUnit;

/**
 * Receives the broadcast from the profiler agent when a heap dump is finished.
 *
 * <p>This receiver acts as a bridge between the Android Studio Profiler and the LeakCanary instance
 * running in the app. When the Profiler finishes analyzing a heap dump, it sends a broadcast. This
 * receiver catches that broadcast and instructs LeakCanary to clear its internal state (watched
 * objects) up to the timestamp of the dump, preventing duplicate reporting of the same leaks.
 */
@SuppressWarnings("unused")
public class HeapDumpFinishedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (HelperConfig.HEAP_DUMP_COMPLETE_INTENT.equals(intent.getAction())) {
            long heapDumpTimestampNs =
                    intent.getLongExtra(HelperConfig.HEAP_DUMP_COMPLETE_EXTRA, -1L);
            if (heapDumpTimestampNs != -1L) {
                Log.d(
                        HelperConfig.LOG_TAG,
                        "Received heap dump finished broadcast with timestamp: "
                                + heapDumpTimestampNs
                                + " (ns)");
                // The timestamp from the profiler pipeline is in nanoseconds.
                // The AppWatcher API expects milliseconds. We must convert it here.
                long heapDumpTimestampMillis = TimeUnit.NANOSECONDS.toMillis(heapDumpTimestampNs);

                // Signal the listener to clear its state using the precise timestamp.
                StudioLeakCanaryListener.onHeapDumpFinished(heapDumpTimestampMillis);
            } else {
                Log.w(
                        HelperConfig.LOG_TAG,
                        "Received heap dump finished broadcast but timestamp was missing.");
            }
        }
    }
}
