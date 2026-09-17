package android.com.java.profilertester;

import android.app.Activity;
import android.com.java.profilertester.taskcategory.AudioTaskCategory;
import android.com.java.profilertester.taskcategory.BackgroundTaskCategory;
import android.com.java.profilertester.taskcategory.BluetoothTaskCategory;
import android.com.java.profilertester.taskcategory.CameraTaskCategory;
import android.com.java.profilertester.taskcategory.CpuTaskCategory;
import android.com.java.profilertester.taskcategory.EventTaskCategory;
import android.com.java.profilertester.taskcategory.FeedbackTaskCategory;
import android.com.java.profilertester.taskcategory.LocationTaskCategory;
import android.com.java.profilertester.taskcategory.MemoryLeakTaskCategory;
import android.com.java.profilertester.taskcategory.MemoryTaskCategory;
import android.com.java.profilertester.taskcategory.NetworkTaskCategory;
import android.com.java.profilertester.taskcategory.ScreenBrightnessTaskCategory;
import android.com.java.profilertester.taskcategory.TaskCategory;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.Fragment;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * This is a placeholder view which contains two Spinners to decide the scenario
 * mCategorySpinner specifies the category of scenario
 * mTaskSpinner specifies descriptions of scenario
 */

public class MainActivityFragment extends Fragment {
    final static String TAG = MainActivityFragment.class.getName();

    private View mFragmentView;
    private MainLooperThread mMainLooperThread;

    private Spinner mCategorySpinner, mTaskSpinner, mCountSpinner;
    private View mCountContainer;
    private TaskCategory[] mTaskCategories;
    private List<ArrayAdapter<? extends TaskCategory.Task>> mTaskAdapters;

    // Default repeat count for tasks
    private int mTaskRepeatCount = 1;

    private final List<TaskCategory.Task.SelectionListener> mSelectionListeners = new ArrayList<>();
    /**
     * Tasks waiting for permission request results to run, see {@link
     * #onRequestPermissionsResult(int, String[], int[])}.
     */
    private final List<PendingPermissionTask> mPendingPermissionTasks = new ArrayList<>();

