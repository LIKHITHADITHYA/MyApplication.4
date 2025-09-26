package com.example.myapplication.utils

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.myapplication.R // Make sure R is correctly imported/generated

class NotificationHelper(private val context: Context) {

    companion object {
        private const val CHANNEL_ID = "proximity_alert_channel"
        private const val CHANNEL_NAME = "Proximity Alerts"
        private const val CHANNEL_DESCRIPTION = "Notifications for vehicle proximity alerts"
        private const val FLASHLIGHT_DURATION_MS = 1000L // Flashlight on for 1 second
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var cameraManager: CameraManager? = null
    private var cameraId: String? = null

    init {
        createNotificationChannel()
        if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            try {
                cameraManager?.let { cm ->
                    for (id in cm.cameraIdList) {
                        val characteristics = cm.getCameraCharacteristics(id)
                        val flashAvailable = characteristics.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE)
                        val lensFacing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
                        if (flashAvailable == true && lensFacing == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK) {
                            cameraId = id
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("NotificationHelper", "Could not get camera ID for flashlight", e)
                cameraId = null
                cameraManager = null // Ensure it's null if setup failed
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH // Ensures sound plays, notification pops up
            ).apply {
                description = CHANNEL_DESCRIPTION
                enableLights(true)
                enableVibration(true)
                // Set sound on channel if all notifications use the same sound and attributes
                // setSound(null, audioAttributes) // Clear default sound if setting per notification
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    // Existing simple notification method
    fun sendNotification(notificationId: Int, title: String, message: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon) // Ensure this icon exists
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        // TODO: Add a PendingIntent if you want the notification to navigate somewhere

        notificationManager.notify(notificationId, builder.build())
    }

    /**
     * Sends a notification with an optional custom sound and flashlight warning.
     *
     * @param notificationId Unique ID for the notification.
     * @param title Title of the notification.
     * @param message Body text of the notification.
     * @param soundResId Resource ID of the sound file in res/raw (e.g., R.raw.your_sound). Null for default sound.
     * @param enableFlashlight True to trigger a flashlight warning, false otherwise.
     *                         Requires CAMERA permission (declared in Manifest, runtime request might be needed).
     */
    fun sendAdvancedNotification(
        notificationId: Int,
        title: String,
        message: String,
        soundResId: Int?,
        enableFlashlight: Boolean
    ) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon) // Ensure this icon exists
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH) // Crucial for heads-up and sound
            .setAutoCancel(true)
            // .setDefaults(NotificationCompat.DEFAULT_VIBRATE or NotificationCompat.DEFAULT_LIGHTS) // Default vibration/lights unless overridden by channel

        if (soundResId != null) {
            val soundUri = Uri.parse("android.resource://${context.packageName}/$soundResId")
            builder.setSound(soundUri)
        } else {
            // Uses channel sound or default if not set on channel and not overridden here
        }
        // TODO: Add a PendingIntent if you want the notification to navigate somewhere

        notificationManager.notify(notificationId, builder.build())

        if (enableFlashlight && cameraId != null && cameraManager != null &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                cameraManager?.setTorchMode(cameraId!!, true)
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        cameraManager?.setTorchMode(cameraId!!, false)
                    } catch (e: Exception) {
                        android.util.Log.e("NotificationHelper", "Error turning off flashlight", e)
                    }
                }, FLASHLIGHT_DURATION_MS)
            } catch (e: Exception) {
                android.util.Log.e("NotificationHelper", "Error turning on flashlight", e)
            }
        }
    }

    fun cancelNotification(notificationId: Int) {
        notificationManager.cancel(notificationId)
    }

    fun cancelAllNotifications() {
        notificationManager.cancelAll()
        // Attempt to turn off flashlight if it was left on
        if (cameraId != null && cameraManager != null && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            try {
                cameraManager?.setTorchMode(cameraId!!, false)
            } catch (e: Exception) {
                android.util.Log.e("NotificationHelper", "Error turning off flashlight during cancelAll", e)
            }
        }
    }
}
