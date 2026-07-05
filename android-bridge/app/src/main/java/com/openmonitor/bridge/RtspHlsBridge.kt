package com.openmonitor.bridge

import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

data class BridgeLaunch(
    val bridgeId: String,
    val playlistFile: File,
    val playlistUrl: String,
)

class RtspHlsBridge(private val cacheRoot: File) {
    fun start(rtspUrl: String, notifyState: (BridgeState) -> Unit): BridgeLaunch {
        val bridgeId = bridgeKeyFor(rtspUrl)
        val bridgeDir = File(cacheRoot, "hls/$bridgeId")
        if (bridgeDir.exists()) {
            bridgeDir.deleteRecursively()
        }
        bridgeDir.mkdirs()

        val playlistFile = File(bridgeDir, "index.m3u8")
        val segmentPattern = File(bridgeDir, "segment-%05d.ts").absolutePath
        val command = buildCommand(rtspUrl, playlistFile.absolutePath, segmentPattern)
        notifyState(
            BridgeState(
                bridgeId = bridgeId,
                rtspUrl = rtspUrl,
                playlistUrl = "/hls/$bridgeId/index.m3u8",
                status = "starting",
                message = "Starting RTSP bridge",
            ),
        )

        thread(name = "RtspHlsBridge-$bridgeId", isDaemon = true) {
            val session = FFmpegKit.execute(command)
            val returnCode = session.returnCode
            if (ReturnCode.isSuccess(returnCode)) {
                notifyState(
                    BridgeState(
                        bridgeId = bridgeId,
                        rtspUrl = rtspUrl,
                        playlistUrl = "/hls/$bridgeId/index.m3u8",
                        status = "running",
                        message = "RTSP bridge stopped cleanly",
                    ),
                )
            } else {
                val failureMessage = session.failStackTrace ?: session.output
                notifyState(
                    BridgeState(
                        bridgeId = bridgeId,
                        rtspUrl = rtspUrl,
                        playlistUrl = "/hls/$bridgeId/index.m3u8",
                        status = "error",
                        message = failureMessage.ifBlank { "Bridge failed" },
                    ),
                )
            }
        }

        thread(name = "RtspHlsBridgeWait-$bridgeId", isDaemon = true) {
            repeat(30) {
                if (playlistFile.exists()) {
                    notifyState(
                        BridgeState(
                            bridgeId = bridgeId,
                            rtspUrl = rtspUrl,
                            playlistUrl = "/hls/$bridgeId/index.m3u8",
                            status = "running",
                            message = "Bridge is live",
                        ),
                    )
                    return@execute
                }
                TimeUnit.SECONDS.sleep(1)
            }
            if (!playlistFile.exists()) {
                notifyState(
                    BridgeState(
                        bridgeId = bridgeId,
                        rtspUrl = rtspUrl,
                        playlistUrl = "/hls/$bridgeId/index.m3u8",
                        status = "error",
                        message = "Timed out waiting for HLS output",
                    ),
                )
            }
        }

        return BridgeLaunch(bridgeId, playlistFile, "/hls/$bridgeId/index.m3u8")
    }

    private fun buildCommand(rtspUrl: String, playlistPath: String, segmentPattern: String): String {
        return listOf(
            "-hide_banner",
            "-loglevel",
            "warning",
            "-rtsp_transport",
            "tcp",
            "-i",
            shellQuote(rtspUrl),
            "-map",
            "0:v:0",
            "-map",
            "0:a?",
            "-c:v",
            "copy",
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-ar",
            "44100",
            "-f",
            "hls",
            "-hls_time",
            "2",
            "-hls_list_size",
            "6",
            "-hls_flags",
            "delete_segments+append_list+omit_endlist+program_date_time",
            "-hls_segment_filename",
            shellQuote(segmentPattern),
            shellQuote(playlistPath),
        ).joinToString(" ")
    }

    private fun bridgeKeyFor(rtspUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rtspUrl.toByteArray())
        return digest.take(10).joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    }

    private fun shellQuote(value: String): String {
        return "'${value.replace("'", "'\"'\"'")}'"
    }
}
