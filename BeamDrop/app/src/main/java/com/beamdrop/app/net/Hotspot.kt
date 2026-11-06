package com.beamdrop.app.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/** A phone-made Wi-Fi network for sending with no router and no internet. */
data class HotspotInfo(val ssid: String, val password: String, val wpa3: Boolean, val ip: String)

/**
 * Sender side: starts a "local-only hotspot" (no internet is shared) so a nearby phone can join it.
 * Receiver side: joins that network from the QR code and routes the app's traffic through it.
 */
class Hotspot(private val context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var joinCallback: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("MissingPermission")
    fun start(onReady: (HotspotInfo) -> Unit, onError: (String) -> Unit) {
        stop()
        val before = interfaceIps()
        runCatching {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    val (ssid, pass, wpa3) = credentials(res)
                    if (ssid.isNullOrBlank()) {
                        onError("This phone did not share its hotspot name.")
                        return
                    }
                    // The hotspot's address shows up a moment after it starts.
                    val handler = Handler(Looper.getMainLooper())
                    var tries = 0
                    val look = object : Runnable {
                        override fun run() {
                            val ip = (interfaceIps() - before).firstOrNull() ?: interfaceIps().firstOrNull { it.startsWith("192.168.") }
                            if (ip != null) onReady(HotspotInfo(ssid, pass.orEmpty(), wpa3, ip))
                            else if (++tries < 20) handler.postDelayed(this, 250)
                            else onError("The hotspot started but has no address. Try again.")
                        }
                    }
                    handler.post(look)
                }

                override fun onFailed(reason: Int) {
                    onError(
                        if (reason == ERROR_INCOMPATIBLE_MODE) "Turn off your phone's normal hotspot first."
                        else "Couldn't make a hotspot on this phone."
                    )
                }

                override fun onStopped() { reservation = null }
            }, Handler(Looper.getMainLooper()))
        }.onFailure { onError(if (it is SecurityException) "Sendit needs the Nearby devices / location permission." else "Couldn't make a hotspot on this phone.") }
    }

    fun stop() {
        runCatching { reservation?.close() }
        reservation = null
    }

    @Suppress("DEPRECATION")
    private fun credentials(res: WifiManager.LocalOnlyHotspotReservation): Triple<String?, String?, Boolean> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val c = res.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else c.ssid
            Triple(ssid, c.passphrase, c.securityType == 3 /* WPA3_SAE */)
        } else {
            val c = res.wifiConfiguration
            Triple(c?.SSID?.trim('"'), c?.preSharedKey?.trim('"'), false)
        }

    /** IPv4 addresses of Wi-Fi-like interfaces (wlan, swlan, ap...). */
    private fun interfaceIps(): List<String> = runCatching {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .filter { it.isUp && !it.isLoopback && Regex("^(wlan|swlan|ap|softap)").containsMatchIn(it.name.lowercase()) }
            .flatMap { i -> Collections.list(i.inetAddresses).filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress } }
            .filterNot { it.startsWith("169.254.") }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------ receiver

    /** Joins the sender's hotspot (Android asks the user once), then [onJoined]. Android 10+ only. */
    fun join(ssid: String, password: String, wpa3: Boolean, onJoined: () -> Unit, onError: (String) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            onError("Join the Wi-Fi \"$ssid\" (password: $password) in your phone's settings, then scan again.")
            return
        }
        leave()
        val spec = WifiNetworkSpecifier.Builder().setSsid(ssid).apply {
            if (password.isNotEmpty()) { if (wpa3) setWpa3Passphrase(password) else setWpa2Passphrase(password) }
        }.build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val main = Handler(Looper.getMainLooper())
        val cb = object : ConnectivityManager.NetworkCallback() {
            var done = false
            override fun onAvailable(network: Network) {
                connectivity.bindProcessToNetwork(network)
                if (!done) { done = true; main.post(onJoined) }
            }
            override fun onUnavailable() { main.post { onError("Couldn't join the sender's Wi-Fi.") } }
        }
        joinCallback = cb
        runCatching { connectivity.requestNetwork(request, cb, 60_000) }
            .onFailure { onError("Couldn't join the sender's Wi-Fi.") }
    }

    /** Back to the normal network (after receiving). */
    fun leave() {
        val cb = joinCallback ?: return
        joinCallback = null
        runCatching { connectivity.bindProcessToNetwork(null) }
        runCatching { connectivity.unregisterNetworkCallback(cb) }
    }

    val joined: Boolean get() = joinCallback != null

    companion object {
        fun qrText(info: HotspotInfo, url: String): String =
            Uri.Builder().scheme("sendit-wifi").authority("join")
                .appendQueryParameter("s", info.ssid)
                .appendQueryParameter("p", info.password)
                .appendQueryParameter("t", if (info.wpa3) "3" else "2")
                .appendQueryParameter("u", url)
                .build().toString()
    }
}
