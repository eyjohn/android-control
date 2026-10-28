package com.evdev.androidcontrol.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.evdev.androidcontrol.R

class KeepAliveService : Service() {

    companion object {
        private const val CHANNEL_ID = "android_control_service_channel"
        private const val NOTIF_ID = 2001

        fun startIfNeeded(context: Context) {
            val a11yRunning = ControlAccessibilityService.isRunning
            val prefs = context.getSharedPreferences("prefs_control", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("connections_enabled", true)

            if (!a11yRunning && enabled) {
                val intent = Intent(context, KeepAliveService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("KeepAliveService", "startForegroundService suppressed: ${e.message}")
                }
            }
        }

        fun stopIfRunning(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                startForeground(NOTIF_ID, notification, fgsType)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, 0)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("KeepAliveService", "startForeground failed: ${e.message}")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // If a11y service started in the meantime, we can stop the foreground service
        if (ControlAccessibilityService.isRunning) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Remote Control Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps remote control standby active in ADB mode"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Android Control Active")
            .setContentText("Listening for incoming WebRTC requests")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
