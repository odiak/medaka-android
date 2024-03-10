package net.odiak.medaka

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
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
        // ensure notification channel is created
        getNotificationManagerCompat(false)

        val notification = NotificationCompat.Builder(this, NotificationConfig.CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentText("service running...")
            .setSmallIcon(R.drawable.ic_notification)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .setTimeoutAfter(5000)
            .build()
        startForeground(NotificationConfig.Types.ServiceRunning.id, notification)

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
                val result = try {
                    DataFetcher.fetchDataAndNotify(this@DataFetchService)
                } catch (e: Exception) {
                    Log.e("DataFetchService", "Error in fetchDataAndNotify", e)
                    logger.log("Error in fetchDataAndNotify: ${e.message}")
                    continue
                }

                when (result) {
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

    override fun onCreate() {
        super.onCreate()

        logger.log("Service started")
    }

    override fun onDestroy() {
        super.onDestroy()

        currentJob?.let {
            if (it.isActive) {
                it.cancel()
            }
        }

        logger.log("Service stopped")
    }
}