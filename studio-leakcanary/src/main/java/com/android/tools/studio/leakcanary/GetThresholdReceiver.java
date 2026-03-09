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

/** Receives the broadcast when Android Studio requests the retained visible threshold. */
@SuppressWarnings("unused")
public class GetThresholdReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (HelperConfig.GET_THRESHOLD_INTENT.equals(intent.getAction())) {
            Log.d(HelperConfig.LOG_TAG, "Received broadcast request for LeakCanary threshold.");

            int threshold = LeakCanaryReflectionHelper.getRetainedVisibleThreshold();

            Intent resultIntent = new Intent(HelperConfig.THRESHOLD_RESULT_INTENT);
            resultIntent.putExtra(HelperConfig.THRESHOLD_EXTRA, threshold);
            resultIntent.setPackage(context.getPackageName());
            context.sendBroadcast(resultIntent);
            Log.d(HelperConfig.LOG_TAG, "Sent threshold result broadcast with value: " + threshold);
        }
    }
}