    private static final int PERF_MODE_RUNS_PER_TASK = 5;
    private static final String PREFS_NAME = "ProfilerTesterPrefs";
    private static final String PREF_CATEGORY = "selected_category";
    private static final String PREF_TASK = "selected_task";
    private static final String PREF_COUNT = "selected_count";
    private SharedPreferences mPrefs;
    private boolean mIsInPerfMode = false;
    private LinearLayout mPerfModeGroup;
    private TextView mPerfLogView;
    private Button mClearLogButton;
    private Button mExportLogButton;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mMainLooperThread = new MainLooperThread();
        mMainLooperThread.start();
    }

    @Override
    public View onCreateView(
            LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mFragmentView = inflater.inflate(R.layout.fragment_main, container, false);
        mPrefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        mCategorySpinner = (Spinner) mFragmentView.findViewById(R.id.category_spinner);
        mCountSpinner = (Spinner) mFragmentView.findViewById(R.id.count_spinner);
        mCountContainer = mFragmentView.findViewById(R.id.count_container);

        // Setup Count Spinner (1 to 5)
        Integer[] counts = new Integer[] {1, 2, 3, 4, 5};
        ArrayAdapter<Integer> countAdapter =
                new ArrayAdapter<>(
                        mFragmentView.getContext(), android.R.layout.simple_spinner_item, counts);
        countAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCountSpinner.setAdapter(countAdapter);
        // Default to 5
        mCountSpinner.setSelection(4);
        mCountSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parent, View view, int position, long id) {
                        mTaskRepeatCount = (Integer) parent.getItemAtPosition(position);
                        mPrefs.edit().putInt(PREF_COUNT, mTaskRepeatCount).apply();

                        int category = mCategorySpinner.getSelectedItemPosition();
                        if (mTaskCategories != null
                                && category >= 0
                                && category < mTaskCategories.length
                                && mTaskCategories[category] instanceof MemoryLeakTaskCategory) {
                            ((MemoryLeakTaskCategory) mTaskCategories[category])
                                    .setRepeatCount(mTaskRepeatCount);
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                        mTaskRepeatCount = 5;
                        mPrefs.edit().putInt(PREF_COUNT, mTaskRepeatCount).apply();

                        int category = mCategorySpinner.getSelectedItemPosition();
                        if (mTaskCategories != null
                                && category >= 0
                                && category < mTaskCategories.length
                                && mTaskCategories[category] instanceof MemoryLeakTaskCategory) {
                            ((MemoryLeakTaskCategory) mTaskCategories[category])
                                    .setRepeatCount(mTaskRepeatCount);
                        }
                    }
                });

        final Activity host = getActivity();
        final SleepControl sleepControl = new SleepControl();
        mTaskCategories =
                new TaskCategory[] {
                    new CpuTaskCategory(host.getFilesDir(), sleepControl),
                    new MemoryTaskCategory(sleepControl),
                    new MemoryLeakTaskCategory(host),
                    new NetworkTaskCategory(host),
                    new EventTaskCategory(
                            new Callable<Activity>() {
                                @Override
                                public Activity call() {
                                    return host;
                                }
                            },
                            (EditText) mFragmentView.findViewById(R.id.section_editor)),
                    new BluetoothTaskCategory(host),
                    new LocationTaskCategory(host, mMainLooperThread.getLooper()),
                    new ScreenBrightnessTaskCategory(host),
                    new CameraTaskCategory(host),
                    new AudioTaskCategory(host),
                    new FeedbackTaskCategory(host),
                    new BackgroundTaskCategory(
                            host, (EditText) mFragmentView.findViewById(R.id.section_editor))
                };
        ArrayAdapter<TaskCategory> categoryAdapters =
                new ArrayAdapter<>(
                        mFragmentView.getContext(),
                        android.R.layout.simple_spinner_item,
                        mTaskCategories);
        categoryAdapters.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mCategorySpinner.setAdapter(categoryAdapters);
        mTaskSpinner = (Spinner) mFragmentView.findViewById(R.id.task_spinner);
        // create an adaptor for each category
        mTaskAdapters = new ArrayList<>();
        for (TaskCategory taskCategory : mTaskCategories) {
            ArrayAdapter<? extends TaskCategory.Task> taskAdapter = new ArrayAdapter<>(
                    mFragmentView.getContext(),
                    android.R.layout.simple_spinner_item,
                    taskCategory.getTasks());
            taskAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            mTaskAdapters.add(taskAdapter);
            mSelectionListeners.addAll(taskCategory.getTaskSelectionListeners());
        }

        mCategorySpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parentView,
                            View selectedItemView,
                            int position,
                            long id) {
                        mPrefs.edit().putInt(PREF_CATEGORY, position).apply();
                        if (mTaskSpinner.getAdapter() != mTaskAdapters.get(position)) {
                            mTaskSpinner.setAdapter(mTaskAdapters.get(position));

                            Object selectedTaskItem = mTaskSpinner.getSelectedItem();
                            notifySelection(selectedTaskItem);
                        }

                        // Show count container only for Memory Leaks
                        if (mTaskCategories[position] instanceof MemoryLeakTaskCategory) {
                            mCountContainer.setVisibility(View.VISIBLE);
                            // Ensure the new category's repeat count matches the currently selected
                            // UI value
                            ((MemoryLeakTaskCategory) mTaskCategories[position])
                                    .setRepeatCount(mTaskRepeatCount);
                        } else {
                            mCountContainer.setVisibility(View.GONE);
                        }
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parentView) {
                        Log.i(TAG, "mCategorySpinner: nothing selected");
                    }
                });

        mTaskSpinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(
                            AdapterView<?> parentView,
                            View selectedItemView,
                            int position,
                            long id) {
                        mPrefs.edit().putInt(PREF_TASK, position).apply();
                        Object selectedTaskItem = parentView.getItemAtPosition(position);
                        notifySelection(selectedTaskItem);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parentView) {
                        Log.i(TAG, "mTaskSpinner: nothing selected");
                    }
                });

        // Restore selections from SharedPreferences
        int savedCategory = mPrefs.getInt(PREF_CATEGORY, 0);
        int savedTask = mPrefs.getInt(PREF_TASK, 0);
        int savedCount = mPrefs.getInt(PREF_COUNT, 5);

        if (savedCategory >= 0 && savedCategory < categoryAdapters.getCount()) {
            mCategorySpinner.setSelection(savedCategory);
        }

        mTaskSpinner.post(
                () -> {
                    if (mTaskSpinner.getAdapter() != null
                            && savedTask >= 0
                            && savedTask < mTaskSpinner.getAdapter().getCount()) {
                        mTaskSpinner.setSelection(savedTask);
                    }
                });

        // The count spinner values are 1..5, so index is value - 1
        int countIndex = savedCount - 1;
        if (countIndex >= 0 && countIndex < countAdapter.getCount()) {
            mCountSpinner.setSelection(countIndex);
        }

        mPerfModeGroup = (LinearLayout) mFragmentView.findViewById(R.id.perf_mode_group);
        mPerfLogView = (TextView) mFragmentView.findViewById(R.id.perf_log_text);
        mPerfLogView.setMovementMethod(new ScrollingMovementMethod());
        mClearLogButton = (Button) mFragmentView.findViewById(R.id.clear_log);
        mClearLogButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mPerfLogView.getText().length() > 0) {
                            mPerfLogView.setText("");
                        }
                    }
                });
        mExportLogButton = (Button) mFragmentView.findViewById(R.id.export_log);
        mExportLogButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (mPerfLogView.getText().length() > 0) {
                            Log.i(
                                    "ProfilerTester",
                                    "Task execution log:\n" + mPerfLogView.getText().toString());
                        }
                    }
                });

        return mFragmentView;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mMainLooperThread.quit();
    }

    private void notifySelection(@Nullable Object selectedItem) {
        if (selectedItem != null) {
            for (TaskCategory.Task.SelectionListener listener : mSelectionListeners) {
                listener.onSelection(selectedItem);
            }
        }
    }

    private void setScenario(int category, int task) {
        if (category != mCategorySpinner.getSelectedItemPosition()) {
            mCategorySpinner.setSelection(category);
            mTaskSpinner.setAdapter(mTaskAdapters.get(category));
        }
        mTaskSpinner.setSelection(task);
    }

    public boolean scenarioMoveBack() {
        int category = mCategorySpinner.getSelectedItemPosition();
        int task = mTaskSpinner.getSelectedItemPosition();
        --task;
        if (task == -1) {
            --category;
            if (category == -1) {
                return false;
            }
            task = mTaskAdapters.get(category).getCount() - 1;
        }
        setScenario(category, task);
        return true;
    }

    public boolean scenarioMoveForward() {
        int category = mCategorySpinner.getSelectedItemPosition();
        int task = mTaskSpinner.getSelectedItemPosition();
        ++task;
        if (task == mTaskAdapters.get(category).getCount()) {
            ++category;
            if (category >= mCategorySpinner.getAdapter().getCount()) {
                return false;
            }
            task = 0;
        }
        setScenario(category, task);
        return true;
    }

    public void testScenario() {
        Object categoryObject = mCategorySpinner.getSelectedItem();
        if (categoryObject == null) {
            return;
        }
        if (!(categoryObject instanceof TaskCategory)) {
            Log.e("ProfilerTester", "Invalid category spinner selection!");
            return;
        }
        final TaskCategory taskCategory = (TaskCategory) categoryObject;

        Object taskObject = mTaskSpinner.getSelectedItem();
        if (taskObject == null) {
            return;
        }
        if (!(taskObject instanceof TaskCategory.Task)) {
            Log.e("ProfilerTester", "Invalid task spinner selection!");
            return;
        }
        final TaskCategory.Task task = (TaskCategory.Task) taskObject;

        TaskCategory.RequestCodePermissions permissions = taskCategory.getPermissionsRequired(task);
        Runnable taskRunnable =
                new Runnable() {
                    @Override
                    public void run() {
                        long[] startTimeMs = new long[1];
                        startTimeMs[0] = System.currentTimeMillis();
                        int[] runCount = new int[1];
                        runCount[0] = mIsInPerfMode ? PERF_MODE_RUNS_PER_TASK : 1;
                        taskCategory.executeTask(
                                task,
                                new TaskCategory.PostExecuteRunner() {
                                    @Override
                                    public void accept(@Nullable String s) {
                                        long endTimeMs = System.currentTimeMillis();
                                        logTaskRun(taskCategory, task, startTimeMs[0], endTimeMs);
                                        View view = getView();
                                        if (view != null && s != null) {
                                            Toast.makeText(getActivity(), s, Toast.LENGTH_LONG)
                                                    .show();
                                        }
                                        runCount[0]--;
                                        if (runCount[0] > 0) {
                                            // Sleep for a second to let CPU cool down for
                                            // more repeatable timing numbers.
                                            try {
                                                TimeUnit.SECONDS.sleep(1);
                                            } catch (InterruptedException e) {
                                                e.printStackTrace();
                                            }
                                            startTimeMs[0] = System.currentTimeMillis();
                                            taskCategory.executeTask(task, this);
                                        }
                                    }
                                });
                    }
                };
        if (hasAllPermissions(permissions)) {
            taskRunnable.run();
        } else {
            mPendingPermissionTasks.add(new PendingPermissionTask(permissions, taskRunnable));
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (mTaskCategories == null) {
            return;
        }

        for (TaskCategory taskCategory : mTaskCategories) {
            taskCategory.onActivityResult(requestCode, resultCode, data);
        }
    }


    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length == 0) {
            return;
        }
        boolean isGranted = true;
        for (int grantResult : grantResults) {
            if (grantResult != PackageManager.PERMISSION_GRANTED) {
                isGranted = false;
                break;
            }
        }
        for (PendingPermissionTask task : mPendingPermissionTasks) {
            if (requestCode == task.mPermissions.getRequestCode().ordinal()) {
                if (isGranted) {
                    task.mTaskRunnable.run();
                }
                mPendingPermissionTasks.remove(task);
                break;
            }
        }
    }

    private boolean hasAllPermissions(TaskCategory.RequestCodePermissions codePermissions) {
        boolean hasAllPermissions = true;
        String[] permissions = codePermissions.getPermissions();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            for (String p : permissions) {
                if (ActivityCompat.checkSelfPermission(getActivity().getApplicationContext(), p)
                        != PackageManager.PERMISSION_GRANTED) {
                    hasAllPermissions = false;
                    break;
                }
            }
        }
        if (!hasAllPermissions) {
            ActivityCompat.requestPermissions(
                    getActivity(), permissions, codePermissions.getRequestCode().ordinal());
        }
        return hasAllPermissions;
    }

    public void togglePerfMode() {
        if (mIsInPerfMode) {
            mIsInPerfMode = false;
            mPerfModeGroup.setVisibility(View.INVISIBLE);
            mPerfLogView.setText("");
        } else {
            mIsInPerfMode = true;
            mPerfModeGroup.setVisibility(View.VISIBLE);
        }
    }

    private void logTaskRun(
            TaskCategory category, TaskCategory.Task task, long startTimeMs, long endTimeMs) {
        if (!mIsInPerfMode) {
            return;
        }

        long durationMs = endTimeMs - startTimeMs;
        StringBuilder logBuilder =
                new StringBuilder()
                        .append(DateFormat.getTimeInstance(DateFormat.MEDIUM).format(startTimeMs))
                        .append(", ")
                        .append(category)
                        .append(", ")
                        .append(task)
                        .append(", duration ")
                        .append(durationMs)
                        .append(" ms");
        if (mPerfLogView.getText().length() > 0) {
            mPerfLogView.append("\n");
        }
        mPerfLogView.append(logBuilder.toString());
    }

    public final class SleepControl {
        public void sleepIfAllowed(TimeUnit timeUnit, long timeout) throws InterruptedException {
            if (!mIsInPerfMode) {
                timeUnit.sleep(timeout);
            }
        }
    }

    private static final class PendingPermissionTask {
        @NonNull private final TaskCategory.RequestCodePermissions mPermissions;
        @NonNull private final Runnable mTaskRunnable;

        PendingPermissionTask(
                @NonNull TaskCategory.RequestCodePermissions permissions,
                @NonNull Runnable runnable) {
            mPermissions = permissions;
            mTaskRunnable = runnable;
        }
    }
}
