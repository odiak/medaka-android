package net.odiak.medaka

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.time.Duration.Companion.seconds

object DataFetcher {
    private const val CACHE_FILE = "data.json"
    private const val TOKEN_FILE = "token"
    val lastData = MutableStateFlow<MinimedData?>(null)
    private val lastDataTimestamp = MutableStateFlow<Long?>(null)
    val status = MutableStateFlow(Status.IDLE)

    private var token: String? = null
    private var tokenValidTo: Long? = null
    private var isTokenRestored = false

    val COOKIE_DATETIME_FORMAT =
        DateTimeFormatter.ofPattern("EEE MMM dd HH:mm:ss zzz yyyy", Locale.ENGLISH)!!

    enum class Status {
        IDLE, FETCHING, SUCCESS, ERROR, SESSION_EXPIRED
    }

    sealed class Result {
        data class Success(val timeToNextUpdate: Long) : Result()
        object Error : Result()
        object ErrorAndExit : Result()
    }

    fun checkCache(context: Context) {
        if (lastData.value != null) {
            return
        }

        val cache = File(context.filesDir, CACHE_FILE)
        if (!cache.exists()) return

        val data = try {
            cache.readText().parseJson<MinimedData>()
        } catch (e: Throwable) {
            Log.e("DataFetcher", "Failed to parse cache", e)
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


    fun setToken(context: Context, token: String, tokenValidToStr: String): Boolean {
        val tokenValidTo = tokenValidToStr.parseTokenValidTo()

        if (tokenValidTo < System.currentTimeMillis()) {
            return false
        }

        this.token = token
        this.tokenValidTo = tokenValidTo

        val validToStr = tokenValidTo.epochMilliToFormattedDateTime()
        context.logger.log("Token updated, valid to $validToStr")

        val file = File(context.filesDir, TOKEN_FILE)
        file.writeText("$token\n$tokenValidTo")

        return true
    }

    private fun clearToken(context: Context) {
        token = null
        tokenValidTo = null

        File(context.filesDir, TOKEN_FILE).writeText("")
    }

    private fun restoreToken(context: Context) {
        if (isTokenRestored) return
        isTokenRestored = true

        try {
            val file = File(context.filesDir, TOKEN_FILE)
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
            Log.e("DataFetcher", "Failed to restore token", e)
        }
    }

    suspend fun fetchDataAndNotify(context: Context): Result {
        val logger = context.logger

        restoreToken(context)

        if (status.value == Status.FETCHING) {
            return Result.Error
        }
        status.value = Status.FETCHING

        val data: MinimedData?

        try {
            val settings = context.settingsDataStore.data.first()
            if (!settings.isValid()) {
                status.value = Status.ERROR
                return Result.ErrorAndExit
            }

            reauthIfNeeded(context)

            val token = token
            if (token == null) {
                status.value = Status.SESSION_EXPIRED
                return Result.ErrorAndExit
            }

            data = fetchData(context, settings.username, token)

            if (data == null) {
                return if (this.token == null) {
                    status.value = Status.SESSION_EXPIRED
                    Result.ErrorAndExit
                } else {
                    status.value = Status.ERROR
                    Result.Error
                }
            }

            lastData.update { data }
            lastDataTimestamp.update { System.currentTimeMillis() }

            val lastSensorDateTime = data.lastSG?.datetime?.let {
                LocalDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(it))
            }
            logger.log("latest sensor time: $lastSensorDateTime")

            sendDataToWearDevice(context, data)
            notify(context, data)
        } catch (e: Throwable) {
            Log.e("DataFetcher", "Error", e)
            status.value = Status.ERROR
            return Result.Error
        }

        val lastSensorDateTime = data.lastSGDateTime
        val delay = if (lastSensorDateTime == null) 60 else {
            val now = LocalDateTime.now()
            val target = lastSensorDateTime.plusSeconds(60 * 5 + 30)
            Duration.between(now, target).seconds.coerceAtLeast(30)
        }

        status.value = Status.SUCCESS
        return Result.Success(delay)
    }

    private suspend fun fetchData(
        context: Context,
        username: String,
        token: String,
    ): MinimedData? {
        val logger = context.logger
        val client = OkHttpClient.Builder()
            .readTimeout(Duration.ofMinutes(3))
            .build()

        val payload = mapOf(
            "patientId" to username,
            "role" to "patient",
            "username" to username
        )

        logger.log("Start fetching data")

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
                    val file = File(context.filesDir, CACHE_FILE)
                    file.writeBytes(bytes)

                    val data = bytes.toString(Charset.defaultCharset()).parseJson<MinimedData>()

                    logger.log("Data fetched")

                    return data
                }

                res.body?.close()

                if (res.code == 401) {
                    clearToken(context)
                    notifySessionExpiration(context)

                    logger.log("Got 401 Unauthorized, session expired")

                    return null
                }
            } catch (e: Throwable) {
                Log.e("DataFetcher", "Failed to fetch data", e)
                logger.log("Failed to fetch data: ${e.message}")
            }

