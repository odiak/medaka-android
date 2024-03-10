package net.odiak.medaka

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat

object NotificationConfig {
    const val CHANNEL_ID = "main"
    const val CHANNEL_NAME = "Main"

    enum class Types(val id: Int) {
        SensorData(1),
        SessionExpiration(2),
        ServiceRunning(3),
    }
}

fun Context.getNotificationManagerCompat(checkPermission: Boolean = true): NotificationManagerCompat? {
    if (checkPermission && ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED
    ) {
        return null
    }

    val manager = NotificationManagerCompat.from(this)
    if (manager.getNotificationChannel(NotificationConfig.CHANNEL_ID) == null) {
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationConfig.CHANNEL_ID,
                NotificationConfig.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    return manager
}