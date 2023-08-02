package net.odiak.medaka

import android.content.Context
import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import net.odiak.medaka.common.DataForWear
import net.odiak.medaka.tile.MainTileService
import java.io.File

class ListeningService : WearableListenerService() {
    companion object {
        val lastData = MutableStateFlow<DataForWear?>(null)
        private const val dataFile = "data.json"

        fun checkData(context: Context) {
            if (lastData.value != null) return
            
            val file = File(context.filesDir, dataFile)
            if (!file.exists()) return

            try {
                val text = file.readText()
                val data = Moshi.Builder()
                    .add(KotlinJsonAdapterFactory())
                    .build()
                    .adapter(DataForWear::class.java)
                    .fromJson(text)
                lastData.update { data }
            } catch (_: Exception) {
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        if (messageEvent.path == "/data") {
            val rawData = messageEvent.data
            val data = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
                .adapter(DataForWear::class.java)
                .fromJson(String(rawData))
            lastData.update { data }

            TileService.getUpdater(applicationContext).requestUpdate(MainTileService::class.java)

            val file = File(applicationContext.filesDir, dataFile)
            file.writeBytes(rawData)
        }
    }
}