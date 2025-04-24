/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.google.test.inspectors.background

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_ONE_SHOT
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.PowerManager.PARTIAL_WAKE_LOCK
import androidx.core.app.AlarmManagerCompat
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import androidx.lifecycle.LiveData
import androidx.lifecycle.asFlow
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import com.google.test.inspectors.Logger
import com.google.test.inspectors.ui.scafold.AppScaffoldViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

private val jobId = AtomicInteger(1)

@HiltViewModel
internal class BackgroundViewModel @Inject constructor(private val application: Application) :
  AppScaffoldViewModel(), BackgroundScreenActions {

  private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
    setSnack("Error: ${throwable.message}")
    Logger.error("Error: ${throwable.message}", throwable)
  }

  private val scope = CoroutineScope(viewModelScope.coroutineContext + exceptionHandler)
  private val wakeLock =
    application
      .getSystemService(PowerManager::class.java)
      .newWakeLock(PARTIAL_WAKE_LOCK, "com.google.test.inspectors:test")

  override fun startJob() {
    val id = jobId.getAndIncrement()
    val job =
      JobInfo.Builder(id, ComponentName(application, AppJobService::class.java))
        .safeSetRequiresBatteryNotLow(true)
        .build()
    val jobScheduler = application.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    jobScheduler.schedule(job)

    setSnack("Started job $jobId")
  }

  override fun startWork() {
    startWork(InjectedWorker::class.java)
    startWork(ManualWorker::class.java)
  }

  private fun startWork(worker: Class<out Worker>) {
    val request = OneTimeWorkRequest.Builder(worker).build()
    val workManager = WorkManager.getInstance(application)
    val work: LiveData<WorkInfo?> = workManager.getWorkInfoByIdLiveData(request.id)

    scope.launch {
      work.asFlow().filterNotNull().collect {
        Logger.info("State of ${request.id}: ${it.state}")
        if (it.state == WorkInfo.State.SUCCEEDED) {
          setSnack(it.outputData.getString(InjectedWorker.MESSAGE_KEY) ?: "no-message")
        }
      }
    }
    workManager.enqueue(request)
  }

  override fun doSetActivityAlarm() {
    val intent = Intent(application, AlarmActivity::class.java).putExtra("NAME", "Alarm")
    val pendingIntent =
      PendingIntent.getActivity(application, 1, intent, FLAG_ONE_SHOT or FLAG_IMMUTABLE)
    doSetAlarm(pendingIntent)
  }

  override fun doSetActivityWithBundleAlarm() {
    val intent = Intent(application, AlarmActivity::class.java).putExtra("NAME", "Alarm-Bundle")
    val pendingIntent =
      PendingIntent.getActivity(application, 1, intent, FLAG_ONE_SHOT or FLAG_IMMUTABLE, Bundle())
    doSetAlarm(pendingIntent)
  }

  override fun doSetActivitiesAlarm() {
    val intent1 = Intent(application, AlarmActivity::class.java).putExtra("NAME", "Alarm1")
    val intent2 = Intent(application, AlarmActivity::class.java).putExtra("NAME", "Alarm2")
    val pendingIntent =
      PendingIntent.getActivities(
        application,
        1,
        arrayOf(intent1, intent2),
        FLAG_ONE_SHOT or FLAG_IMMUTABLE,
      )
    doSetAlarm(pendingIntent)
  }

  override fun doSetActivitiesWithBundleAlarm() {
    val intent1 = Intent(application, AlarmActivity::class.java).putExtra("NAME", "Alarm1-Bundle")
    val intent2 = Intent(application, AlarmActivity::class.java).putExtra("NAME", "Alarm2-Bundle")
    val pendingIntent =
      PendingIntent.getActivities(
        application,
        1,
        arrayOf(intent1, intent2),
        FLAG_ONE_SHOT or FLAG_IMMUTABLE,
      )
    doSetAlarm(pendingIntent)
  }

  override fun doSetServiceAlarm() {
    val intent = Intent(application, AlarmService::class.java)
    val pendingIntent = PendingIntent.getService(application, 1, intent, FLAG_IMMUTABLE)
    doSetAlarm(pendingIntent)
  }

  override fun doSetForegroundServiceAlarm() {
    val intent = Intent(application, AlarmService::class.java).putExtra("FOREGROUND", true)
    val pendingIntent = PendingIntent.getForegroundService(application, 1, intent, FLAG_IMMUTABLE)
    doSetAlarm(pendingIntent)
  }

  override fun doSetBroadcastAlarm() {
    val intent =
      Intent(application, AlarmReceiver::class.java)
        .setAction("Alarm")
        .setDataAndType("http://google.com".toUri(), "Some type")
        .addCategory("category1")
        .addCategory("category2")
        .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        .putExtra("INT_EXTRA", 1)
        .putExtra("STRING_EXTRA", "Foo")
        .putExtra(
          "BUNDLE_EXTRA",
          Bundle().apply {
            putInt("INNER_INT_EXTRA", 1)
            putString("INNER_STRING_EXTRA", "Foo")
          },
        )
    val pendingIntent =
      PendingIntent.getBroadcast(
        application,
        1,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE,
      )
    doSetAlarm(pendingIntent)
  }

  private fun doSetAlarm(pendingIntent: PendingIntent) {
    val alarmManager = application.getSystemService<AlarmManager>() ?: throw IllegalStateException()
    AlarmManagerCompat.setExactAndAllowWhileIdle(
      alarmManager,
      AlarmManager.RTC,
      System.currentTimeMillis() + 3.seconds.inWholeMilliseconds,
      pendingIntent,
    )
  }

  override fun doAcquireWakeLock() {
    wakeLock.acquire(5000)
  }

  override fun doReleaseWakeLock() {
    wakeLock.release()
  }
}

private fun JobInfo.Builder.safeSetRequiresBatteryNotLow(value: Boolean): JobInfo.Builder {
  setRequiresBatteryNotLow(value)
  return this
}
