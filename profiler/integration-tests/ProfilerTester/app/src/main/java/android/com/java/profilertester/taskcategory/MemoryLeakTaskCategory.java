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

package android.com.java.profilertester.taskcategory;

import android.app.Activity;
import android.com.java.profilertester.R;
import android.com.java.profilertester.leaks.GlobalLeakingObject;
import android.com.java.profilertester.leaks.LeakingActivity;
import android.com.java.profilertester.leaks.LeakingFragment;
import android.com.java.profilertester.leaks.LeakingFragmentView;
import android.com.java.profilertester.leaks.LeakingService;
import android.com.java.profilertester.leaks.LeakingViewModelActivity;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;

import java.util.Arrays;
import java.util.List;

public final class MemoryLeakTaskCategory extends TaskCategory {

    @NonNull
    private final List<? extends Task> mTasks =
            Arrays.asList(
                    new LeakActivityTask(),
                    new LeakFragmentTask(),
                    new LeakFragmentViewTask(),
                    new LeakViewModelTask(),
                    new LeakServiceTask(),
                    new ClearLeaksTask());

    @NonNull private final Activity mHostActivity;

    private int mRepeatCount = 5;

    public MemoryLeakTaskCategory(@NonNull Activity hostActivity) {
        mHostActivity = hostActivity;
    }

    @NonNull
    @Override
    public List<? extends Task> getTasks() {
        return mTasks;
    }

    @NonNull
    @Override
    protected String getCategoryName() {
        return "Memory Leaks";
    }

    public int getRepeatCount() {
        return mRepeatCount;
    }

    public void setRepeatCount(int repeatCount) {
        mRepeatCount = repeatCount;
    }

    private final class LeakActivityTask extends Task {
        @NonNull
        @Override
        protected String execute() {
            for (int i = 0; i < MemoryLeakTaskCategory.this.getRepeatCount(); i++) {
                Intent intent = new Intent(mHostActivity, LeakingActivity.class);
                mHostActivity.startActivity(intent);
            }
            return "Started "
                    + MemoryLeakTaskCategory.this.getRepeatCount()
                    + " LeakingActivities. They will finish and leak in 1s.";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Activity Leak";
        }
    }

    private final class LeakFragmentTask extends Task {
        @NonNull
        @Override
        protected String execute() {
            if (mHostActivity instanceof FragmentActivity) {
                mHostActivity.runOnUiThread(
                        () -> {
                            for (int i = 0; i < MemoryLeakTaskCategory.this.getRepeatCount(); i++) {
                                ((FragmentActivity) mHostActivity)
                                        .getSupportFragmentManager()
                                        .beginTransaction()
                                        .add(R.id.fragment_container, new LeakingFragment())
                                        .commit();
                            }
                        });
                return "Added "
                        + MemoryLeakTaskCategory.this.getRepeatCount()
                        + " LeakingFragments. They will remove themselves and leak in 1s.";
            }
            return "Host activity is not a FragmentActivity.";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Fragment Leak";
        }
    }

    private final class LeakFragmentViewTask extends Task {
        @NonNull
        @Override
        protected String execute() {
            if (mHostActivity instanceof FragmentActivity) {
                mHostActivity.runOnUiThread(
                        () -> {
                            for (int i = 0; i < MemoryLeakTaskCategory.this.getRepeatCount(); i++) {
                                ((FragmentActivity) mHostActivity)
                                        .getSupportFragmentManager()
                                        .beginTransaction()
                                        .add(R.id.fragment_container, new LeakingFragmentView())
                                        .commit();
                            }
                        });
                return "Added "
                        + MemoryLeakTaskCategory.this.getRepeatCount()
                        + " LeakingFragmentViews. They will remove themselves and leak in 1s.";
            }
            return "Host activity is not a FragmentActivity.";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Fragment View Leak";
        }
    }

    private final class LeakViewModelTask extends Task {
        @NonNull
        @Override
        protected String execute() {
            for (int i = 0; i < MemoryLeakTaskCategory.this.getRepeatCount(); i++) {
                Intent intent = new Intent(mHostActivity, LeakingViewModelActivity.class);
                mHostActivity.startActivity(intent);
            }
            return "Started "
                    + MemoryLeakTaskCategory.this.getRepeatCount()
                    + " LeakingViewModelActivities. They will finish and leak ViewModels in 1s.";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "ViewModel Leak";
        }
    }

    private final class LeakServiceTask extends Task {
        private final Class<?>[] mServiceClasses =
                new Class<?>[] {
                    LeakingService.LS1.class,
                    LeakingService.LS2.class,
                    LeakingService.LS3.class,
                    LeakingService.LS4.class,
                    LeakingService.LS5.class
                };

        @NonNull
        @Override
        protected String execute() {
            int count =
                    Math.min(MemoryLeakTaskCategory.this.getRepeatCount(), mServiceClasses.length);
            for (int i = 0; i < count; i++) {
                Intent intent = new Intent(mHostActivity, mServiceClasses[i]);
                mHostActivity.startService(intent);
            }
            return "Started "
                    + count
                    + " LeakingServices. They will stop themselves and leak in 1s.";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Service Leak";
        }
    }

    private static final class ClearLeaksTask extends Task {
        @NonNull
        @Override
        protected String execute() {
            GlobalLeakingObject.clearAll();
            return "Cleared all global leak references.";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Clear All Leaks";
        }
    }
}
