package net.odiak.medaka.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import net.odiak.medaka.ListeningService
import net.odiak.medaka.presentation.MainActivity

class MedakaComplicationService : ComplicationDataSourceService() {
    override fun onComplicationRequest(
        request: ComplicationRequest,
        listener: ComplicationRequestListener
    ) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val data = ListeningService.lastData.value

        val text = data?.lastSG?.replace("mg/dL", "")?.replace("over ", ">") ?: ""

        listener.onComplicationData(
            ShortTextComplicationData.Builder(
                PlainComplicationText.Builder(text).build(),
                PlainComplicationText.Builder("").build()
            ).setTapAction(pendingIntent).build()
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        return when (type) {
            ComplicationType.SHORT_TEXT -> {
                ShortTextComplicationData.Builder(
                    PlainComplicationText.Builder("100↑").build(),
                    PlainComplicationText.Builder("").build()
                ).build()
            }

            else -> null
        }
    }
}