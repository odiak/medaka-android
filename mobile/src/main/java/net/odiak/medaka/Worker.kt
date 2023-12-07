package net.odiak.medaka

import android.Manifest
import android.annotation.SuppressLint
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
import com.squareup.moshi.adapter
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.IOException
import java.io.File
import java.nio.charset.Charset
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

class Worker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        const val cacheFile = "data.json"
        private const val tokenFile = "token"
        const val workerName = "worker"
        private const val workerNamePeriodic = "worker-periodic"
        val lastData = MutableStateFlow<MinimedData?>(null)
        val lastDataTimestamp = MutableStateFlow<Long?>(null)

        var token: String? = null
            private set
        var tokenValidTo: Long? = null
            private set
        private var isTokenRestored = false

        val COOKIE_DATETIME_FORMAT = DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy")!!

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
                val data = try {
                    cache.readText().parseJson<MinimedData>()
                } catch (e: Throwable) {
                    Log.e("Worker", "Failed to parse cache", e)
                    null
                }
                if (data != null) {
                    lastData.update { data }
                    CoroutineScope(Dispatchers.IO).launch {
                        notify(context, data)
                        sendDataToWearDevice(context, data)
                    }
                }
            }
        }

        val timeSinceLastData: Long?
            get() {
                val lastDataTimestamp = lastDataTimestamp.value ?: return null
                return System.currentTimeMillis() - lastDataTimestamp
            }


        fun setToken(context: Context?, token: String?, tokenValidTo: Long?) {
            this.token = token
            this.tokenValidTo = tokenValidTo

            if (context != null) {
                val file = File(context.filesDir, tokenFile)
                if (token != null && tokenValidTo != null) {
                    file.writeText("$token\n$tokenValidTo")
                } else {
                    file.writeText("")
                }
            }
        }

        private fun restoreToken(context: Context) {
            if (isTokenRestored) return
            isTokenRestored = true

            try {
                val file = File(context.filesDir, tokenFile)
                if (!file.exists()) return

                val lines = file.readLines()
                if (lines.size != 2) return

                val token = lines[0]
                val tokenValidTo = lines[1].toLongOrNull()

                if (token.isNotEmpty() && tokenValidTo != null) {
                    this.token = token
                    this.tokenValidTo = tokenValidTo
                }
            } catch (e: Throwable) {
                Log.e("Worker", "Failed to restore token", e)
            }
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext
        val workManager = WorkManager.getInstance(context)

        restoreToken(context)

        val isPeriodic = inputData.getBoolean("periodic", false)
        if (isPeriodic && !shouldContinueOnPeriodicWork(workManager)) {
            return Result.success()
        }

        var data: MinimedData? = null
        try {
            val settings = context.settingsDataStore.data.first()
            if (!settings.isValid()) {
                return Result.success()
            }

            reauthIfNeeded(context)

            data = fetchData(context, settings.username, token ?: return Result.failure())

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

private suspend fun fetchData(context: Context, username: String, token: String): MinimedData? {
    val client = OkHttpClient.Builder()
        .readTimeout(Duration.ofMinutes(3))
        .build()

    val payload = mapOf(
        "patientId" to username,
        "role" to "patient",
        "username" to username
    )

    for (i in 1..4) {
        try {
            val res = client.newCall(
                Request.Builder()
                    .url("https://clcloud.minimed.eu/connect/carepartner/v6/display/message")
                    .header("Authorization", "Bearer $token")
                    .postJsonMap(payload)
                    .build()
            ).executeSuspend()

            if (res.isSuccessful) {
                val bytes = res.body!!.bytes()
                val file = File(context.filesDir, Worker.cacheFile)
                file.writeBytes(bytes)

                return bytes.toString(Charset.defaultCharset()).parseJson()
            }

            res.body?.close()

            if (res.code == 401) {
                Worker.setToken(context, null, null)
                notifySessionExpiration(context)

                return null
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

private const val CHANNEL_ID = "main"

private fun notificationWrapper(context: Context, block: (NotificationManagerCompat) -> Unit) {
    if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }

    val manager = NotificationManagerCompat.from(context)
    if (manager.getNotificationChannel(CHANNEL_ID) == null) {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Main",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    block(manager)
}

@SuppressLint("MissingPermission")
private fun notify(context: Context, data: MinimedData) {
    notificationWrapper(context) { manager ->
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
}


@SuppressLint("MissingPermission")
private fun notifySessionExpiration(context: Context) {
    notificationWrapper(context) { manager ->
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentText("Session expired")
            .setSmallIcon(R.drawable.ic_notification)
            .build()

        try {
            manager.notify(1, notification)
        } catch (e: Throwable) {
            Log.e("Worker", "Failed to notify", e)
        }
    }
}

private suspend fun reauthIfNeeded(context: Context) {
    val token = Worker.token ?: return
    val tokenValidTo = Worker.tokenValidTo ?: return

    val now = System.currentTimeMillis()
    if (now + 10 * 60 * 1000 < tokenValidTo) {
        return
    }

    if (now > tokenValidTo) {
        Worker.setToken(context, null, null)
        notifySessionExpiration(context)
        return
    }

    val client = OkHttpClient.Builder()
        .readTimeout(Duration.ofMinutes(3))
        .build()

    val res = client.newCall(
        Request.Builder()
            .url("https://carelink.minimed.eu/patient/sso/reauth")
            .header("Authorization", "Bearer $token")
            .header("Cookie", "auth_tmp_token=$token")
            .post("".toRequestBody())
            .build()
    ).executeSuspend()

    var newToken: String? = null
    var newTokenValidToStr: String? = null
    for (value in res.headers.values("Set-Cookie")) {
        val (k, v) = value.split(";", limit = 2)[0]
            .split("=", limit = 2).map(String::trim)
        when (k) {
            "auth_tmp_token" -> newToken = v
            "c_token_valid_to" -> newTokenValidToStr = v

        }
    }

    if (newToken != null && newTokenValidToStr != null) {
        Worker.setToken(context, newToken, newTokenValidToStr.parseTokenValidTo())
    }

    res.body?.close()
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

@OptIn(ExperimentalStdlibApi::class)
fun Request.Builder.postJsonMap(obj: Map<String, Any?>): Request.Builder {
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val adapter = moshi.adapter<Map<String, Any?>>()
    return post(JsonRequestBody(adapter, obj))
}

fun String.parseTokenValidTo(): Long {
    return LocalDateTime
        .parse(this, Worker.COOKIE_DATETIME_FORMAT)
        .toEpochSecond(ZoneOffset.UTC) * 1000
}