package com.openmonitor.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat

class BaseusProxyService : Service() {
    private var server: BaseusProxyServer? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            else -> startCapture()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    private fun startCapture() {
        if (server != null) {
            updateState(true, "Proxy capture already running on 127.0.0.1:${BridgeConfig.PROXY_PORT}")
            startForeground(NOTIFICATION_ID, buildNotification())
            return
        }
        server = BaseusProxyServer()
        updateState(true, "Starting proxy capture on 127.0.0.1:${BridgeConfig.PROXY_PORT}")
        startForeground(NOTIFICATION_ID, buildNotification())
        server?.start { message ->
            updateState(true, message)
            BridgeLogStore.info(message)
        }
    }

    private fun stopCapture() {
        server?.stop()
        server = null
        updateState(false, "Proxy capture stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateState(running: Boolean, message: String) {
        ProxyCaptureStateStore.update {
            it.copy(running = running, port = BridgeConfig.PROXY_PORT, message = message)
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = stopIntent(this)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or pendingIntentImmutableFlag(),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("OpenMonitor proxy capture")
            .setContentText("Listening on 127.0.0.1:${BridgeConfig.PROXY_PORT}")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "OpenMonitor proxy capture",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun pendingIntentImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE
        } else {
            0
        }
    }

    companion object {
        private const val CHANNEL_ID = "openmonitor_proxy_capture"
        private const val NOTIFICATION_ID = 2002
        private const val ACTION_STOP = "com.openmonitor.bridge.action.STOP_PROXY_CAPTURE"

        fun startIntent(context: Context): Intent {
            return Intent(context, BaseusProxyService::class.java)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, BaseusProxyService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
