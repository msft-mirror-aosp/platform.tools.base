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

package android.com.java.profilertester.leaks;

import android.com.java.profilertester.R;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.appcompat.app.AppCompatActivity;

public class LeakingActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_leaking);
        Log.e("LeakSample", "Entering LeakingActivity");

        // The Leak
        GlobalLeakingObject.leakedActivities.add(this);
        GlobalLeakingObject.logCurrentLeaks();

        // Finish activity after delay to allow LeakCanary/Profiler to track
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 1000);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.e("LeakSample", "Exiting LeakingActivity");
    }
}
