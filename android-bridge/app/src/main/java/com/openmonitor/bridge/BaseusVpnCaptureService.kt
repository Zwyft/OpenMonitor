package com.openmonitor.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
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
    @Volatile
    private var targetIp: String = DEFAULT_TARGET_IP

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int): Int {
        targetIp = intent?.getStringExtra(EXTRA_TARGET_IP).orEmpty().ifBlank { DEFAULT_TARGET_IP }
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
            updateState(true, "VPN capture already running for ${targetPackage()} → $targetIp")
            promoteToForeground()
            return
        }
        promoteToForeground()
        updateState(true, "Starting VPN capture for ${targetPackage()} → $targetIp")
        worker = Thread {
            try {
                val builder = Builder()
                    .setSession("OpenMonitor Baseus capture")
                    .setMtu(1500)
                    .addAddress("10.0.0.2", 32)
                    .addRoute("0.0.0.0", 0)
                    .addRoute("::", 0)
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
        updateState(true, "VPN capture running for ${targetPackage()} → $targetIp")
        BridgeLogStore.info("VPN capture established for ${targetPackage()} → $targetIp")

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
        val captureTargetIp = targetIp
        if (length < 20) return
        val version = (packet[0].toInt() ushr 4) and 0x0F
        when (version) {
            4 -> parseIpv4Packet(packet, length, captureTargetIp)
            6 -> parseIpv6Packet(packet, length, captureTargetIp)
        }
    }

    private fun parseIpv4Packet(packet: ByteArray, length: Int, captureTargetIp: String) {
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
                val tcpHeaderLength = ((packet[ihl + 12].toInt() ushr 4) and 0x0F) * 4
                val payloadLength = totalLength - ihl - tcpHeaderLength
                BridgeLogStore.info(
                    "VPN TCP $sourceIp:$sourcePort -> $destinationIp:$destinationPort flags=${tcpFlags(flags)} payload=$payloadLength",
                )
                if (payloadLength > 0 && length >= ihl + tcpHeaderLength + payloadLength) {
                    val payloadOffset = ihl + tcpHeaderLength
                    parseTcpPayload(packet, payloadOffset, payloadLength, sourceIp, sourcePort, destinationIp, destinationPort)
                }
                if (sourceIp == captureTargetIp || destinationIp == captureTargetIp) {
                    BaseusVpnCaptureStateStore.update {
                        it.copy(message = "TCP $sourceIp:$sourcePort -> $destinationIp:$destinationPort", targetIp = captureTargetIp)
                    }
                }
            }
            17 -> {
                if (length < ihl + 8) return
                val sourcePort = readUnsignedShort(packet, ihl)
                val destinationPort = readUnsignedShort(packet, ihl + 2)
                BridgeLogStore.info("VPN UDP $sourceIp:$sourcePort -> $destinationIp:$destinationPort length=$totalLength")
                if (sourcePort == 53 || destinationPort == 53) {
                    parseDns(packet, ihl + 8, totalLength - ihl - 8, sourceIp, destinationIp)
                }
                if (sourceIp == captureTargetIp || destinationIp == captureTargetIp) {
                    BaseusVpnCaptureStateStore.update {
                        it.copy(message = "UDP $sourceIp:$sourcePort -> $destinationIp:$destinationPort", targetIp = captureTargetIp)
                    }
                }
            }
        }
    }

    private fun parseIpv6Packet(packet: ByteArray, length: Int, captureTargetIp: String) {
        if (length < 40) return
        val nextHeader = packet[6].toInt() and 0xFF
        val sourceIp = ipv6(packet, 8)
        val destinationIp = ipv6(packet, 24)
        when (nextHeader) {
            6 -> {
                if (length < 60) return
                val sourcePort = readUnsignedShort(packet, 40)
                val destinationPort = readUnsignedShort(packet, 42)
                val tcpHeaderLength = ((packet[52].toInt() ushr 4) and 0x0F) * 4
                val flags = packet[53].toInt() and 0xFF
                BridgeLogStore.info(
                    "VPN TCP6 $sourceIp:$sourcePort -> $destinationIp:$destinationPort flags=${tcpFlags(flags)}",
                )
                val payloadOffset = 40 + tcpHeaderLength
                val payloadLength = length - payloadOffset
                if (payloadLength > 0 && length >= payloadOffset + payloadLength) {
                    parseTcpPayload(packet, payloadOffset, payloadLength, sourceIp, sourcePort, destinationIp, destinationPort)
                }
                if (sourceIp == captureTargetIp || destinationIp == captureTargetIp) {
                    BaseusVpnCaptureStateStore.update {
                        it.copy(message = "TCP6 $sourceIp:$sourcePort -> $destinationIp:$destinationPort", targetIp = captureTargetIp)
                    }
                }
            }
            17 -> {
                if (length < 48) return
                val sourcePort = readUnsignedShort(packet, 40)
                val destinationPort = readUnsignedShort(packet, 42)
                BridgeLogStore.info("VPN UDP6 $sourceIp:$sourcePort -> $destinationIp:$destinationPort")
                if (sourcePort == 53 || destinationPort == 53) {
                    parseDns(packet, 48, length - 48, sourceIp, destinationIp)
                }
                if (sourceIp == captureTargetIp || destinationIp == captureTargetIp) {
                    BaseusVpnCaptureStateStore.update {
                        it.copy(message = "UDP6 $sourceIp:$sourcePort -> $destinationIp:$destinationPort", targetIp = captureTargetIp)
                    }
                }
            }
        }
    }

    private fun parseTcpPayload(
        packet: ByteArray,
        offset: Int,
        length: Int,
        sourceIp: String,
        sourcePort: Int,
        destinationIp: String,
        destinationPort: Int,
    ) {
        if (length <= 0) return
        if (length >= 5 && packet[offset] == 0x16.toByte() && packet[offset + 1] == 0x03.toByte()) {
            parseTlsClientHello(packet, offset, length, sourceIp, sourcePort, destinationIp, destinationPort)
            return
        }
        if (length >= 4 && (sourcePort == 80 || destinationPort == 80)) {
            val text = runCatching { String(packet, offset, minOf(length, 128), Charsets.UTF_8) }.getOrNull().orEmpty()
            if (text.startsWith("GET ") || text.startsWith("POST ") || text.startsWith("PUT ") || text.startsWith("DELETE ")) {
                val firstLine = text.lineSequence().firstOrNull().orEmpty()
                BridgeLogStore.info("VPN HTTP $sourceIp:$sourcePort -> $destinationIp:$destinationPort request=$firstLine")
            }
        }
    }

    private fun parseTlsClientHello(
        packet: ByteArray,
        offset: Int,
        length: Int,
        sourceIp: String,
        sourcePort: Int,
        destinationIp: String,
        destinationPort: Int,
    ) {
        if (length < 9) return
        val recordLength = readUnsignedShort(packet, offset + 3)
        if (recordLength <= 0 || recordLength + 5 > length) return
        val handshakeType = packet[offset + 5].toInt() and 0xFF
        if (handshakeType != 0x01) return
        var cursor = offset + 9
        if (cursor + 2 > offset + length) return
        cursor += 2 // client_version
        cursor += 32 // random
        if (cursor >= offset + length) return
        val sessionIdLength = packet[cursor].toInt() and 0xFF
        cursor += 1 + sessionIdLength
        if (cursor + 2 > offset + length) return
        val cipherSuiteLength = readUnsignedShort(packet, cursor)
        cursor += 2 + cipherSuiteLength
        if (cursor >= offset + length) return
        val compressionMethodsLength = packet[cursor].toInt() and 0xFF
        cursor += 1 + compressionMethodsLength
        if (cursor + 2 > offset + length) return
        val extensionsLength = readUnsignedShort(packet, cursor)
        cursor += 2
        val extensionsEnd = minOf(cursor + extensionsLength, offset + length)
        while (cursor + 4 <= extensionsEnd) {
            val extensionType = readUnsignedShort(packet, cursor)
            val extensionLength = readUnsignedShort(packet, cursor + 2)
            cursor += 4
            if (cursor + extensionLength > extensionsEnd) return
            if (extensionType == 0x0000 && extensionLength >= 5) {
                val listLength = readUnsignedShort(packet, cursor)
                var listCursor = cursor + 2
                val listEnd = minOf(listCursor + listLength, cursor + extensionLength)
                while (listCursor + 3 <= listEnd) {
                    val nameType = packet[listCursor].toInt() and 0xFF
                    val nameLength = readUnsignedShort(packet, listCursor + 1)
                    listCursor += 3
                    if (listCursor + nameLength > listEnd) break
                    if (nameType == 0x00) {
                        val serverName = String(packet, listCursor, nameLength, Charsets.UTF_8)
                        BridgeLogStore.info("VPN TLS SNI $sourceIp:$sourcePort -> $destinationIp:$destinationPort host=$serverName")
                        BaseusVpnCaptureStateStore.update {
                            it.copy(message = "TLS SNI $serverName", targetIp = targetIp)
                        }
                        return
                    }
                    listCursor += nameLength
                }
            }
            cursor += extensionLength
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

    private fun ipv6(packet: ByteArray, offset: Int): String {
        return try {
            InetAddress.getByAddress(packet.copyOfRange(offset, offset + 16)).hostAddress ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
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
            it.copy(running = running, message = message, packageName = targetPackage(), targetIp = targetIp)
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
            .setContentText("Capturing traffic for ${targetPackage()} → $targetIp")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopPendingIntent)
            .build()
    }

    private fun promoteToForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
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
        private const val EXTRA_TARGET_IP = "extra_target_ip"
        private const val DEFAULT_TARGET_IP = "192.168.4.25"

        fun startIntent(context: android.content.Context, targetIp: String = DEFAULT_TARGET_IP): android.content.Intent {
            return android.content.Intent(context, BaseusVpnCaptureService::class.java).apply {
                putExtra(EXTRA_TARGET_IP, targetIp)
            }
        }

        fun stopIntent(context: android.content.Context): android.content.Intent {
            return android.content.Intent(context, BaseusVpnCaptureService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }
}
