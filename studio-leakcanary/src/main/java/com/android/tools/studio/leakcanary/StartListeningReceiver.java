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
 * Receives the broadcast when Android Studio starts listening for retained objects.
 *
 * <p>The broadcast has the mode (ON_DEVICE or ON_HOST). This updates the mode, then sends the
 * current retained count, so Studio shows the right first value in both modes.
 */
@SuppressWarnings("unused")
public class StartListeningReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (HelperConfig.START_LISTENING_INTENT.equals(intent.getAction())) {
            int mode = intent.getIntExtra("leakcanary_mode", StudioLeakCanaryManager.MODE_ON_HOST);
            Log.d(HelperConfig.LOG_TAG, "Received start listening broadcast with mode: " + mode);

            StudioLeakCanaryManager manager = StudioLeakCanaryManager.getInstance();
            manager.setMode(mode);

            // Send the current count now, so Studio gets the first value in both modes.
            StudioLeakCanaryListener.sendCurrentCount();
        }
    }
}
