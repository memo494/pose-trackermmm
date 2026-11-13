package com.memo494.posetracker.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.memo494.posetracker.R

/** The ongoing notification of [CaptureService]: it keeps the service in the foreground and carries a Stop action. */
internal object CaptureNotification {
    /** Id of the foreground notification. */
    const val NOTIFICATION_ID = 1

    private const val CHANNEL_ID = "capture"
    private const val STOP_REQUEST_CODE = 1

    /** Creates the low-importance channel; the platform ignores it when the channel already exists. */
    fun ensureChannel(context: Context) {
        val builder = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
        builder.setName(context.getString(R.string.capture_channel_name))
        NotificationManagerCompat.from(context).createNotificationChannel(builder.build())
    }

    /** Builds the notification; its Stop action sends [CaptureService.stopIntent] to the service. */
    fun build(context: Context): Notification {
        val stopPendingIntent =
            PendingIntent.getService(
                context,
                STOP_REQUEST_CODE,
                CaptureService.stopIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopLabel = context.getString(R.string.capture_notification_stop)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
        builder.setSmallIcon(R.drawable.ic_stat_capture)
        builder.setContentTitle(context.getString(R.string.capture_notification_title))
        builder.setContentText(context.getString(R.string.capture_notification_text))
        builder.setOngoing(true)
        builder.setCategory(NotificationCompat.CATEGORY_SERVICE)
        builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        builder.addAction(R.drawable.ic_stat_capture, stopLabel, stopPendingIntent)
        return builder.build()
    }
}
