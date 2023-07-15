package net.odiak.medaka

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import net.odiak.medaka.common.MinimedData
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val workerName = "worker"
        const val tag = "worker"
        var lastData = MutableStateFlow<MinimedData?>(null)

        fun enqueue(workManager: WorkManager, delaySecs: Long = 0) {
            val builder = OneTimeWorkRequestBuilder<Worker>().addTag(tag)
            if (delaySecs > 0) {
                builder.setInitialDelay(delaySecs, TimeUnit.SECONDS)
            }
            workManager.enqueueUniqueWork(
                workerName,
                ExistingWorkPolicy.REPLACE,
                builder.build()
            )
        }
    }

    override suspend fun doWork(): Result {
        val start = System.currentTimeMillis()

        val settings = applicationContext.settingsDataStore.data.first()
        if (settings.password.isEmpty()) {
            return Result.success()
        }

        val data = fetchData(settings.password)
        if (data != null) {
            lastData.update { data }

            val lastSensorDateTime = data.lastSG?.datetime?.let {
                LocalDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(it))
            }
            println("Data fetched. latest sensor time: $lastSensorDateTime")

            sendDataToWearDevice(data)
            notify(data)
        }

        val durationSec = (System.currentTimeMillis() - start) / 1000
        val delay = (5 * 60 - durationSec).coerceAtLeast(0)

        enqueue(WorkManager.getInstance(applicationContext), delay)

        return Result.success()
    }

    private suspend fun fetchData(password: String): MinimedData? {
        val client = OkHttpClient.Builder()
            .readTimeout(Duration.ofMinutes(3))
            .build()

        try {
            val res = client.newCall(
                Request.Builder().url("https://minimed-fetcher.odiak.workers.dev/fetch")
                    .post(FormBody.Builder().build())
                    .header("Authorization", "Bearer $password").build()
            ).executeSuspend()

            if (!res.isSuccessful) {
                return null
            }

            return res.bodyJson<MinimedData>()
        } catch (e: Throwable) {
            Log.e("Worker", "Failed to fetch data", e)
            return null
        }
    }

    private fun sendDataToWearDevice(data: MinimedData) {
        val context = applicationContext

        val capabilityInfo = Tasks.await(
            Wearable.getCapabilityClient(context)
                .getCapability("medaka-wear", CapabilityClient.FILTER_REACHABLE)
        )
        val id = capabilityInfo.nodes.minByOrNull { if (it.isNearby) 0 else 1 }?.id ?: return

        val adapter =
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(MinimedData::class.java)
        val buffer = adapter.toJson(data).toByteArray()

        Wearable.getMessageClient(context).sendMessage(id, "/data", buffer)
    }

    private fun notify(data: MinimedData) {
        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = NotificationManagerCompat.from(applicationContext)
        if (manager.getNotificationChannel("main") == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    "main",
                    "Main",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val sg = data.lastSGString
        val time = data.lastSGDateTime
            ?.format(DateTimeFormatter.ofPattern("HH:mm"))
        val diff = data.lastSGDiffString

        val notification = NotificationCompat.Builder(applicationContext, "main")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentText("SG: ${sg}mg/dL ${diff}\nat $time")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()


        try {
            manager.notify(0, notification)
        } catch (e: Throwable) {
            Log.e("Worker", "Failed to notify", e)
        }
    }
}

suspend fun Call.executeSuspend(): Response {
    return suspendCoroutine { continuation ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response)
            }
        })
    }
}

inline fun <reified T> Response.bodyJson(): T {
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val adapter = moshi.adapter(T::class.java)
    return adapter.fromJson(body!!.source())!!
}