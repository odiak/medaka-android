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
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.Wearable
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import net.odiak.medaka.common.DataForWear
import net.odiak.medaka.common.MinimedData
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.IOException
import java.io.File
import java.nio.charset.Charset
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val cacheFile = "data.json"
        const val workerName = "worker"
        private const val workerNamePeriodic = "worker-periodic"
        val lastData = MutableStateFlow<MinimedData?>(null)
        val lastDataTimestamp = MutableStateFlow<Long?>(null)

        fun enqueue(workManager: WorkManager, delaySecs: Long = 0) {
            workManager.enqueueUniqueWork(
                workerName,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<Worker>()
                    .apply {
                        if (delaySecs > 0) {
                            setInitialDelay(delaySecs, TimeUnit.SECONDS)
                        }
                    }
                    .build()
            )
        }

        fun enqueuePeriodically(workManager: WorkManager) {
            workManager.enqueueUniquePeriodicWork(
                workerNamePeriodic,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<Worker>(Duration.ofMinutes(15))
                    .setInputData(
                        Data.Builder().putBoolean("periodic", true).build()
                    )
                    .build()
            )
        }

        fun checkCache(context: Context) {
            if (lastData.value != null) {
                return
            }

            val cache = File(context.filesDir, cacheFile)
            if (cache.exists()) {
                val data = cache.readText().parseJson<MinimedData>()
                lastData.update { data }
                CoroutineScope(Dispatchers.IO).launch {
                    notify(context, data)
                    sendDataToWearDevice(context, data)
                }

            }
        }

        val timeSinceLastData: Long?
            get() {
                val lastDataTimestamp = lastDataTimestamp.value ?: return null
                return System.currentTimeMillis() - lastDataTimestamp
            }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val workManager = WorkManager.getInstance(context)

        val isPeriodic = inputData.getBoolean("periodic", false)
        if (isPeriodic && !shouldContinueOnPeriodicWork(workManager)) {
            return Result.success()
        }

        var data: MinimedData? = null
        try {
            val settings = context.settingsDataStore.data.first()
            if (settings.password.isEmpty()) {
                return Result.success()
            }

            data = fetchData(context, settings.password)
            if (data != null) {
                lastData.update { data }
                lastDataTimestamp.update { System.currentTimeMillis() }

                val lastSensorDateTime = data.lastSG?.datetime?.let {
                    LocalDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(it))
                }
                println("Data fetched. latest sensor time: $lastSensorDateTime")

                sendDataToWearDevice(context, data)
                notify(context, data)
            }
        } catch (e: Throwable) {
            Log.e("Worker", "Error", e)
        }

        val lastSensorDateTime = data?.lastSGDateTime
        val delay = if (lastSensorDateTime == null) 60 else {
            val now = LocalDateTime.now()
            val target = lastSensorDateTime.plusSeconds(60 * 5 + 30)
            Duration.between(now, target).seconds.coerceAtLeast(30)
        }

        enqueue(workManager, delay)

        return Result.success()
    }

    private suspend fun shouldContinueOnPeriodicWork(workManager: WorkManager): Boolean {
        val work = workManager.getWorkInfosForUniqueWork(workerName).await().firstOrNull()
            ?: return true
        val timeSinceLastData = Worker.timeSinceLastData ?: 0

        if (work.state == WorkInfo.State.RUNNING || timeSinceLastData < 5 * 60 * 1000) {
            return false
        }

        workManager.cancelWorkById(work.id).await()
        return true
    }
}

private suspend fun fetchData(context: Context, password: String): MinimedData? {
    val client = OkHttpClient.Builder()
        .readTimeout(Duration.ofMinutes(3))
        .build()

    for (i in 1..4) {
        try {
            val res = client.newCall(
                Request.Builder().url("https://minimed-fetcher.odiak.workers.dev/fetch")
                    .post(FormBody.Builder().build())
                    .header("Authorization", "Bearer $password").build()
            ).executeSuspend()

            if (res.isSuccessful) {
                val bytes = res.body!!.bytes()
                val file = File(context.filesDir, Worker.cacheFile)
                file.writeBytes(bytes)

                return bytes.toString(Charset.defaultCharset()).parseJson()
            }
        } catch (e: Throwable) {
            Log.e("Worker", "Failed to fetch data", e)
        }

        delay(30.seconds)
    }

    return null
}

private fun sendDataToWearDevice(context: Context, data: MinimedData) {
    val capabilityInfo = Tasks.await(
        Wearable.getCapabilityClient(context)
            .getCapability("medaka-wear", CapabilityClient.FILTER_REACHABLE)
    )
    val id = capabilityInfo.nodes.minByOrNull { if (it.isNearby) 0 else 1 }?.id ?: return

    val dataForWear = DataForWear(
        lastSG = data.lastSGString,
        lastSGDiff = data.lastSGDiffString,
        lastSGTime = data.lastSGDateTime?.format(DateTimeFormatter.ofPattern("HH:mm")),
    )

    val adapter =
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(DataForWear::class.java)
    val buffer = adapter.toJson(dataForWear).toByteArray()

    Wearable.getMessageClient(context).sendMessage(id, "/data", buffer)
}

private fun notify(context: Context, data: MinimedData) {
    if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val manager = NotificationManagerCompat.from(context)
    if (manager.getNotificationChannel("main") == null) {
        manager.createNotificationChannel(
            NotificationChannel(
                "main",
                "Main",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    val intent = Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
    }
    val pendingIntent: PendingIntent =
        PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

    val sg = data.lastSGString
    val time = data.lastSGDateTime
        ?.format(DateTimeFormatter.ofPattern("HH:mm"))
    val diff = data.lastSGDiffString

    val notification = NotificationCompat.Builder(context, "main")
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setContentText("SG: $sg $diff\nat $time")
        .setContentIntent(pendingIntent)
        .setSmallIcon(R.drawable.ic_notification)
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

inline fun <reified T> String.parseJson(): T {
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val adapter = moshi.adapter(T::class.java)
    return adapter.fromJson(this)!!
}