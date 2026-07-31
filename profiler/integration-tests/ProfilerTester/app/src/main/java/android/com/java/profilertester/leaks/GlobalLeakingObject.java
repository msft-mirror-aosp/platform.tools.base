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

import android.app.Activity;
import android.app.Service;
import android.util.Log;
import android.view.View;

import androidx.fragment.app.Fragment;

import java.util.ArrayList;
import java.util.List;

public class GlobalLeakingObject {
    public static final List<Activity> leakedActivities = new ArrayList<>();
    public static final List<Fragment> leakedFragments = new ArrayList<>();
    public static final List<View> leakedFragmentViews = new ArrayList<>();
    public static final List<Service> leakedServices = new ArrayList<>();
    public static final List<Object> leakedViewModels = new ArrayList<>();

    public static void clearAll() {
        leakedActivities.clear();
        leakedFragments.clear();
        leakedFragmentViews.clear();
        leakedServices.clear();
        leakedViewModels.clear();
        logCurrentLeaks();
    }

    public static void logCurrentLeaks() {
        int total =
                leakedActivities.size()
                        + leakedFragments.size()
                        + leakedFragmentViews.size()
                        + leakedServices.size()
                        + leakedViewModels.size();
        Log.i(
                "LeakSample",
                "Total leaks currently held: "
                        + total
                        + " [Activities: "
                        + leakedActivities.size()
                        + ", Fragments: "
                        + leakedFragments.size()
                        + ", FragmentViews: "
                        + leakedFragmentViews.size()
                        + ", Services: "
                        + leakedServices.size()
                        + ", ViewModels: "
                        + leakedViewModels.size()
                        + "]");
    }
}
