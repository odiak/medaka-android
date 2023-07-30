package net.odiak.medaka

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo.State.RUNNING
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.flow.first
import java.time.Duration

class PeriodicWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        private const val workName = "periodicWorker"

        fun enqueue(workManager: WorkManager) {
            workManager.enqueueUniquePeriodicWork(
                workName,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PeriodicWorker>(Duration.ofMinutes(15)).build()
            )
        }
    }

    override suspend fun doWork(): Result {
        Worker.checkCache(applicationContext)

        val settings = applicationContext.settingsDataStore.data.first()
        if (settings.password.isEmpty()) return Result.success()

        val workManager = WorkManager.getInstance(applicationContext)
        val work = workManager.getWorkInfosForUniqueWork(Worker.workerName).await().firstOrNull()
        val timeSinceLastData = Worker.timeSinceLastData ?: 0

        if (work == null || (work.state != RUNNING && timeSinceLastData > 5 * 60 * 1000)) {
            Worker.enqueue(workManager)
        }
        return Result.success()
    }

}