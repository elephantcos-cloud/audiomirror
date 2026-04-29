package com.audiomirror.util

import android.content.Context
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi

class HotspotManager(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var hotspotReservation: Any? = null // WifiManager.LocalOnlyHotspotReservation

    var onStarted: ((ssid: String, password: String) -> Unit)? = null
    var onStopped: (() -> Unit)? = null
    var onFailed: (() -> Unit)? = null

    fun start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startModern()
        } else {
            startLegacy()
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            stopModern()
        } else {
            stopLegacy()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startModern() {
        try {
            wifiManager.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    hotspotReservation = reservation
                    val config = reservation.wifiConfiguration
                    val ssid = config?.SSID ?: generateSsid()
                    val password = config?.preSharedKey ?: generatePassword()
                    Log.d(TAG, "Hotspot started: SSID=$ssid")
                    onStarted?.invoke(ssid, password)
                }

                override fun onStopped() {
                    hotspotReservation = null
                    Log.d(TAG, "Hotspot stopped")
                    onStopped?.invoke()
                }

                override fun onFailed(reason: Int) {
                    Log.e(TAG, "Hotspot failed: reason=$reason")
                    onFailed?.invoke()
                }
            }, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.e(TAG, "startModern error", e)
            onFailed?.invoke()
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun stopModern() {
        try {
            @Suppress("UNCHECKED_CAST")
            (hotspotReservation as? WifiManager.LocalOnlyHotspotReservation)?.close()
            hotspotReservation = null
        } catch (e: Exception) {
            Log.e(TAG, "stopModern error", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun startLegacy() {
        try {
            val ssid = generateSsid()
            val password = generatePassword()
            val config = WifiConfiguration().apply {
                SSID = ssid
                preSharedKey = password
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
            }
            // disable wifi first
            wifiManager.isWifiEnabled = false

            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.java
            )
            val result = method.invoke(wifiManager, config, true) as Boolean
            if (result) {
                Log.d(TAG, "Legacy hotspot started: SSID=$ssid")
                onStarted?.invoke(ssid, password)
            } else {
                onFailed?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "startLegacy error", e)
            onFailed?.invoke()
        }
    }

    @Suppress("DEPRECATION")
    private fun stopLegacy() {
        try {
            val method = wifiManager.javaClass.getMethod(
                "setWifiApEnabled",
                WifiConfiguration::class.java,
                Boolean::class.java
            )
            method.invoke(wifiManager, null, false)
        } catch (e: Exception) {
            Log.e(TAG, "stopLegacy error", e)
        }
    }

    private fun generateSsid() = "AudioMirror_${(1000..9999).random()}"
    private fun generatePassword() = (100000..999999).random().toString()

    companion object {
        private const val TAG = "HotspotManager"
    }
}
