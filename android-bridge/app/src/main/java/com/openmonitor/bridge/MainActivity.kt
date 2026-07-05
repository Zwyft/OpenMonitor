package com.openmonitor.bridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private val uiHandler = Handler(Looper.getMainLooper())
    private lateinit var rtspInput: EditText
    private lateinit var statusView: TextView
    private lateinit var scanStatusView: TextView
    private lateinit var baseusTargetsInput: EditText
    private lateinit var proxyStatusView: TextView
    private lateinit var vpnStatusView: TextView
    private lateinit var vpnModeSpinner: Spinner
    private lateinit var vicohomeEmailInput: EditText
    private lateinit var vicohomePasswordInput: EditText
    private lateinit var vicohomeRegionSpinner: Spinner
    private lateinit var serverView: TextView
    private lateinit var hlsView: TextView
    private lateinit var bridgeIdView: TextView
    private lateinit var logView: TextView
    private lateinit var logFilterSpinner: Spinner
    private lateinit var logSearchInput: EditText
    private lateinit var copyFilteredLogsButton: Button
    private lateinit var copyAllLogsButton: Button
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
    private var pendingVpnTargetIp: String = "192.168.4.25"
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var vicohomeContainer: LinearLayout
    private lateinit var tokenHarvestStatusView: TextView
    private lateinit var tokenHarvestValue: TextView
    private lateinit var probeTokenPrefsButton: Button
    private lateinit var copyTokenCandidatesButton: Button

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
        vpnModeSpinner = findViewById(R.id.vpnModeSpinner)
        vicohomeEmailInput = findViewById(R.id.vicohomeEmailInput)
        vicohomePasswordInput = findViewById(R.id.vicohomePasswordInput)
        vicohomeRegionSpinner = findViewById(R.id.vicohomeRegionSpinner)
        serverView = findViewById(R.id.serverValue)
        hlsView = findViewById(R.id.hlsValue)
        bridgeIdView = findViewById(R.id.bridgeIdValue)
        logView = findViewById(R.id.logValue)
        logFilterSpinner = findViewById(R.id.logFilterSpinner)
        logSearchInput = findViewById(R.id.logSearchInput)
        copyFilteredLogsButton = findViewById(R.id.copyFilteredLogsButton)
        copyAllLogsButton = findViewById(R.id.copyAllLogsButton)
        cameraContainer = findViewById(R.id.cameraContainer)
        vicohomeContainer = findViewById(R.id.vicohomeContainer)
        tokenHarvestStatusView = findViewById(R.id.tokenHarvestStatusValue)
        tokenHarvestValue = findViewById(R.id.tokenHarvestValue)
        usernameInput = findViewById(R.id.usernameInput)
        passwordInput = findViewById(R.id.passwordInput)
        scanButton = findViewById(R.id.scanButton)
        captureButton = findViewById(R.id.captureButton)
        proxyStartButton = findViewById(R.id.proxyStartButton)
        proxyStopButton = findViewById(R.id.proxyStopButton)
        vpnStartButton = findViewById(R.id.vpnStartButton)
        vpnStopButton = findViewById(R.id.vpnStopButton)
        vicohomeButton = findViewById(R.id.vicohomeButton)
        probeTokenPrefsButton = findViewById(R.id.probeTokenPrefsButton)
        copyTokenCandidatesButton = findViewById(R.id.copyTokenCandidatesButton)
        startButton = findViewById(R.id.startButton)
        stopButton = findViewById(R.id.stopButton)

        scanButton.setOnClickListener { scanCameras() }
        captureButton.setOnClickListener { captureBaseus() }
        proxyStartButton.setOnClickListener { startProxyCapture() }
        proxyStopButton.setOnClickListener { stopProxyCapture() }
        vpnStartButton.setOnClickListener { startVpnCapture() }
        vpnStopButton.setOnClickListener { stopVpnCapture() }
        vicohomeButton.setOnClickListener { syncVicohome() }
        probeTokenPrefsButton.setOnClickListener { probeBaseusPrefs() }
        copyTokenCandidatesButton.setOnClickListener { copyTokenCandidates() }
        startButton.setOnClickListener { startBridge() }
        stopButton.setOnClickListener { stopBridge() }
        copyFilteredLogsButton.setOnClickListener { copyLogs(filtered = true) }
        copyAllLogsButton.setOnClickListener { copyLogs(filtered = false) }

        vicohomeRegionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            VicohomeRegionChoice.entries.map { it.displayName },
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        logFilterSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            LogFilterPreset.entries.map { it.displayName },
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        vpnModeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            VpnCaptureMode.entries.map { it.displayName },
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        logFilterSpinner.setSelection(LogFilterPreset.ALL.ordinal)
        vpnModeSpinner.setSelection(VpnCaptureMode.DNS_ONLY.ordinal)
        logFilterSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>, view: android.view.View?, position: Int, id: Long) {
                renderLogs()
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>) = Unit
        }
        logSearchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderLogs()
            }
        })

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
            ContextCompat.startForegroundService(
                this,
                BaseusVpnCaptureService.startIntent(
                    this,
                    pendingVpnTargetIp,
                    selectedVpnMode(),
                ),
            )
        } else {
            scanStatusView.text = "VPN permission denied."
        }
    }

    private fun startVpnCapture() {
        scanStatusView.text = "Preparing VPN capture..."
        BridgeLogStore.info("Baseus VPN capture requested")
        val targetIp = baseusTargetsInput.text?.toString()
            .orEmpty()
            .split(',', ';', ' ', '\n', '\t')
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() }
            ?: "192.168.4.25"
        pendingVpnTargetIp = targetIp
        val intent = android.net.VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            ContextCompat.startForegroundService(this, BaseusVpnCaptureService.startIntent(this, targetIp, selectedVpnMode()))
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
        BridgeLogStore.info("Baseus cloud sync requested for $email")
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
                result.session?.let { VicohomeSessionStore.update(it) }
                runOnUiThread {
                    scanStatusView.text = result.message
                    renderVicohomeEntries()
                }
            } catch (exception: Exception) {
                BridgeLogStore.error("Baseus cloud sync failed: ${exception.stackTraceToString()}")
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
        renderLogs()
        renderCameraCandidates()
        renderVicohomeEntries()
        renderTokenHarvest()
        renderProxyCapture()
        renderVpnCapture()
    }

    private fun renderLogs() {
        val preset = LogFilterPreset.entries.getOrElse(logFilterSpinner.selectedItemPosition.coerceIn(0, LogFilterPreset.entries.lastIndex)) {
            LogFilterPreset.ALL
        }
        val query = LogQuery(
            preset = preset,
            searchText = logSearchInput.text?.toString().orEmpty(),
        )
        val logs = BridgeLogStore.exportEntries().filter { query.matches(it) }.takeLast(250)
        logView.text = logs.joinToString("\n") { it.formatLine() }.ifBlank { "No logs yet." }
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
        val session = VicohomeSessionStore.snapshot()
        val liveUrl = "${NetworkUtils.serverUrl(BridgeConfig.HTTP_PORT)}/live"
        vicohomeContainer.removeAllViews()

        vicohomeContainer.addView(TextView(this).apply {
            text = VicohomeDataStore.snapshotMessage().ifBlank { "No Baseus cloud data loaded yet." }
            setTextColor(0xFF94A3B8.toInt())
        })

        vicohomeContainer.addView(TextView(this).apply {
            text = if (session == null) {
                "Live viewer: sync Baseus cloud data first, then open $liveUrl on another device."
            } else {
                "Live viewer ready for ${session.region.label}. Open $liveUrl on another device."
            }
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
                vicohomeContainer.addView(Button(this).apply {
                    text = "Open live"
                    setOnClickListener {
                        openBaseusLive(device.serialNumber, device.ip)
                    }
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

    private fun renderTokenHarvest() {
        tokenHarvestStatusView.text = TokenHarvestStore.summary()
        val tokens = TokenHarvestStore.snapshot(20)
        tokenHarvestValue.text = if (tokens.isEmpty()) {
            "No token candidates yet."
        } else {
            tokens.joinToString("\n\n") { entry ->
                buildString {
                    append(entry.source)
                    if (entry.note.isNotBlank()) {
                        append(" • ")
                        append(entry.note)
                    }
                    append('\n')
                    append(entry.token)
                }
            }
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
            append("Mode: ")
            append(selectedVpnMode().displayName)
            append("\n")
            append("Open the Baseus app after starting VPN capture.")
        }
        vpnStartButton.isEnabled = !state.running
        vpnStopButton.isEnabled = state.running
    }

    private fun openBaseusLive(serialNumber: String, ipAddress: String) {
        val serverUrl = NetworkUtils.serverUrl(BridgeConfig.HTTP_PORT)
        val targetUrl = buildString {
            append(serverUrl)
            append("/live?serial=")
            append(Uri.encode(serialNumber))
            if (ipAddress.isNotBlank()) {
                append("&ip=")
                append(Uri.encode(ipAddress))
            }
        }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)))
            scanStatusView.text = "Opening live viewer for ${ipAddress.ifBlank { serialNumber }}"
        } catch (exception: Exception) {
            BridgeLogStore.error("Failed to open live viewer: ${exception.stackTraceToString()}")
            scanStatusView.text = "Unable to open live viewer: ${exception.message ?: "unknown error"}"
        }
    }

    private fun selectedVpnMode(): VpnCaptureMode {
        return VpnCaptureMode.entries.getOrElse(vpnModeSpinner.selectedItemPosition.coerceIn(0, VpnCaptureMode.entries.lastIndex)) {
            VpnCaptureMode.DNS_ONLY
        }
    }

    private fun copyLogs(filtered: Boolean) {
        val preset = LogFilterPreset.entries.getOrElse(logFilterSpinner.selectedItemPosition.coerceIn(0, LogFilterPreset.entries.lastIndex)) {
            LogFilterPreset.ALL
        }
        val query = LogQuery(
            preset = preset,
            searchText = logSearchInput.text?.toString().orEmpty(),
        )
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val label = if (filtered) "OpenMonitor filtered logs" else "OpenMonitor full logs"
        val text = if (filtered) {
            BridgeLogStore.exportText(query)
        } else {
            BridgeLogStore.exportText()
        }
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        val summary = if (filtered) "${preset.displayName}${query.searchText.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""}" else "full log"
        Toast.makeText(this, "Copied $summary", Toast.LENGTH_SHORT).show()
        BridgeLogStore.info("Log copy requested ($summary)")
    }

    private fun copyTokenCandidates() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val text = TokenHarvestStore.exportText(50)
        clipboard.setPrimaryClip(ClipData.newPlainText("OpenMonitor token candidates", text))
        Toast.makeText(this, "Copied token candidates", Toast.LENGTH_SHORT).show()
        BridgeLogStore.info("Token candidates copied to clipboard")
    }

    private fun probeBaseusPrefs() {
        probeTokenPrefsButton.isEnabled = false
        tokenHarvestStatusView.text = "Probing Baseus app prefs..."
        BridgeLogStore.info("Baseus prefs probe requested")
        Thread {
            try {
                val tokens = BaseusTokenProbe.probe { progress ->
                    BridgeLogStore.info(progress)
                    runOnUiThread {
                        tokenHarvestStatusView.text = progress
                    }
                }
                runOnUiThread {
                    tokenHarvestStatusView.text = if (tokens.isEmpty()) {
                        "Baseus prefs probe complete: no readable tokens found"
                    } else {
                        "Baseus prefs probe complete: found ${tokens.size} candidate(s)"
                    }
                    renderTokenHarvest()
                }
            } catch (exception: Exception) {
                BridgeLogStore.error("Baseus prefs probe failed: ${exception.stackTraceToString()}")
                runOnUiThread {
                    tokenHarvestStatusView.text = "Baseus prefs probe failed: ${exception.message ?: "unknown error"}"
                }
            } finally {
                runOnUiThread {
                    probeTokenPrefsButton.isEnabled = true
                }
            }
        }.start()
    }
}
