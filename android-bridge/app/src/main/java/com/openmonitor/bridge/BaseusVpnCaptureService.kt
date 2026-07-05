package com.openmonitor.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class BaseusVpnCaptureService : VpnService() {
    private val running = AtomicBoolean(false)
    @Volatile
    private var tunInterface: ParcelFileDescriptor? = null
    @Volatile
    private var worker: Thread? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopCapture()
            else -> startCapture()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopCapture()
        super.onRevoke()
    }

    override fun onBind(intent: android.content.Intent?) = null

    private fun startCapture() {
        if (!running.compareAndSet(false, true)) {
            updateState(true, "VPN capture already running for ${targetPackage()}")
            startForeground(NOTIFICATION_ID, buildNotification())
            return
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        updateState(true, "Starting VPN capture for ${targetPackage()}")
        worker = Thread {
            try {
                val builder = Builder()
                    .setSession("OpenMonitor Baseus capture")
                    .setMtu(1500)
                    .setBlocking(true)
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addDnsServer("1.1.1.1")

                runCatching {
                    builder.addAllowedApplication(targetPackage())
                }.onFailure { exception ->
                    throw IllegalStateException("Baseus app package not found: ${exception.message ?: "unknown"}")
                }

                establishAndCapture(builder)
            } catch (exception: Exception) {
                BridgeLogStore.error("VPN capture failed: ${exception.stackTraceToString()}")
                updateState(false, "VPN capture failed: ${exception.message ?: "unknown error"}")
            } finally {
                stopCaptureInternal()
            }
        }.also { it.start() }
    }

    private fun establishAndCapture(builder: Builder) {
        val fd = builder.establish() ?: throw IllegalStateException("VPN establish returned null")
        tunInterface = fd
        updateState(true, "VPN capture running for ${targetPackage()}")
        BridgeLogStore.info("VPN capture established for ${targetPackage()}")

        FileInputStream(fd.fileDescriptor).use { input ->
            val buffer = ByteArray(32767)
            while (running.get()) {
                val read = input.read(buffer)
                if (read <= 0) {
                    continue
                }
                parsePacket(buffer, read)
            }
        }
    }

    private fun parsePacket(packet: ByteArray, length: Int) {
        if (length < 20) return
        val version = (packet[0].toInt() ushr 4) and 0x0F
        if (version != 4) return
        val ihl = (packet[0].toInt() and 0x0F) * 4
        if (length < ihl + 8) return
        val totalLength = readUnsignedShort(packet, 2)
        if (totalLength < ihl) return
        val protocol = packet[9].toInt() and 0xFF
        val sourceIp = ipv4(packet, 12)
        val destinationIp = ipv4(packet, 16)
        when (protocol) {
            6 -> {
                if (length < ihl + 20) return
                val sourcePort = readUnsignedShort(packet, ihl)
                val destinationPort = readUnsignedShort(packet, ihl + 2)
                val flags = packet[ihl + 13].toInt() and 0xFF
                val syn = flags and 0x02 != 0
                val ack = flags and 0x10 != 0
                val fin = flags and 0x01 != 0
                val rst = flags and 0x04 != 0
                if (syn || fin || rst || destinationPort == 443 || destinationPort == 554 || destinationPort == 8554) {
                    val payloadLength = totalLength - ihl - (((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4)
                    BridgeLogStore.info(
                        "VPN TCP $sourceIp:$sourcePort -> $destinationIp:$destinationPort flags=${tcpFlags(flags)} payload=$payloadLength",
                    )
                    BaseusVpnCaptureStateStore.update {
                        it.copy(message = "TCP $sourceIp:$sourcePort -> $destinationIp:$destinationPort")
                    }
                }
            }
            17 -> {
                if (length < ihl + 8) return
                val sourcePort = readUnsignedShort(packet, ihl)
                val destinationPort = readUnsignedShort(packet, ihl + 2)
                if (sourcePort == 53 || destinationPort == 53 || sourcePort == 443 || destinationPort == 443 || sourcePort == 554 || destinationPort == 554) {
                    BridgeLogStore.info("VPN UDP $sourceIp:$sourcePort -> $destinationIp:$destinationPort length=$totalLength")
                    if (sourcePort == 53 || destinationPort == 53) {
                        parseDns(packet, ihl + 8, totalLength - ihl - 8, sourceIp, destinationIp)
                    }
                    BaseusVpnCaptureStateStore.update {
                        it.copy(message = "UDP $sourceIp:$sourcePort -> $destinationIp:$destinationPort")
                    }
                }
            }
        }
    }

    private fun parseDns(packet: ByteArray, offset: Int, length: Int, sourceIp: String, destinationIp: String) {
        if (length < 12) return
        val flags = readUnsignedShort(packet, offset + 2)
        val questionCount = readUnsignedShort(packet, offset + 4)
        if (questionCount <= 0) return
        var cursor = offset + 12
        val end = offset + length
        val names = mutableListOf<String>()
        repeat(questionCount) {
            val (name, next) = readDnsName(packet, cursor, end, offset) ?: return
            cursor = next
            if (cursor + 4 > end) return
            val qType = readUnsignedShort(packet, cursor)
            cursor += 4
            names += "$name(type=$qType)"
        }
        BridgeLogStore.info("VPN DNS $sourceIp -> $destinationIp queries=${names.joinToString(", ")} flags=0x${flags.toString(16)}")
    }

    private fun readDnsName(packet: ByteArray, start: Int, end: Int, baseOffset: Int): Pair<String, Int>? {
        var cursor = start
        val labels = mutableListOf<String>()
        var jumped = false
        var jumpEnd = start
        var safety = 0
        while (cursor < end && safety < 32) {
            safety++
            val length = packet[cursor].toInt() and 0xFF
            if (length == 0) {
                cursor++
                if (!jumped) jumpEnd = cursor
                return labels.joinToString(".") to if (jumped) jumpEnd else cursor
            }
            if (length and 0xC0 == 0xC0) {
                if (cursor + 1 >= end) return null
                val pointer = ((length and 0x3F) shl 8) or (packet[cursor + 1].toInt() and 0xFF)
                if (!jumped) {
                    jumpEnd = cursor + 2
                    jumped = true
                }
                cursor = baseOffset + pointer
                continue
            }
            cursor++
            if (cursor + length > end) return null
            labels += String(packet, cursor, length, Charsets.UTF_8)
            cursor += length
            if (!jumped) jumpEnd = cursor
        }
        return null
    }

    private fun tcpFlags(flags: Int): String {
        val names = mutableListOf<String>()
        if (flags and 0x01 != 0) names += "FIN"
        if (flags and 0x02 != 0) names += "SYN"
        if (flags and 0x04 != 0) names += "RST"
        if (flags and 0x08 != 0) names += "PSH"
        if (flags and 0x10 != 0) names += "ACK"
        if (flags and 0x20 != 0) names += "URG"
        return names.joinToString("|")
    }

    private fun ipv4(packet: ByteArray, offset: Int): String {
        return listOf(
            packet[offset].toInt() and 0xFF,
            packet[offset + 1].toInt() and 0xFF,
            packet[offset + 2].toInt() and 0xFF,
            packet[offset + 3].toInt() and 0xFF,
        ).joinToString(".")
    }

    private fun readUnsignedShort(packet: ByteArray, offset: Int): Int {
        return ByteBuffer.wrap(packet, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
    }

    private fun stopCapture() {
        running.set(false)
        stopCaptureInternal()
    }

    private fun stopCaptureInternal() {
        try {
            tunInterface?.close()
        } catch (_: Exception) {
        }
        tunInterface = null
        worker?.interrupt()
        worker = null
        updateState(false, "VPN capture stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateState(running: Boolean, message: String) {
        BaseusVpnCaptureStateStore.update {
            it.copy(running = running, message = message, packageName = targetPackage())
        }
    }

    private fun targetPackage(): String = "com.baseus.security.ipc"

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
            .setContentTitle("OpenMonitor VPN capture")
            .setContentText("Capturing traffic for ${targetPackage()}")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "OpenMonitor VPN capture",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun pendingIntentImmutableFlag(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
    }

    companion object {
        private const val CHANNEL_ID = "openmonitor_vpn_capture"
        private const val NOTIFICATION_ID = 2003
        private const val ACTION_STOP = "com.openmonitor.bridge.action.STOP_VPN_CAPTURE"

        fun startIntent(context: android.content.Context): android.content.Intent {
            return android.content.Intent(context, BaseusVpnCaptureService::class.java)
        }

        fun stopIntent(context: android.content.Context): android.content.Intent {
            return android.content.Intent(context, BaseusVpnCaptureService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
