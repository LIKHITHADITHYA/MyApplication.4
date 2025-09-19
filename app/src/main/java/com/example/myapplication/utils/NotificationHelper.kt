package com.example.myapplication.utils

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.myapplication.R // Assuming you have a default R file

class NotificationHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "proximity_alert_channel"
        private const val CHANNEL_NAME = "Proximity Alerts"
        private const val CHANNEL_DESCRIPTION = "Notifications for vehicle proximity alerts"
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = CHANNEL_DESCRIPTION
                // Configure other channel properties here if needed (e.g., lights, vibration)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun sendNotification(notificationId: Int, title: String, message: String) {
        // Consider adding an icon, pending intent, etc.
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with a suitable icon
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true) // Dismiss notification on tap

        // TODO: Add a PendingIntent if you want the notification to navigate somewhere in your app

        notificationManager.notify(notificationId, builder.build())
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    fun cancelAllNotifications() {
        notificationManager.cancelAll()
    }
}
