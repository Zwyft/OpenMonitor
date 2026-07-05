package com.openmonitor.bridge

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

class BridgeService : Service() {
    private lateinit var httpServer: BridgeHttpServer
    private lateinit var rtspBridge: RtspHlsBridge

    override fun onCreate() {
        super.onCreate()
        BridgeLogStore.initialize(cacheDir)
        BridgeLogStore.info("Bridge service created")
        rtspBridge = RtspHlsBridge(this, cacheDir)
        httpServer = BridgeHttpServer(cacheDir)
        httpServer.start()
        startAsForeground("OpenMonitor Bridge ready")
        BridgeLogStore.info("HTTP server started at ${httpServer.baseUrl()}")
        BridgeStateStore.update {
            it.copy(
                serverUrl = httpServer.baseUrl(),
                message = "Ready to bridge RTSP cameras",
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val rtspUrl = intent.getStringExtra(EXTRA_RTSP_URL).orEmpty().trim()
                if (rtspUrl.isBlank()) {
                    BridgeLogStore.warn("Start requested without RTSP URL")
                    BridgeStateStore.update { it.copy(status = "error", message = "Missing RTSP URL") }
                    startAsForeground("Missing RTSP URL")
                } else {
                    startBridge(rtspUrl)
                }
            }
            ACTION_STOP -> {
                BridgeLogStore.info("Stop requested")
                rtspBridge.stop()
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        BridgeLogStore.info("Bridge service destroyed")
        rtspBridge.stop()
        httpServer.stop()
        BridgeStateStore.update {
            it.copy(status = "idle", message = "Bridge stopped", playlistUrl = "", rtspUrl = "", bridgeId = "")
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBridge(rtspUrl: String) {
        BridgeLogStore.info("Starting bridge for $rtspUrl")
        rtspBridge.stop()
        val launch = rtspBridge.start(rtspUrl) { state ->
            BridgeStateStore.update { current ->
                current.copy(
                    bridgeId = state.bridgeId.ifBlank { current.bridgeId },
                    rtspUrl = state.rtspUrl.ifBlank { current.rtspUrl },
                    playlistUrl = state.playlistUrl.ifBlank { current.playlistUrl },
                    status = state.status,
                    message = state.message,
                    serverUrl = httpServer.baseUrl(),
                )
            }
        }
        BridgeStateStore.update {
            it.copy(
                bridgeId = launch.bridgeId,
                rtspUrl = rtspUrl,
                playlistUrl = launch.playlistUrl,
                serverUrl = httpServer.baseUrl(),
                status = "starting",
                message = "Launching LibVLC bridge",
            )
        }
        startAsForeground("Bridging RTSP stream")
    }

    private fun startAsForeground(message: String) {
        ensureChannel()
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("OpenMonitor Bridge")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val channel = NotificationChannel(CHANNEL_ID, "OpenMonitor Bridge", NotificationManager.IMPORTANCE_LOW)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_START = "com.openmonitor.bridge.action.START"
        const val ACTION_STOP = "com.openmonitor.bridge.action.STOP"
        const val EXTRA_RTSP_URL = "extra_rtsp_url"
        private const val CHANNEL_ID = "openmonitor_bridge"
        private const val NOTIFICATION_ID = 42

        fun startIntent(context: Context, rtspUrl: String): Intent {
            return Intent(context, BridgeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RTSP_URL, rtspUrl)
            }
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, BridgeService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
