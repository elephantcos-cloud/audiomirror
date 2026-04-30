package com.audiomirror.activity

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import com.google.android.material.tabs.TabLayout
import com.google.zxing.BarcodeFormat
import org.monora.android.codescanner.BarcodeEncoder
import org.monora.android.codescanner.CodeScanner
import org.monora.android.codescanner.CodeScannerView
import com.audiomirror.App
import com.audiomirror.AudioMode
import com.audiomirror.R
import com.audiomirror.service.AudioPlaybackService
import com.audiomirror.service.AudioStreamService
import com.audiomirror.util.HotspotManager
import com.audiomirror.util.WifiConnector
import java.net.Inet4Address
import java.net.NetworkInterface

class AudioMirrorActivity : AppCompatActivity() {

    private lateinit var serverLayout: LinearLayout
    private lateinit var qrImageView: ImageView
    private lateinit var serverStatusText: TextView
    private lateinit var startStopBtn: Button
    private lateinit var hotspotStatusText: TextView

    private lateinit var clientLayout: FrameLayout
    private lateinit var scannerView: CodeScannerView
    private lateinit var listeningLayout: LinearLayout
    private lateinit var listeningStatusText: TextView
    private lateinit var disconnectBtn: Button
    private lateinit var scanInstruction: TextView

    private val hotspotManager by lazy { HotspotManager(this) }
    private val wifiConnector by lazy { WifiConnector(this) }

    private var codeScanner: CodeScanner? = null
    private var currentHotspotSsid = ""
    private var currentHotspotPass = ""
    private var currentMode = AudioMode.MIC_ONLY
    private var currentTheme: String = "dark"
    private var lastQrContent = ""

    // Derived state — always read from service/prefs, not stored locally
    private val isServerRunning get() = AudioStreamService.isServiceRunning
    private val isClientRunning get() = AudioPlaybackService.isServiceRunning

