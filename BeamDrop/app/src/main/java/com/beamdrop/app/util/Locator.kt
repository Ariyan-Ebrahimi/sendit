package com.beamdrop.app.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** Where the phone is now, from GPS or the network (no Google services needed). */
object Locator {

    fun isOn(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /** A fresh fix (up to [timeoutMs]); falls back to a recent last-known one. Null if nothing. */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, timeoutMs: Long = 20_000): Location? = withContext(Dispatchers.Main) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
            runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
        }
        val recent = providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 2 * 60_000 }
            .minByOrNull { it.accuracy }
        if (recent != null && recent.accuracy < 100) return@withContext recent
        if (providers.isEmpty()) return@withContext recent
        val fresh = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        if (cont.isActive) {
                            runCatching { lm.removeUpdates(this) }
                            cont.resume(location)
                        }
                    }
                    @Deprecated("old API")
                    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) {}
                }
                providers.forEach { runCatching { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) } }
                cont.invokeOnCancellation { runCatching { lm.removeUpdates(listener) } }
            }
        }
        fresh ?: recent ?: providers.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }
}
