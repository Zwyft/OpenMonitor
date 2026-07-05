package com.openmonitor.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var rtspInput: EditText
    private lateinit var statusView: TextView
    private lateinit var scanStatusView: TextView
    private lateinit var serverView: TextView
    private lateinit var hlsView: TextView
    private lateinit var bridgeIdView: TextView
    private lateinit var logView: TextView
    private lateinit var cameraContainer: LinearLayout
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var scanButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        updatePermissionHint()
    }

    private val refreshRunnable = object : Runnable {
        override fun run() {
            renderState()
            uiHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        rtspInput = findViewById(R.id.rtspInput)
        statusView = findViewById(R.id.statusValue)
        scanStatusView = findViewById(R.id.scanStatusValue)
        serverView = findViewById(R.id.serverValue)
        hlsView = findViewById(R.id.hlsValue)
        bridgeIdView = findViewById(R.id.bridgeIdValue)
        logView = findViewById(R.id.logValue)
        cameraContainer = findViewById(R.id.cameraContainer)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        scanButton = findViewById(R.id.scanButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        scanButton.setOnClickListener { scanCameras() }
        startButton.setOnClickListener { startBridge() }
        stopButton.setOnClickListener { stopBridge() }

        requestRequiredPermissions()
        renderState()
    }

    override fun onStart() {
        super.onStart()
        uiHandler.post(refreshRunnable)
    }

    override fun onStop() {
        uiHandler.removeCallbacks(refreshRunnable)
        super.onStop()
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf(Manifest.permission.FOREGROUND_SERVICE)
        if (Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        val missingPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    private fun updatePermissionHint() {
        statusView.text = "Keep the phone and iPad on the same Wi‑Fi network."
    }

    private fun scanCameras() {
        scanButton.isEnabled = false
        scanStatusView.text = "Scanning LAN for cameras..."
        BridgeLogStore.info("Camera scan requested")
        Thread {
            try {
                val scanner = CameraScanner(
                    username = usernameInput.text?.toString().orEmpty().trim(),
                    password = passwordInput.text?.toString().orEmpty().trim(),
                )
                val discoveries = scanner.scan { progress ->
                    BridgeLogStore.info(progress)
                    runOnUiThread {
                        scanStatusView.text = progress
                    }
                }
                CameraDiscoveryStore.update(discoveries)
                runOnUiThread {
                    scanStatusView.text = if (discoveries.isEmpty()) {
                        "Scan complete: no stream URIs found"
                    } else {
                        "Scan complete: found ${discoveries.size} candidate(s)"
                    }
                    renderCameraCandidates()
                }
            } catch (exception: Exception) {
                BridgeLogStore.error("Camera scan failed: ${exception.stackTraceToString()}")
                runOnUiThread {
                    scanStatusView.text = "Scan failed: ${exception.message ?: "unknown error"}"
                }
            } finally {
                runOnUiThread {
                    scanButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun startBridge() {
        val rtspUrl = rtspInput.text?.toString().orEmpty().trim()
        if (rtspUrl.isBlank()) {
            statusView.text = "Enter an RTSP URL first."
            return
        }
        ContextCompat.startForegroundService(this, BridgeService.startIntent(this, rtspUrl))
    }

    private fun stopBridge() {
        startService(BridgeService.stopIntent(this))
        BridgeStateStore.update { it.copy(status = "idle", message = "Bridge stopped") }
        renderState()
    }

    private fun renderState() {
        val state = BridgeStateStore.snapshot()
        statusView.text = state.status + if (state.message.isBlank()) "" else " • ${state.message}"
        serverView.text = state.serverUrl.ifBlank { NetworkUtils.serverUrl(BridgeConfig.HTTP_PORT) }
        val serverUrl = state.serverUrl.ifBlank { NetworkUtils.serverUrl(BridgeConfig.HTTP_PORT) }
        hlsView.text = if (state.playlistUrl.isBlank()) "—" else "$serverUrl${state.playlistUrl}"
        bridgeIdView.text = state.bridgeId.ifBlank { "—" }
        logView.text = BridgeLogStore.snapshot(24)
            .joinToString("\n") { it.formatLine() }
            .ifBlank { "No logs yet." }
        renderCameraCandidates()
    }

    private fun renderCameraCandidates() {
        val discoveries = CameraDiscoveryStore.snapshot()
        cameraContainer.removeAllViews()
        if (discoveries.isEmpty()) {
            val empty = TextView(this).apply {
                text = "No cameras discovered yet."
                setTextColor(0xFF94A3B8.toInt())
            }
            cameraContainer.addView(empty)
            return
        }
        discoveries.forEach { discovery ->
            val button = Button(this).apply {
                text = discovery.displayLine()
                setOnClickListener {
                    rtspInput.setText(discovery.streamUrl)
                    statusView.text = "Selected ${discovery.label}"
                    startBridge()
                }
            }
            cameraContainer.addView(button)
        }
    }
}
