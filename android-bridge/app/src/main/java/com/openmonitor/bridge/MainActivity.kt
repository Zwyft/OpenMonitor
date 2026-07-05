package com.openmonitor.bridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
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
    private lateinit var baseusTargetsInput: EditText
    private lateinit var proxyStatusView: TextView
    private lateinit var vpnStatusView: TextView
    private lateinit var vicohomeEmailInput: EditText
    private lateinit var vicohomePasswordInput: EditText
    private lateinit var vicohomeRegionSpinner: Spinner
    private lateinit var serverView: TextView
    private lateinit var hlsView: TextView
    private lateinit var bridgeIdView: TextView
    private lateinit var logView: TextView
    private lateinit var cameraContainer: LinearLayout
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var scanButton: Button
    private lateinit var captureButton: Button
    private lateinit var proxyStartButton: Button
    private lateinit var proxyStopButton: Button
    private lateinit var vpnStartButton: Button
    private lateinit var vpnStopButton: Button
    private lateinit var vicohomeButton: Button
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var vicohomeContainer: LinearLayout

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
        baseusTargetsInput = findViewById(R.id.baseusTargetsInput)
        proxyStatusView = findViewById(R.id.proxyStatusValue)
        vpnStatusView = findViewById(R.id.vpnStatusValue)
        vicohomeEmailInput = findViewById(R.id.vicohomeEmailInput)
        vicohomePasswordInput = findViewById(R.id.vicohomePasswordInput)
        vicohomeRegionSpinner = findViewById(R.id.vicohomeRegionSpinner)
        serverView = findViewById(R.id.serverValue)
        hlsView = findViewById(R.id.hlsValue)
        bridgeIdView = findViewById(R.id.bridgeIdValue)
        logView = findViewById(R.id.logValue)
        cameraContainer = findViewById(R.id.cameraContainer)
        vicohomeContainer = findViewById(R.id.vicohomeContainer)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        scanButton = findViewById(R.id.scanButton)
        captureButton = findViewById(R.id.captureButton)
        proxyStartButton = findViewById(R.id.proxyStartButton)
        proxyStopButton = findViewById(R.id.proxyStopButton)
        vpnStartButton = findViewById(R.id.vpnStartButton)
        vpnStopButton = findViewById(R.id.vpnStopButton)
        vicohomeButton = findViewById(R.id.vicohomeButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        scanButton.setOnClickListener { scanCameras() }
        captureButton.setOnClickListener { captureBaseus() }
        proxyStartButton.setOnClickListener { startProxyCapture() }
        proxyStopButton.setOnClickListener { stopProxyCapture() }
        vpnStartButton.setOnClickListener { startVpnCapture() }
        vpnStopButton.setOnClickListener { stopVpnCapture() }
        vicohomeButton.setOnClickListener { syncVicohome() }
        startButton.setOnClickListener { startBridge() }
        stopButton.setOnClickListener { stopBridge() }

        vicohomeRegionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            VicohomeRegionChoice.entries.map { it.displayName },
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

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

    private fun captureBaseus() {
        captureButton.isEnabled = false
        val targets = baseusTargetsInput.text?.toString().orEmpty()
            .split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .filter { it.isNotBlank() }
        scanStatusView.text = "Capturing Baseus endpoints..."
        BridgeLogStore.info("Baseus capture requested for ${targets.joinToString(", ")}")
        Thread {
            try {
                val scanner = CameraScanner(
                    username = usernameInput.text?.toString().orEmpty().trim(),
                    password = passwordInput.text?.toString().orEmpty().trim(),
                )
                val discoveries = scanner.captureBaseus(targets) { progress ->
                    BridgeLogStore.info(progress)
                    runOnUiThread {
                        scanStatusView.text = progress
                    }
                }
                CameraDiscoveryStore.update(discoveries)
                runOnUiThread {
                    scanStatusView.text = if (discoveries.isEmpty()) {
                        "Baseus capture complete: no stream URIs found"
                    } else {
                        "Baseus capture complete: found ${discoveries.size} candidate(s)"
                    }
                    renderCameraCandidates()
                }
            } catch (exception: Exception) {
                BridgeLogStore.error("Baseus capture failed: ${exception.stackTraceToString()}")
                runOnUiThread {
                    scanStatusView.text = "Baseus capture failed: ${exception.message ?: "unknown error"}"
                }
            } finally {
                runOnUiThread {
                    captureButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun startProxyCapture() {
        scanStatusView.text = "Starting Baseus proxy capture..."
        BridgeLogStore.info("Baseus proxy capture requested")
        ContextCompat.startForegroundService(this, BaseusProxyService.startIntent(this))
    }

    private fun stopProxyCapture() {
        BridgeLogStore.info("Baseus proxy capture stop requested")
        startService(BaseusProxyService.stopIntent(this))
        ProxyCaptureStateStore.update {
            it.copy(running = false, message = "Proxy capture stopped")
        }
        renderState()
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            ContextCompat.startForegroundService(this, BaseusVpnCaptureService.startIntent(this))
        } else {
            scanStatusView.text = "VPN permission denied."
        }
    }

    private fun startVpnCapture() {
        scanStatusView.text = "Preparing VPN capture..."
        BridgeLogStore.info("Baseus VPN capture requested")
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            ContextCompat.startForegroundService(this, BaseusVpnCaptureService.startIntent(this))
        }
    }

    private fun stopVpnCapture() {
        BridgeLogStore.info("Baseus VPN capture stop requested")
        startService(BaseusVpnCaptureService.stopIntent(this))
        BaseusVpnCaptureStateStore.update {
            it.copy(running = false, message = "VPN capture stopped")
        }
        renderState()
    }

    private fun syncVicohome() {
        vicohomeButton.isEnabled = false
        val email = vicohomeEmailInput.text?.toString().orEmpty().trim()
        val password = vicohomePasswordInput.text?.toString().orEmpty().trim()
        if (email.isBlank() || password.isBlank()) {
            scanStatusView.text = "Enter Baseus / Vicohome email and password first."
            vicohomeButton.isEnabled = true
            return
        }
        scanStatusView.text = "Syncing Baseus cloud data..."
        BridgeLogStore.info("Vicohome/Baseus cloud sync requested for $email")
        Thread {
            try {
                val regionChoice = VicohomeRegionChoice.entries[vicohomeRegionSpinner.selectedItemPosition.coerceIn(0, VicohomeRegionChoice.entries.lastIndex)]
                val client = VicohomeClient(email, password, regionChoice)
                val result = client.syncRecentData { progress ->
                    BridgeLogStore.info(progress)
                    runOnUiThread {
                        scanStatusView.text = progress
                    }
                }
                VicohomeDataStore.update(result)
                runOnUiThread {
                    scanStatusView.text = result.message
                    renderVicohomeEntries()
                }
            } catch (exception: Exception) {
                BridgeLogStore.error("Vicohome/Baseus cloud sync failed: ${exception.stackTraceToString()}")
                runOnUiThread {
                    scanStatusView.text = "Baseus cloud sync failed: ${exception.message ?: "unknown error"}"
                }
            } finally {
                runOnUiThread {
                    vicohomeButton.isEnabled = true
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
        renderVicohomeEntries()
        renderProxyCapture()
        renderVpnCapture()
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

    private fun renderVicohomeEntries() {
        val devices = VicohomeDataStore.snapshotDevices()
        val events = VicohomeDataStore.snapshotEvents()
        vicohomeContainer.removeAllViews()

        vicohomeContainer.addView(TextView(this).apply {
            text = VicohomeDataStore.snapshotMessage().ifBlank { "No Baseus cloud data loaded yet." }
            setTextColor(0xFF94A3B8.toInt())
        })

        if (devices.isNotEmpty()) {
            vicohomeContainer.addView(TextView(this).apply {
                text = "Devices"
                setTextColor(0xFFCBD5E1.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            devices.forEach { device ->
                vicohomeContainer.addView(TextView(this).apply {
                    text = buildString {
                        append(device.deviceName.ifBlank { device.serialNumber })
                        append(" • ")
                        append(device.modelNo.ifBlank { "unknown model" })
                        if (device.ip.isNotBlank()) {
                            append(" • ")
                            append(device.ip)
                        }
                    }
                    setTextColor(0xFFE2E8F0.toInt())
                })
            }
        }

        if (events.isNotEmpty()) {
            vicohomeContainer.addView(TextView(this).apply {
                text = "Recent clips"
                setTextColor(0xFFCBD5E1.toInt())
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            events.take(20).forEach { event ->
                vicohomeContainer.addView(Button(this).apply {
                    text = buildString {
                        append(event.deviceName.ifBlank { event.traceId })
                        append(" • ")
                        append(event.timestamp)
                        if (event.birdName.isNotBlank()) {
                            append(" • ")
                            append(event.birdName)
                        }
                    }
                    setOnClickListener {
                        statusView.text = "Selected Baseus cloud clip"
                        scanStatusView.text = event.videoUrl.ifBlank { "No clip URL available" }
                    }
                })
            }
        } else {
            vicohomeContainer.addView(TextView(this).apply {
                text = "No recent Baseus cloud clips loaded."
                setTextColor(0xFF94A3B8.toInt())
            })
        }
    }

    private fun renderProxyCapture() {
        val state = ProxyCaptureStateStore.snapshot()
        proxyStatusView.text = buildString {
            append(if (state.running) "Running" else "Stopped")
            append(" • ")
            append(state.message)
            append("\n")
            append("Set the Android phone Wi‑Fi proxy to 127.0.0.1:")
            append(state.port)
            append(" before opening the Baseus app.")
        }
        proxyStartButton.isEnabled = !state.running
        proxyStopButton.isEnabled = state.running
    }

    private fun renderVpnCapture() {
        val state = BaseusVpnCaptureStateStore.snapshot()
        vpnStatusView.text = buildString {
            append(if (state.running) "Running" else "Stopped")
            append(" • ")
            append(state.message)
            append("\n")
            append("Open the Baseus app after starting VPN capture.")
        }
        vpnStartButton.isEnabled = !state.running
        vpnStopButton.isEnabled = state.running
    }
}
