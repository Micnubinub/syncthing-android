package com.micnubinub.syncthing.util

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.micnubinub.syncthing.service.RunConditionMonitor
import com.micnubinub.syncthing.service.SyncTriggerWorker
import java.util.concurrent.TimeUnit

object SyncScheduler {
    private const val TAG = "SyncScheduler"
    private const val UNIQUE_WORK_NAME = "sync_trigger"

    @JvmStatic
    fun scheduleSyncTriggerServiceJob(context: Context, delayInSeconds: Int, startRun: Boolean) {
        val delayMs = maxOf(0L, delayInSeconds * 1000L)
        val workRequest = OneTimeWorkRequestBuilder<SyncTriggerWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(
                workDataOf(
                    RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW to startRun
                )
            )
            .addTag(UNIQUE_WORK_NAME)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        Log.i(
            TAG, "Scheduled SyncTriggerWorker to run in " + delayInSeconds.toString() +
                    " seconds."
        )
    }

    @JvmStatic
    fun cancelAllScheduledJobs(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(UNIQUE_WORK_NAME)
    }
}
