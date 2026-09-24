package com.example.aiwebtabautomator

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class InboxPollWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!AppPrefs.isFileBridgeEnabled(applicationContext)) return Result.success()
        FileBridge.consumeInbox(applicationContext) { command ->
            AutomationBus.emit(command)
        }
        return Result.success()
    }
}
