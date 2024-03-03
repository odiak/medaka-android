package net.odiak.medaka

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DataFetchService : Service() {

    companion object {
        const val EXTRA_FORCE = "force"

        fun start(context: Context, force: Boolean = false) {
            context.startForegroundService(Intent(context, DataFetchService::class.java).apply {
                putExtra(EXTRA_FORCE, force)
            })
        }
    }

    private var currentJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, "main")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentText("fetching data")
            .setSmallIcon(R.drawable.ic_notification)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
        startForeground(1, notification)

        startJob(intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false)

        return START_STICKY
    }

    private fun startJob(force: Boolean = false) {
        currentJob?.let {
            if (it.isActive) {
                if (force) {
                    it.cancel()
                } else {
                    return
                }
            }
        }

        currentJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                when (val result = DataFetcher.fetchDataAndNotify(this@DataFetchService)) {
                    is DataFetcher.Result.ErrorAndExit -> {
                        stopSelf()
                        break
                    }

                    is DataFetcher.Result.Success -> {
                        delay(result.timeToNextUpdate * 1000)
                    }

                    is DataFetcher.Result.Error -> {
                        delay(60 * 1000)
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onDestroy() {
        super.onDestroy()

        currentJob?.let {
            if (it.isActive) {
                it.cancel()
            }
        }
    }
}