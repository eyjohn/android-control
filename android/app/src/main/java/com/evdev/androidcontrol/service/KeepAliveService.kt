package com.evdev.androidcontrol.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.evdev.androidcontrol.MainActivity
import com.evdev.androidcontrol.R
import com.evdev.androidcontrol.session.SessionManager

class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID = "android_control_service_channel"
        const val NOTIF_ID = 2001

        const val ACTION_DISCONNECT = "com.evdev.androidcontrol.action.DISCONNECT"
        const val ACTION_DISABLE_CONTROL = "com.evdev.androidcontrol.action.DISABLE_CONTROL"

        fun startIfNeeded(context: Context) {
            val prefs = context.getSharedPreferences("prefs_control", Context.MODE_PRIVATE)
            val enabled = prefs.getBoolean("connections_enabled", true)

            if (enabled) {
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

        fun updateNotification(context: Context, isConnected: Boolean, mode: String = "") {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIF_ID, buildNotification(context, isConnected, mode))
        }

        fun stopIfRunning(context: Context) {
            val intent = Intent(context, KeepAliveService::class.java)
            context.stopService(intent)
        }

        fun buildNotification(context: Context, isConnected: Boolean, mode: String = ""): Notification {
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                0,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val disconnectIntent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            val disconnectPending = PendingIntent.getService(
                context,
                1,
                disconnectIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val disableIntent = Intent(context, KeepAliveService::class.java).apply {
                action = ACTION_DISABLE_CONTROL
            }
            val disablePending = PendingIntent.getService(
                context,
                2,
                disableIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val title = if (isConnected) "Remote Control Active" else "DroidCtl Standby"
            val text = if (isConnected) {
                val modeLabel = if (mode.isNotEmpty()) " ($mode)" else ""
                "Connected via WebRTC$modeLabel"
            } else {
                "Listening for remote requests"
            }

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_stat_remote_control)
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setShowWhen(false)

            if (isConnected) {
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Disconnect",
                    disconnectPending
                )
            }

            builder.addAction(
                android.R.drawable.ic_lock_power_off,
                "Stop Service",
                disablePending
            )

            return builder.build()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        try {
            val isConnected = SessionManager.getInstance(applicationContext).isConnected.value
            val mode = SessionManager.getInstance(applicationContext).controlMode.value.name
            val notification = buildNotification(this, isConnected, mode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
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
        when (intent?.action) {
            ACTION_DISCONNECT -> {
                SessionManager.getInstance(applicationContext).disconnectSession(clearApproval = true)
                updateNotification(this, isConnected = false)
            }
            ACTION_DISABLE_CONTROL -> {
                SessionManager.getInstance(applicationContext).setConnectionsEnabled(false)
                stopSelf()
                return START_NOT_STICKY
            }
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
                description = "Status bar and controls for active WebRTC remote sessions"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }
}
