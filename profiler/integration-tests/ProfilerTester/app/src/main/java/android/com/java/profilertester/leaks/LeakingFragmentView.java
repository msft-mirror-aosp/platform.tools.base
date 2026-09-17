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

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class LeakingFragmentView extends Fragment {

    @Nullable
    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater,
            @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        // Just return a basic View for testing
        return new View(requireContext());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Fragment view leak
        GlobalLeakingObject.leakedFragmentViews.add(view);
        GlobalLeakingObject.logCurrentLeaks();

        new Handler(Looper.getMainLooper())
                .postDelayed(
                        () -> {
                            if (isAdded()) {
                                getParentFragmentManager()
                                        .beginTransaction()
                                        .remove(LeakingFragmentView.this)
                                        .commit();
                            }
                        },
                        1000);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.e("LeakSample", "Entering LeakingFragmentView");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.e("LeakSample", "Exiting LeakingFragmentView");
    }
}