    private val mediaProjectionManager by lazy {
        getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    // ── Permission launchers ──────────────────────────────────────────────────

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants[Manifest.permission.RECORD_AUDIO] == true) {
            if (needsMediaProjection()) requestProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
            else startHotspotAndStream(null, -1)
        } else toast(R.string.perm_audio_required)
    }

    private val requestProjection = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null)
            startHotspotAndStream(result.data, result.resultCode)
        else toast(R.string.perm_projection_denied)
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) codeScanner?.startPreview()
        else toast(R.string.perm_camera_required)
    }

    // ── Receivers ─────────────────────────────────────────────────────────────

    private val streamReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(AudioStreamService.EXTRA_STATUS) ?: return
            runOnUiThread {
                when (status) {
                    AudioStreamService.STATUS_WAITING -> {
                        serverStatusText.text = getString(R.string.status_waiting_listener)
                        startStopBtn.text = getString(R.string.stop)
                        // Restore QR if available
                        restoreQrIfNeeded()
                    }
                    AudioStreamService.STATUS_CONNECTED ->
                        serverStatusText.text = getString(R.string.status_listener_connected)
                    AudioStreamService.STATUS_STOPPED -> {
                        serverStatusText.text = getString(R.string.status_not_streaming)
                        startStopBtn.text = getString(R.string.start_streaming)
                        qrImageView.visibility = View.GONE
                        hotspotStatusText.visibility = View.GONE
                        startStopBtn.isEnabled = true
                    }
                }
            }
        }
    }

    private val playReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getStringExtra(AudioPlaybackService.EXTRA_STATUS) ?: return
            runOnUiThread {
                when (status) {
                    AudioPlaybackService.STATUS_CONNECTING ->
                        listeningStatusText.text = getString(R.string.status_connecting)
                    AudioPlaybackService.STATUS_CONNECTED -> {
                        showListeningState(true)
                        listeningStatusText.text = getString(R.string.status_now_listening)
                    }
                    AudioPlaybackService.STATUS_STOPPED -> {
                        showListeningState(false)
                        wifiConnector.disconnect()
                    }
                }
            }
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        currentTheme = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this).getString("app_theme", "dark") ?: "dark"
        App.applyTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_mirror)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))

        serverLayout = findViewById(R.id.serverLayout)
        qrImageView = findViewById(R.id.qrImage)
        serverStatusText = findViewById(R.id.serverStatusText)
        startStopBtn = findViewById(R.id.startStopBtn)
        hotspotStatusText = findViewById(R.id.hotspotStatusText)
        clientLayout = findViewById(R.id.clientLayout)
        scannerView = findViewById(R.id.scannerView)
        listeningLayout = findViewById(R.id.listeningLayout)
        listeningStatusText = findViewById(R.id.listeningStatusText)
        disconnectBtn = findViewById(R.id.disconnectBtn)
        scanInstruction = findViewById(R.id.scanInstruction)

        findViewById<TabLayout>(R.id.tabLayout).addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (tab.position == 0) showServerMode() else showClientMode()
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })

        startStopBtn.setOnClickListener {
            if (isServerRunning) stopStreaming() else checkPermissionsAndStart()
        }
        disconnectBtn.setOnClickListener { stopListening() }

        codeScanner = CodeScanner(this, scannerView, { result ->
            runOnUiThread { handleQrScan(result.text) }
        })

        loadMode()
        showServerMode()
    }

    override fun onResume() {
        super.onResume()
        // Detect theme change — if user changed theme in Settings, recreate this activity too
        val newTheme = androidx.preference.PreferenceManager
            .getDefaultSharedPreferences(this).getString("app_theme", "dark") ?: "dark"
        if (newTheme != currentTheme) {
            currentTheme = newTheme
            App.applyTheme(this)
            recreate()
            return
        }
        App.applyTheme(this)
        registerReceiver(streamReceiver, IntentFilter(AudioStreamService.ACTION_STATUS))
        registerReceiver(playReceiver, IntentFilter(AudioPlaybackService.ACTION_STATUS))
        loadMode()
        syncUiWithServiceState()
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(streamReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(playReceiver) } catch (_: Exception) {}
        codeScanner?.releaseResources()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_settings) {
            // Pass whether this is listener phone (settings should be read-only)
            val intent = Intent(this, SettingsActivity::class.java).apply {
                putExtra(SettingsActivity.EXTRA_IS_LISTENER, isClientRunning)
                putExtra(SettingsActivity.EXTRA_IS_STREAMING, isServerRunning)
            }
            startActivity(intent)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    // ── State sync ─────────────────────────────────────────────────────────────

    private fun syncUiWithServiceState() {
        if (isServerRunning) {
            // Service running but UI might show wrong state
            startStopBtn.text = getString(R.string.stop)
            serverStatusText.text = getString(R.string.status_waiting_listener)
            restoreQrIfNeeded()
        } else {
            startStopBtn.text = getString(R.string.start_streaming)
            serverStatusText.text = getString(R.string.status_not_streaming)
        }

        if (isClientRunning) {
            if (clientLayout.visibility == View.VISIBLE) showListeningState(true)
        } else {
            if (clientLayout.visibility == View.VISIBLE && !isClientRunning) {
                showListeningState(false)
            }
        }
    }

    private fun restoreQrIfNeeded() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val qr = prefs.getString(AudioStreamService.PREF_QR_CONTENT, "") ?: ""
        if (qr.isNotEmpty() && qrImageView.visibility != View.VISIBLE) {
            try {
                val size = resources.getDimensionPixelSize(R.dimen.qr_code_size)
                val encoder = BarcodeEncoder()
                val matrix = encoder.encode(qr, BarcodeFormat.QR_CODE, size, size)
                qrImageView.setImageBitmap(encoder.createBitmap(matrix))
                qrImageView.visibility = View.VISIBLE
            } catch (_: Exception) {}
        }
    }

    // ── UI ─────────────────────────────────────────────────────────────────────

    private fun showServerMode() {
        serverLayout.visibility = View.VISIBLE
        clientLayout.visibility = View.GONE
        codeScanner?.releaseResources()
        syncUiWithServiceState()
    }

    private fun showClientMode() {
        serverLayout.visibility = View.GONE
        clientLayout.visibility = View.VISIBLE
        if (isClientRunning) showListeningState(true)
        else { showListeningState(false); resumeScanner() }
    }

    private fun showListeningState(listening: Boolean) {
        if (listening) {
            scannerView.visibility = View.GONE
            scanInstruction.visibility = View.GONE
            listeningLayout.visibility = View.VISIBLE
            codeScanner?.releaseResources()
        } else {
            scannerView.visibility = View.VISIBLE
            scanInstruction.visibility = View.VISIBLE
            listeningLayout.visibility = View.GONE
            resumeScanner()
        }
    }

    private fun resumeScanner() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
            codeScanner?.startPreview()
        else requestCamera.launch(Manifest.permission.CAMERA)
    }

    private fun loadMode() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        currentMode = AudioMode.fromKey(prefs.getString("audio_mode", AudioMode.MIC_ONLY.key))
    }

    // ── Stream ─────────────────────────────────────────────────────────────────

    private fun checkPermissionsAndStart() {
        if (isServerRunning) return  // prevent double-start crash
        loadMode()
        val permsNeeded = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            permsNeeded.add(Manifest.permission.RECORD_AUDIO)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            permsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)

        when {
            permsNeeded.isNotEmpty() -> requestPermissions.launch(permsNeeded.toTypedArray())
            needsMediaProjection() -> requestProjection.launch(mediaProjectionManager.createScreenCaptureIntent())
            else -> startHotspotAndStream(null, -1)
        }
    }

    private fun needsMediaProjection() =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                (currentMode == AudioMode.DEVICE_ONLY || currentMode == AudioMode.DEVICE_AND_MIC)

    private fun startHotspotAndStream(projectionData: Intent?, resultCode: Int) {
        startStopBtn.isEnabled = false
        serverStatusText.text = getString(R.string.status_starting_hotspot)
        hotspotStatusText.text = getString(R.string.status_hotspot_starting)
        hotspotStatusText.visibility = View.VISIBLE

        hotspotManager.onStarted = { ssid, password ->
            currentHotspotSsid = ssid
            currentHotspotPass = password
            runOnUiThread {
                hotspotStatusText.text = getString(R.string.status_hotspot_on, ssid)
            }
            Handler(Looper.getMainLooper()).postDelayed({
                val ip = getLocalIpAddress()
                val qrContent = "audiomirror;$ssid;$password;$ip;${AudioStreamService.PORT};${currentMode.key}"
                lastQrContent = qrContent
                showQrCode(qrContent)
                launchStreamService(projectionData, resultCode, qrContent)
            }, 1500)
        }

        hotspotManager.onFailed = {
            runOnUiThread {
                hotspotStatusText.text = getString(R.string.status_hotspot_failed)
                val ip = getLocalIpAddress()
                val qrContent = "audiomirror;;;$ip;${AudioStreamService.PORT};${currentMode.key}"
                lastQrContent = qrContent
                showQrCode(qrContent)
                launchStreamService(projectionData, resultCode, qrContent)
            }
        }

        hotspotManager.start()
    }

    private fun launchStreamService(projectionData: Intent?, resultCode: Int, qrContent: String) {
        val intent = Intent(this, AudioStreamService::class.java).apply {
            putExtra(AudioStreamService.EXTRA_MODE, currentMode.key)
            putExtra(AudioStreamService.EXTRA_QR_CONTENT, qrContent)
            if (projectionData != null && resultCode != -1) {
                putExtra(AudioStreamService.EXTRA_RESULT_CODE, resultCode)
                putExtra(AudioStreamService.EXTRA_DATA, projectionData)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
        startStopBtn.isEnabled = true
        startStopBtn.text = getString(R.string.stop)
    }

    private fun stopStreaming() {
        startService(Intent(this, AudioStreamService::class.java).apply { action = AudioStreamService.ACTION_STOP })
        serverStatusText.text = getString(R.string.status_not_streaming)
        startStopBtn.text = getString(R.string.start_streaming)
        qrImageView.visibility = View.GONE
        hotspotStatusText.visibility = View.GONE
        hotspotManager.stop()
    }

    private fun stopListening() {
        startService(Intent(this, AudioPlaybackService::class.java).apply { action = AudioPlaybackService.ACTION_STOP })
        showListeningState(false)
        wifiConnector.disconnect()
    }

    // ── QR ──────────────────────────────────────────────────────────────────────

    private fun showQrCode(content: String) {
        runOnUiThread {
            try {
                val size = resources.getDimensionPixelSize(R.dimen.qr_code_size)
                val encoder = BarcodeEncoder()
                val matrix = encoder.encode(content, BarcodeFormat.QR_CODE, size, size)
                qrImageView.setImageBitmap(encoder.createBitmap(matrix))
                qrImageView.visibility = View.VISIBLE
            } catch (e: Exception) { toast(R.string.qr_error) }
        }
    }

    private fun handleQrScan(code: String) {
        codeScanner?.releaseResources()
        try {
            val parts = code.split(";")
            if (parts.size >= 5 && parts[0] == "audiomirror") {
                val ssid = parts[1]
                val password = parts[2]
                val ip = parts[3]
                val port = parts[4].toInt()
                val mode = if (parts.size >= 6) parts[5] else AudioMode.MIC_ONLY.key

                // Mark this phone as listener — disable settings
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                    .putBoolean("is_listener_phone", true).apply()

                listeningStatusText.text = getString(R.string.status_connecting_hotspot)
                showListeningState(true)
                connectAndListen(ssid, password, ip, port, mode)
            } else {
                toast(R.string.invalid_qr)
                codeScanner?.startPreview()
            }
        } catch (e: Exception) {
            toast(R.string.invalid_qr)
            codeScanner?.startPreview()
        }
    }

    private fun connectAndListen(ssid: String, password: String, ip: String, port: Int, mode: String) {
        wifiConnector.enableWifi()

        if (ssid.isNotEmpty() && password.isNotEmpty()) {
            wifiConnector.onConnected = {
                runOnUiThread { listeningStatusText.text = getString(R.string.status_hotspot_connected) }
                Handler(Looper.getMainLooper()).postDelayed({ startListeningService(ip, port, mode) }, 1500)
            }
            wifiConnector.onFailed = {
                runOnUiThread { startListeningService(ip, port, mode) }
            }
            wifiConnector.connect(ssid, password)
        } else {
            startListeningService(ip, port, mode)
        }
    }

    private fun startListeningService(ip: String, port: Int, mode: String) {
        val intent = Intent(this, AudioPlaybackService::class.java).apply {
            putExtra(AudioPlaybackService.EXTRA_HOST, ip)
            putExtra(AudioPlaybackService.EXTRA_PORT, port)
            putExtra(AudioPlaybackService.EXTRA_MODE, mode)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
        else startService(intent)
    }

    // ── IP ──────────────────────────────────────────────────────────────────────

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "192.168.43.1"
            for (iface in interfaces.iterator()) {
                for (addr in iface.inetAddresses.iterator()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.43.")) return ip
                    }
                }
            }
            for (iface in NetworkInterface.getNetworkInterfaces().iterator()) {
                for (addr in iface.inetAddresses.iterator()) {
                    if (!addr.isLoopbackAddress && addr is Inet4Address)
                        return addr.hostAddress ?: continue
                }
            }
        } catch (e: Exception) { e.printStackTrace() }
        return "192.168.43.1"
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_SHORT).show()
}
