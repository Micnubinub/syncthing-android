package com.micnubinub.syncthing.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class SyncTriggerWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val beginActive = inputData.getBoolean(
            RunConditionMonitor.EXTRA_BEGIN_ACTIVE_TIME_WINDOW, false
        )

        RunConditionBus.tryEmit(
            RunConditionEvent.SyncTriggerFired(beginActive)
        )

        return Result.success()
    }
}
