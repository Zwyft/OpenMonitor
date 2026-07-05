package com.openmonitor.bridge

import android.content.Context
import android.net.Uri
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
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

class RtspHlsBridge(
    private val context: Context,
    private val cacheRoot: File,
) {
    @Volatile
    private var libVlc: LibVLC? = null

    @Volatile
    private var mediaPlayer: MediaPlayer? = null

    fun start(rtspUrl: String, notifyState: (BridgeState) -> Unit): BridgeLaunch {
        stop()

        val bridgeId = bridgeKeyFor(rtspUrl)
        val bridgeDir = File(cacheRoot, "hls/$bridgeId")
        if (bridgeDir.exists()) {
            bridgeDir.deleteRecursively()
        }
        bridgeDir.mkdirs()

        val playlistFile = File(bridgeDir, "index.m3u8")
        val segmentPattern = File(bridgeDir, "segment-########.ts").absolutePath
        val localIndexUrl = "segment-########.ts"
        val sout = buildSout(playlistFile.absolutePath, segmentPattern, localIndexUrl)

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
            try {
                val options = arrayListOf(
                    "--quiet",
                    "--no-video-title-show",
                    "--no-audio-time-stretch",
                    "--rtsp-tcp",
                    "--network-caching=1000",
                    "--sout-keep",
                )
                val vlc = LibVLC(context, options)
                val player = MediaPlayer(vlc)
                libVlc = vlc
                mediaPlayer = player

                val media = Media(vlc, Uri.parse(rtspUrl))
                media.setHWDecoderEnabled(true, false)
                media.addOption(":sout=$sout")
                media.addOption(":sout-all")
                media.addOption(":no-sout-audio")

                player.setMedia(media)
                media.release()
                player.play()
            } catch (exception: Exception) {
                notifyState(
                    BridgeState(
                        bridgeId = bridgeId,
                        rtspUrl = rtspUrl,
                        playlistUrl = "/hls/$bridgeId/index.m3u8",
                        status = "error",
                        message = exception.message ?: "Bridge failed to start",
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
                    return@thread
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

    fun stop() {
        try {
            mediaPlayer?.stop()
        } catch (_: Exception) {
        }
        try {
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        try {
            libVlc?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
        libVlc = null
    }

    private fun buildSout(playlistPath: String, segmentPath: String, indexUrl: String): String {
        return "#transcode{vcodec=h264,vb=900,acodec=mp4a,ab=128,channels=2,samplerate=44100}:std{access=livehttp{seglen=2,delsegs=true,numsegs=6,index=$playlistPath,index-url=$indexUrl},mux=ts{use-key-frames},dst=$segmentPath}"
    }

    private fun bridgeKeyFor(rtspUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rtspUrl.toByteArray())
        return digest.take(10).joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    }
}
