package net.odiak.medaka

import androidx.wear.tiles.TileService
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import net.odiak.medaka.common.MinimedData
import net.odiak.medaka.tile.MainTileService

class ListeningService : WearableListenerService() {
    companion object {
        val lastData = MutableStateFlow<MinimedData?>(null)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        super.onMessageReceived(messageEvent)

        if (messageEvent.path == "/data") {
            val data = Moshi.Builder()
                .add(KotlinJsonAdapterFactory())
                .build()
                .adapter(MinimedData::class.java)
                .fromJson(String(messageEvent.data))
            lastData.update { data }

            TileService.getUpdater(applicationContext).requestUpdate(MainTileService::class.java)
        }
    }
}