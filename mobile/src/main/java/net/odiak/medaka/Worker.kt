package net.odiak.medaka

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
    }

    override suspend fun doWork(): Result {
        val start = System.currentTimeMillis()

        val settings = applicationContext.settingsDataStore.data.first()
        if (settings.password.isEmpty()) {
            return Result.failure()
        }

        val client = OkHttpClient.Builder()
            .readTimeout(Duration.ofMinutes(3))
            .build()

        val data = client.newCall(
            Request.Builder().url("https://minimed-fetcher.odiak.workers.dev/fetch")
                .post(FormBody.Builder().build())
                .header("Authorization", "Bearer ${settings.password}").build()
        ).executeSuspend().bodyJson<MinimedData>()
        lastData.update { data }

        val lastSensorDateTime = data.lastSG?.datetime?.let {
            LocalDateTime.from(DateTimeFormatter.ISO_DATE_TIME.parse(it))
        }
        println("Data fetched. latest sensor time: $lastSensorDateTime")

        val durationSec = (System.currentTimeMillis() - start) / 1000
        val delay = (5 * 60 - durationSec).coerceAtLeast(0)

        WorkManager.getInstance(applicationContext)
            .enqueueUniqueWork(
                workerName,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<Worker>().setInitialDelay(delay, TimeUnit.SECONDS)
                    .addTag(tag).build()
            )

        return Result.success()
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