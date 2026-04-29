package com.audiomirror.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.util.Log

class WifiConnector(private val context: Context) {

    private val wifiManager: WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var addedNetworkId: Int = -1

    var onConnected: ((network: Network?) -> Unit)? = null
    var onFailed: (() -> Unit)? = null

    fun enableWifi() {
        if (!wifiManager.isWifiEnabled) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = true
            }
            // Android 10+ এ programmatically wifi enable করা যায় না
            // User কে Settings এ নিয়ে যেতে হবে — কিন্তু hotspot connect এর সময়
            // requestNetwork() কাজ করে যদি WiFi সক্ষম থাকে
        }
    }

    fun connect(ssid: String, password: String) {
        Log.d(TAG, "Connecting to SSID: $ssid")
        enableWifi()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            connectModern(ssid, password)
        } else {
            connectLegacy(ssid, password)
        }
    }

    @Suppress("DEPRECATION")
    private fun connectLegacy(ssid: String, password: String) {
        try {
            // Remove any existing network with same SSID
            wifiManager.configuredNetworks?.forEach { config ->
                if (config.SSID == "\"$ssid\"") {
                    wifiManager.removeNetwork(config.networkId)
                }
            }

            val config = WifiConfiguration().apply {
                SSID = "\"$ssid\""
                preSharedKey = "\"$password\""
                allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP)
                allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP)
                allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP)
                allowedProtocols.set(WifiConfiguration.Protocol.RSN)
            }

            addedNetworkId = wifiManager.addNetwork(config)
            if (addedNetworkId != -1) {
                wifiManager.disconnect()
                wifiManager.enableNetwork(addedNetworkId, true)
                wifiManager.reconnect()
                Log.d(TAG, "Legacy connect initiated, networkId=$addedNetworkId")
                // Give it a moment then trigger callback
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    onConnected?.invoke(null)
                }, 3000)
            } else {
                Log.e(TAG, "Failed to add network")
                onFailed?.invoke()
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectLegacy error", e)
            onFailed?.invoke()
        }
    }

    @Suppress("NewApi")
    private fun connectModern(ssid: String, password: String) {
        try {
            // Clean up previous callback
            networkCallback?.let {
                try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
            }

            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .setWpa2Passphrase(password)
                .build()

            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()

            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "Network available: $network")
                    connectivityManager.bindProcessToNetwork(network)
                    onConnected?.invoke(network)
                }

                override fun onUnavailable() {
                    Log.e(TAG, "Network unavailable")
                    onFailed?.invoke()
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "Network lost")
                    connectivityManager.bindProcessToNetwork(null)
                }
            }

            networkCallback = callback
            connectivityManager.requestNetwork(request, callback)
            Log.d(TAG, "Modern connect requested for SSID=$ssid")
        } catch (e: Exception) {
            Log.e(TAG, "connectModern error", e)
            onFailed?.invoke()
        }
    }

    fun disconnect() {
        networkCallback?.let {
            try {
                connectivityManager.unregisterNetworkCallback(it)
                connectivityManager.bindProcessToNetwork(null)
            } catch (_: Exception) {}
            networkCallback = null
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && addedNetworkId != -1) {
            @Suppress("DEPRECATION")
            wifiManager.removeNetwork(addedNetworkId)
            addedNetworkId = -1
        }
    }

    companion object {
        private const val TAG = "WifiConnector"
    }
}
