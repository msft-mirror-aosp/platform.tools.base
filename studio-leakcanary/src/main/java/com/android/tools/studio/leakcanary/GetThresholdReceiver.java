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

import java.lang.reflect.Method;

/** Receives the broadcast when Android Studio requests the retained visible threshold. */
@SuppressWarnings("unused")
public class GetThresholdReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (HelperConfig.GET_THRESHOLD_INTENT.equals(intent.getAction())) {
            Log.d(HelperConfig.LOG_TAG, "Received broadcast request for LeakCanary threshold.");
            int threshold =
                    5; // Default fallback value to indicate presence even if reflection fails.
            try {
                ClassLoader classLoader = context.getClassLoader();
                Class<?> leakCanaryClass =
                        Class.forName(HelperConfig.LEAK_CANARY_CLASS, false, classLoader);
                java.lang.reflect.Field instanceField =
                        leakCanaryClass.getField(HelperConfig.INSTANCE_FIELD);
                Object leakCanaryInstance = instanceField.get(null);
                Method getConfigMethod = leakCanaryClass.getMethod(HelperConfig.GET_CONFIG_METHOD);
                Object config = getConfigMethod.invoke(leakCanaryInstance);
                Method getThresholdMethod =
                        config.getClass()
                                .getMethod(HelperConfig.GET_RETAINED_VISIBLE_THRESHOLD_METHOD);
                threshold = (int) getThresholdMethod.invoke(config);
                Log.d(
                        HelperConfig.LOG_TAG,
                        "Successfully retrieved threshold via reflection: " + threshold);
            } catch (Exception e) {
                // If reflection fails (e.g. ProGuard, incompatible version), we log the error but
                // still return
                // a default non-zero value. This confirms that the Studio library itself is present
                // and responding,
                // which allows the Studio UI to enable the 'Start' button.
                Log.e(
                        HelperConfig.LOG_TAG,
                        "Failed to get threshold via reflection. Returning default: " + threshold,
                        e);
            }

            Intent resultIntent = new Intent(HelperConfig.THRESHOLD_RESULT_INTENT);
            resultIntent.putExtra(HelperConfig.THRESHOLD_EXTRA, threshold);
            resultIntent.setPackage(context.getPackageName());
            context.sendBroadcast(resultIntent);
            Log.d(HelperConfig.LOG_TAG, "Sent threshold result broadcast with value: " + threshold);
        }
    }
}