            delay(30.seconds)
            logger.log("Retrying to fetch data")
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
            lastSG = data.lastSGString + data.lastSGTrendString,
            lastSGDiff = data.lastSGDiffString,
            lastSGTime = data.lastSGDateTime?.format(DateTimeFormatter.ofPattern("HH:mm")),
        )

        val adapter =
            Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(DataForWear::class.java)
        val buffer = adapter.toJson(dataForWear).toByteArray()

        Wearable.getMessageClient(context).sendMessage(id, "/data", buffer)
    }

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, data: MinimedData) {
        val manager = context.getNotificationManagerCompat() ?: return

        manager.cancel(NotificationConfig.Types.SessionExpiration.id)
        manager.cancel(NotificationConfig.Types.ServiceRunning.id)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val sg = data.lastSGString
        val time = data.lastSGDateTime
            ?.format(DateTimeFormatter.ofPattern("HH:mm"))
        val diff = data.lastSGDiffString
        val trend = data.lastSGTrendString.let {
            if (it.isEmpty()) "" else " $it"
        }

        val notification = NotificationCompat.Builder(context, NotificationConfig.CHANNEL_ID)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentText("SG: $sg$trend $diff\nat $time")
            .setContentIntent(pendingIntent)
            .setSmallIcon(R.drawable.ic_notification)
            .setSilent(true)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        try {
            manager.notify(NotificationConfig.Types.SensorData.id, notification)
        } catch (e: Throwable) {
            Log.e("DataFetcher", "Failed to notify", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun notifySessionExpiration(context: Context) {
        val manager = context.getNotificationManagerCompat() ?: return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent: PendingIntent =
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, NotificationConfig.CHANNEL_ID)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentText("Session expired")
            .setSmallIcon(R.drawable.ic_notification)
            .build()

        try {
            manager.notify(2, notification)
        } catch (e: Throwable) {
            Log.e("DataFetcher", "Failed to notify", e)
        }
    }

    fun forceReauth(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            reauthIfNeeded(context, true)
        }
    }

    private suspend fun reauthIfNeeded(context: Context, force: Boolean = false) {
        val logger = context.logger

        val token = token ?: return
        val tokenValidTo = tokenValidTo ?: return

        val now = System.currentTimeMillis()
        if (!force && now + 10 * 60 * 1000 < tokenValidTo) {
            return
        }

        if (now > tokenValidTo) {
            clearToken(context)
            notifySessionExpiration(context)

            logger.log("Session expired")

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
            logger.log("Cookie from reauth: $value")
            val (k, v) = value.split(";", limit = 2)[0]
                .split("=", limit = 2).map(String::trim)
            when (k) {
                "auth_tmp_token" -> newToken = v
                "c_token_valid_to" -> newTokenValidToStr = v

            }
        }

        if (newToken != null && newTokenValidToStr != null) {
            logger.log("Reauth succeeded")
            setToken(context, newToken, newTokenValidToStr)
        } else {
            logger.log("Reauth failed")
        }

        res.body?.close()
    }

    fun resetAllData(context: Context) {
        lastData.update { null }
        lastDataTimestamp.update { null }
        clearToken(context)

        val file = File(context.filesDir, CACHE_FILE)
        file.delete()
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

private inline fun <reified T> String.parseJson(): T {
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val adapter = moshi.adapter(T::class.java)
    return adapter.fromJson(this)!!
}

@OptIn(ExperimentalStdlibApi::class)
private fun Request.Builder.postJsonMap(obj: Map<String, Any?>): Request.Builder {
    val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    val adapter = moshi.adapter<Map<String, Any?>>()
    return post(JsonRequestBody(adapter, obj))
}

private fun String.parseTokenValidTo(): Long =
    LocalDateTime.parse(this, DataFetcher.COOKIE_DATETIME_FORMAT)
        .toEpochSecond(ZoneOffset.UTC) * 1000

private fun Long.epochMilliToFormattedDateTime(): String =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).format(
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    )