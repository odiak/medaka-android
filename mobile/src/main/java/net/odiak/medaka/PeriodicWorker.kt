package net.odiak.medaka

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.flow.first

class PeriodicWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        const val workName = "periodicWorker"
    }

    override suspend fun doWork(): Result {
        val settings = applicationContext.settingsDataStore.data.first()
        if (settings.password.isEmpty()) return Result.success()

        val workManager = WorkManager.getInstance(applicationContext)
        val works = workManager.getWorkInfosByTag(Worker.tag).await()

        if (works.isEmpty()) {
            Worker.enqueue(workManager)
        }
        return Result.success()
    }

}