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
import java.util.concurrent.atomic.AtomicBoolean
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

    private data class BridgeProfile(
        val name: String,
        val options: List<String>,
        val sout: String,
        val hardwareDecode: Boolean,
    )

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
        val segmentBaseUrl = "${NetworkUtils.serverUrl(BridgeConfig.HTTP_PORT)}/hls/$bridgeId/segment-########.ts"
        val profiles = listOf(
            BridgeProfile(
                name = "remux",
                options = listOf("--network-caching=2000", "--rtsp-tcp", "--sout-keep"),
                sout = buildSout(
                    playlistPath = playlistFile.absolutePath,
                    segmentPath = segmentPattern,
                    indexUrl = segmentBaseUrl,
                    videoTranscode = null,
                    audioTranscode = null,
                ),
                hardwareDecode = true,
            ),
            BridgeProfile(
                name = "video-only-transcode",
                options = listOf("--network-caching=2000", "--rtsp-tcp", "--sout-keep"),
                sout = buildSout(
                    playlistPath = playlistFile.absolutePath,
                    segmentPath = segmentPattern,
                    indexUrl = segmentBaseUrl,
                    videoTranscode = "vcodec=h264,vb=900",
                    audioTranscode = null,
                ),
                hardwareDecode = false,
            ),
            BridgeProfile(
                name = "video-audio-transcode",
                options = listOf("--network-caching=2000", "--rtsp-tcp", "--sout-keep"),
                sout = buildSout(
                    playlistPath = playlistFile.absolutePath,
                    segmentPath = segmentPattern,
                    indexUrl = segmentBaseUrl,
                    videoTranscode = "vcodec=h264,vb=900",
                    audioTranscode = "acodec=mp4a,ab=128,channels=2,samplerate=44100",
                ),
                hardwareDecode = false,
            ),
        )
        BridgeLogStore.info("Bridge $bridgeId output dir: ${bridgeDir.absolutePath}")
        BridgeLogStore.info("Bridge $bridgeId playlist: ${playlistFile.absolutePath}")
        BridgeLogStore.info("Bridge $bridgeId segment URL: $segmentBaseUrl")

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
            for (profile in profiles) {
                val errorSeen = AtomicBoolean(false)
                val errorMessage = arrayOfNulls<String>(1)
                BridgeLogStore.info("Bridge $bridgeId trying profile ${profile.name}")
                notifyState(
                    BridgeState(
                        bridgeId = bridgeId,
                        rtspUrl = rtspUrl,
                        playlistUrl = "/hls/$bridgeId/index.m3u8",
                        status = "starting",
                        message = "Trying ${profile.name}",
                    ),
                )
                stop()
                var profileSucceeded = false
                try {
                    val options = arrayListOf(
                        "--quiet",
                        "--no-video-title-show",
                        "--no-audio-time-stretch",
                    )
                    options.addAll(profile.options)
                    BridgeLogStore.info("Bridge $bridgeId VLC options (${profile.name}): ${options.joinToString(" ")}")
                    val vlc = LibVLC(context, options)
                    val player = MediaPlayer(vlc)
                    libVlc = vlc
                    mediaPlayer = player
                    player.setEventListener(object : MediaPlayer.EventListener {
                        override fun onEvent(event: MediaPlayer.Event) {
                            val name = when (event.type) {
                                MediaPlayer.Event.Opening -> "Opening"
                                MediaPlayer.Event.Buffering -> "Buffering"
                                MediaPlayer.Event.Playing -> "Playing"
                                MediaPlayer.Event.Paused -> "Paused"
                                MediaPlayer.Event.Stopped -> "Stopped"
                                MediaPlayer.Event.EndReached -> "EndReached"
                                MediaPlayer.Event.EncounteredError -> "EncounteredError"
                                MediaPlayer.Event.TimeChanged -> "TimeChanged"
                                MediaPlayer.Event.PositionChanged -> "PositionChanged"
                                MediaPlayer.Event.Vout -> "Vout"
                                else -> "Event(${event.type})"
                            }
                            BridgeLogStore.info("Bridge $bridgeId VLC event (${profile.name}): $name")
                            if (event.type == MediaPlayer.Event.EncounteredError) {
                                errorSeen.set(true)
                                errorMessage[0] = "LibVLC reported an error in profile ${profile.name}"
                            }
                        }
                    })

                    val media = Media(vlc, Uri.parse(rtspUrl))
                    media.setHWDecoderEnabled(profile.hardwareDecode, false)
                    media.addOption(":sout=${profile.sout}")
                    media.addOption(":sout-all")
                    media.addOption(":no-sout-audio")
                    if (profile.name == "remux") {
                        media.addOption(":no-audio")
                    }

                    player.setMedia(media)
                    media.release()
                    BridgeLogStore.info("Bridge $bridgeId starting playback (${profile.name})")
                    player.play()

                    var second = 0
                    while (second < 20) {
                        if (playlistFile.exists()) {
                            BridgeLogStore.info("Bridge $bridgeId playlist exists after ${second + 1} seconds (${profile.name})")
                            notifyState(
                                BridgeState(
                                    bridgeId = bridgeId,
                                    rtspUrl = rtspUrl,
                                    playlistUrl = "/hls/$bridgeId/index.m3u8",
                                    status = "running",
                                    message = "Bridge is live via ${profile.name}",
                                ),
                            )
                            profileSucceeded = true
                            break
                        }
                        if (errorSeen.get()) {
                            BridgeLogStore.warn("Bridge $bridgeId profile ${profile.name} failed early: ${errorMessage[0]}")
                            break
                        }
                        BridgeLogStore.info("Bridge $bridgeId waiting for HLS output (${profile.name}) ${second + 1}/20")
                        TimeUnit.SECONDS.sleep(1)
                        second += 1
                    }

                    if (!errorSeen.get()) {
                        BridgeLogStore.warn("Bridge $bridgeId profile ${profile.name} timed out without HLS output")
                    }
                } catch (exception: Exception) {
                    BridgeLogStore.error("Bridge $bridgeId profile ${profile.name} failed: ${exception.stackTraceToString()}")
                } finally {
                    if (!profileSucceeded) {
                        stop()
                    }
                }
                if (profileSucceeded) return@thread
            }

            BridgeLogStore.error("Bridge $bridgeId exhausted all profiles without generating HLS")
            notifyState(
                BridgeState(
                    bridgeId = bridgeId,
                    rtspUrl = rtspUrl,
                    playlistUrl = "/hls/$bridgeId/index.m3u8",
                    status = "error",
                    message = "All bridge profiles failed; open logs for details",
                ),
            )
        }

        return BridgeLaunch(bridgeId, playlistFile, "/hls/$bridgeId/index.m3u8")
    }

    fun stop() {
        BridgeLogStore.info("Stopping bridge")
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

    private fun buildSout(
        playlistPath: String,
        segmentPath: String,
        indexUrl: String,
        videoTranscode: String?,
        audioTranscode: String?,
    ): String {
        val transcodeParts = mutableListOf<String>()
        if (videoTranscode != null) transcodeParts += videoTranscode
        if (audioTranscode != null) transcodeParts += audioTranscode
        val transcode = if (transcodeParts.isEmpty()) "" else "#transcode{${transcodeParts.joinToString(",")}}:"
        return "${transcode}std{access=livehttp{seglen=2,delsegs=true,numsegs=6,index=$playlistPath,index-url=$indexUrl},mux=ts{use-key-frames},dst=$segmentPath}"
    }

    private fun bridgeKeyFor(rtspUrl: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(rtspUrl.toByteArray())
        return digest.take(10).joinToString("") { byte -> "%02x".format(Locale.US, byte) }
    }
}
