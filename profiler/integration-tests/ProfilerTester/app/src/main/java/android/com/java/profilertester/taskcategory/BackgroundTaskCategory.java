package android.com.java.profilertester.taskcategory;

import static android.content.Context.ALARM_SERVICE;
import static android.content.Context.JOB_SCHEDULER_SERVICE;
import static android.os.PowerManager.PARTIAL_WAKE_LOCK;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class BackgroundTaskCategory extends TaskCategory {

    private static final String NUM_OF_WAKEUPS = "Number of Wakeups";

    // Repeating alarms have system-enforced minimum interval so we need a longer task time.
    private static final long ALARM_TASK_TIME_MS = TimeUnit.MINUTES.toMillis(2);

    private static PowerManager.WakeLock sLeakedWakeLock = null;

    @NonNull private final List<? extends Task> mTasks;

    @NonNull private final Activity mHostActivity;

    public BackgroundTaskCategory(@NonNull Activity hostActivity) {
        this(hostActivity, null);
    }

    public BackgroundTaskCategory(@NonNull Activity hostActivity, @Nullable EditText textEditor) {
        mHostActivity = hostActivity;
        mTasks =
                Arrays.asList(
                        new TimedWakeLockTask(
                                TimeUnit.SECONDS.toMillis(15),
                                "Short Wake Lock (15s)",
                                "ShortWakeLock"),
                        new TimedWakeLockTask(
                                TimeUnit.SECONDS.toMillis(65),
                                "Long Wake Lock (65s - BatteryStats)",
                                "LongWakeLock65s"),
                        new TimedWakeLockTask(
                                TimeUnit.SECONDS.toMillis(120),
                                "Long Wake Lock (120s - 2 min)",
                                "LongWakeLock120s"),
                        new TimedWakeLockTask(
                                TimeUnit.SECONDS.toMillis(300),
                                "Long Wake Lock (300s - 5 min)",
                                "LongWakeLock300s"),
                        new CustomWakeLockTask(textEditor),
                        new AcquireLeakedWakeLockTask(),
                        new ReleaseLeakedWakeLockTask(),
                        new AlarmTask(),
                        new RepeatingAlarmTask(),
                        new SingleJobTask(),
                        new PeriodicJobTask());
    }

    @NonNull
    @Override
    public List<? extends Task> getTasks() {
        return mTasks;
    }

    @NonNull
    @Override
    protected String getCategoryName() {
        return "Background Tasks";
    }

    private final class TimedWakeLockTask extends Task {
        private final long mDurationMs;
        @NonNull private final String mDescription;
        @NonNull private final String mTagSuffix;

        TimedWakeLockTask(long durationMs, @NonNull String description, @NonNull String tagSuffix) {
            mDurationMs = durationMs;
            mDescription = description;
            mTagSuffix = tagSuffix;
        }

        @NonNull
        @Override
        protected String execute() {
            PowerManager powerManager = mHostActivity.getSystemService(PowerManager.class);
            if (powerManager == null) {
                return "Could not acquire the PowerManager!";
            }
            String tag = mHostActivity.getPackageName() + ":" + mTagSuffix;
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PARTIAL_WAKE_LOCK, tag);
            wakeLock.acquire(mDurationMs);
            try {
                SystemClock.sleep(mDurationMs);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
            return "Released wake lock after "
                    + TimeUnit.MILLISECONDS.toSeconds(mDurationMs)
                    + "s.";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return mDescription;
        }
    }

    private final class CustomWakeLockTask extends Task {
        @Nullable private final EditText mTextEditor;
        @Nullable private final CustomWakeLockSelectionListener mListener;

        private long mParsedDurationMs =
                70000; // default 70 seconds (> 60s long wake lock threshold)

        CustomWakeLockTask(@Nullable EditText textEditor) {
            mTextEditor = textEditor;
            mListener = textEditor != null ? new CustomWakeLockSelectionListener(textEditor) : null;
        }

        @Override
        public void preExecute() {
            if (mTextEditor != null) {
                String input = mTextEditor.getText().toString().trim();
                if (!input.isEmpty()) {
                    try {
                        mParsedDurationMs = TimeUnit.SECONDS.toMillis(Long.parseLong(input));
                    } catch (NumberFormatException e) {
                        Log.w("BackgroundTaskCategory", "Invalid duration input: " + input);
                    }
                }
            }
        }

        @Override
        public boolean usesTextEditor() {
            return true;
        }

        @NonNull
        @Override
        protected String execute() {
            long durationMs = mParsedDurationMs;
            long durationSec = TimeUnit.MILLISECONDS.toSeconds(durationMs);
            PowerManager powerManager = mHostActivity.getSystemService(PowerManager.class);
            if (powerManager == null) {
                return "Could not acquire the PowerManager!";
            }
            String tag = mHostActivity.getPackageName() + ":CustomWakeLockTag_" + durationSec + "s";
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PARTIAL_WAKE_LOCK, tag);
            wakeLock.acquire(durationMs);
            try {
                SystemClock.sleep(durationMs);
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
            }
            return "Released custom wake lock after " + durationSec + "s.";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Custom Duration Wake Lock";
        }

        @Nullable
        @Override
        protected SelectionListener getSelectionListener() {
            return mListener;
        }

        private final class CustomWakeLockSelectionListener implements SelectionListener {
            @NonNull private final EditText mTextEditor;

            private CustomWakeLockSelectionListener(@NonNull EditText textEditor) {
                mTextEditor = textEditor;
            }

            @Override
            public void onSelection(@NonNull Object selectedItem) {
                if (selectedItem instanceof Task && ((Task) selectedItem).usesTextEditor()) {
                    mTextEditor.setVisibility(View.VISIBLE);
                    mTextEditor.setHint("Duration in seconds (default 70)");
                } else {
                    mTextEditor.setVisibility(View.INVISIBLE);
                }
            }
        }
    }

    private final class AcquireLeakedWakeLockTask extends Task {
        @SuppressLint("WakelockTimeout")
        @NonNull
        @Override
        protected String execute() {
            PowerManager powerManager = mHostActivity.getSystemService(PowerManager.class);
            if (powerManager == null) {
                return "Could not acquire the PowerManager!";
            }
            if (sLeakedWakeLock != null && sLeakedWakeLock.isHeld()) {
                return "Leaked wake lock is already acquired and held!";
            }
            sLeakedWakeLock =
                    powerManager.newWakeLock(
                            PARTIAL_WAKE_LOCK,
                            mHostActivity.getPackageName() + ":LeakedWakeLockTag");
            sLeakedWakeLock.acquire();
            return "Acquired leaked wake lock indefinitely! (Use 'Release Leaked Wake Lock' to"
                    + " release)";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Leaked Wake Lock (Acquire & Keep)";
        }
    }

    private final class ReleaseLeakedWakeLockTask extends Task {
        @NonNull
        @Override
        protected String execute() {
            if (sLeakedWakeLock != null && sLeakedWakeLock.isHeld()) {
                sLeakedWakeLock.release();
                sLeakedWakeLock = null;
                return "Released leaked wake lock.";
            }
            return "No leaked wake lock is currently held.";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Release Leaked Wake Lock";
        }
    }

    private final class RepeatingAlarmTask extends Task {

        @NonNull
        @Override
        protected String execute() {
            Context context = mHostActivity.getApplicationContext();
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
            if (alarmManager == null) {
                return "Error setting alarm!";
            }
            Intent intent = new Intent(context, AlarmReceiver.class);
            PendingIntent pendingIntent =
                    PendingIntent.getBroadcast(
                            mHostActivity.getApplicationContext(),
                            ActivityRequestCodes.ALARM.ordinal(),
                            intent,
                            PendingIntent.FLAG_UPDATE_CURRENT);
            // As of API 22, system enforces a minimum of 1 minute interval and at least 5 seconds
            // before the initial trigger.
            alarmManager.setRepeating(
                    AlarmManager.RTC_WAKEUP,
                    System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5),
                    TimeUnit.MINUTES.toMillis(1),
                    pendingIntent);
            if (pendingIntent == null) {
                return "Error setting alarm!";
            }

            try {
                SystemClock.sleep(ALARM_TASK_TIME_MS);
            } catch (Exception e) {
                e.printStackTrace();
            }

            alarmManager.cancel(pendingIntent);
            return "Alarms cancelled normally.";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Repeating Alarm";
        }
    }

    private final class AlarmTask extends Task {

        @RequiresApi(api = Build.VERSION_CODES.N)
        @NonNull
        @Override
        protected String execute() {
            Context context = mHostActivity.getApplicationContext();
            AlarmManager alarmManager = (AlarmManager) context.getSystemService(ALARM_SERVICE);
            if (alarmManager == null) {
                return "Error setting alarm!";
            }
            AlarmManager.OnAlarmListener listener = () -> {};
            alarmManager.set(
                    AlarmManager.RTC_WAKEUP, TimeUnit.SECONDS.toMillis(5), "TEST", listener, null);

            try {
                SystemClock.sleep(TimeUnit.SECONDS.toMillis(4));
            } catch (Exception e) {
                e.printStackTrace();
            }

            alarmManager.cancel(listener);
            return "Alarms cancelled normally.";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "1-shot Alarm";
        }
    }

    public static final class AlarmReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            intent.putExtra(NUM_OF_WAKEUPS, intent.getIntExtra(NUM_OF_WAKEUPS, 0) + 1);
        }
    }

    public static class MyJobService extends JobService {
        private final String TAG = MyJobService.class.getSimpleName();

        @SuppressLint("UseSparseArrays")
        @NonNull
        private Map<Integer, MyServiceTask> mServiceTaskMap = new HashMap<>();

        @NonNull private ThreadPoolExecutor mThreadPoolExecutor = getDefaultThreadPoolExecutor();

        @Override
        public boolean onStartJob(JobParameters jobParameters) {
            Log.d(TAG, "Job started with ID: " + jobParameters.getJobId());
            MyServiceTask task = new MyServiceTask();
            mServiceTaskMap.put(jobParameters.getJobId(), task);
            task.executeOnExecutor(mThreadPoolExecutor, jobParameters);
            return true;
        }

        @Override
        public boolean onStopJob(JobParameters jobParameters) {
            Log.d(TAG, "Job stopped with ID: " + jobParameters.getJobId());
            MyServiceTask task = mServiceTaskMap.get(jobParameters.getJobId());
            task.cancel(true);
            return false;
        }

        @SuppressLint("StaticFieldLeak")
        private class MyServiceTask extends AsyncTask<JobParameters, Void, Void> {
            @Override
            public Void doInBackground(JobParameters... parameters) {
                Log.d(TAG, "Job running with ID: " + parameters[0].getJobId());
                try {
                    SystemClock.sleep(TimeUnit.SECONDS.toMillis(2L));
                } catch (Exception e) {
                    e.printStackTrace();
                }
                jobFinished(parameters[0], false);
                return null;
            }
        }

        private static ThreadPoolExecutor getDefaultThreadPoolExecutor() {
            ThreadPoolExecutor threadPoolExecutor =
                    new ThreadPoolExecutor(
                            4,
                            128,
                            30,
                            TimeUnit.SECONDS,
                            new LinkedBlockingQueue<Runnable>(128),
                            new ThreadFactory() {
                                private final AtomicInteger mCount = new AtomicInteger(1);

                                public Thread newThread(@NonNull Runnable r) {
                                    return new Thread(
                                            r, "JobAsyncTask #" + mCount.getAndIncrement());
                                }
                            });
            threadPoolExecutor.allowCoreThreadTimeOut(true);
            return threadPoolExecutor;
        }
    }

    private abstract class JobTask extends Task {

        @NonNull
        @Override
        protected String execute() {
            Context context = mHostActivity.getApplicationContext();
            ComponentName componentName = new ComponentName(context, MyJobService.class);
            JobScheduler scheduler = (JobScheduler) context.getSystemService(JOB_SCHEDULER_SERVICE);
            if (scheduler == null) {
                return "Error setting job scheduler!";
            }

            scheduler.schedule(createJob(componentName));

            try {
                SystemClock.sleep(TimeUnit.SECONDS.toMillis(6L));
            } catch (Exception e) {
                return getTaskDescription() + " interrupted!";
            } finally {
                scheduler.cancelAll();
            }
            return "Job finished successfully";
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Job";
        }

        protected abstract JobInfo createJob(ComponentName componentName);
    }

    private final class SingleJobTask extends JobTask {

        @Override
        protected JobInfo createJob(ComponentName componentName) {
            JobInfo.Builder builder = new JobInfo.Builder(0, componentName);
            builder.setOverrideDeadline(TimeUnit.SECONDS.toMillis(2));
            builder.setMinimumLatency(TimeUnit.SECONDS.toMillis(1));
            return builder.build();
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Single Job";
        }
    }

    private final class PeriodicJobTask extends JobTask {

        @Override
        protected JobInfo createJob(ComponentName componentName) {
            JobInfo.Builder builder = new JobInfo.Builder(1, componentName);
            // JobInfo has a 15 minute minimum period interval and we can not see
            // the next job scheduled.
            builder.setPeriodic(TimeUnit.MINUTES.toMillis(15));
            return builder.build();
        }

        @NonNull
        @Override
        protected String getTaskDescription() {
            return "Periodic Job";
        }
    }
}
