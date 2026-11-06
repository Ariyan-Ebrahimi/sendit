package com.beamdrop.app.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.beamdrop.app.data.PeerDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Advertises a running send session and discovers other BeamDrop senders on the
 * same LAN using Android's built-in DNS-SD (NsdManager). No location permission
 * is required for service discovery on modern Android versions.
 */
class NsdHelper(context: Context) {

    private val nsdManager =
        context.applicationContext.getSystemService(Context.NSD_SERVICE) as? NsdManager

    private val _peers = MutableStateFlow<List<PeerDevice>>(emptyList())
    val peers: StateFlow<List<PeerDevice>> = _peers.asStateFlow()

    private val lock = Any()
    private val peersByService = linkedMapOf<String, PeerDevice>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var ownCode: String? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var discovering = false

    fun advertise(name: String, code: String, port: Int, token: String) {
        val manager = nsdManager ?: return
        stopAdvertising()
        val safeName = name.take(50)
        val safeCode = code.take(12)
        val safeToken = token.take(32)
        val info = NsdServiceInfo().apply {
            // A short suffix keeps re-registration reliable while the old service shuts down.
            serviceName = "Sendit-$safeCode-${System.currentTimeMillis() % 10_000}"
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("name", safeName)
            setAttribute("code", safeCode)
            setAttribute("token", safeToken)
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
        }
        registrationListener = listener
        runCatching {
            manager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun stopAdvertising() {
        val manager = nsdManager ?: return
        val listener = registrationListener ?: return
        registrationListener = null
        runCatching { manager.unregisterService(listener) }
    }

    fun startDiscovery(ownCode: String?) {
        this.ownCode = ownCode
        if (discovering || nsdManager == null) return
        val manager = nsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false
                runCatching { manager.stopServiceDiscovery(this) }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                discovering = false
            }

            override fun onDiscoveryStarted(serviceType: String) {
                discovering = true
            }

            override fun onDiscoveryStopped(serviceType: String) {
                discovering = false
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains(SERVICE_TYPE.trimEnd('.'))) return
                enqueueResolve(serviceInfo)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(lock) {
                    if (peersByService.remove(serviceInfo.serviceName) != null) {
                        _peers.value = peersByService.values.toList()
                    }
                }
            }
        }
        discoveryListener = listener
        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
            discovering = true
        }.onFailure {
            discovering = false
            discoveryListener = null
        }
    }

    fun stopDiscovery() {
        val manager = nsdManager ?: return
        val listener = discoveryListener
        discoveryListener = null
        discovering = false
        if (listener != null) {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        synchronized(lock) {
            resolveQueue.clear()
            resolving = false
            peersByService.clear()
            _peers.value = emptyList()
        }
    }

    private fun enqueueResolve(info: NsdServiceInfo) {
        synchronized(lock) {
            if (info.serviceName.isBlank()) return
            if (peersByService.containsKey(info.serviceName)) return
            if (resolveQueue.any { it.serviceName == info.serviceName }) return
            resolveQueue.addLast(info)
            if (!resolving) {
                resolving = true
                resolveNextLocked()
            }
        }
    }

    private fun resolveNextLocked() {
        val manager = nsdManager
        val next = resolveQueue.removeFirstOrNull()
        if (manager == null || next == null) {
            resolving = false
            return
        }
        val listener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                handleResolved(serviceInfo)
                synchronized(lock) { resolveNextLocked() }
            }

            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                synchronized(lock) { resolveNextLocked() }
            }
        }
        runCatching {
            manager.resolveService(next, listener)
        }.onFailure {
            synchronized(lock) { resolveNextLocked() }
        }
    }

    private fun handleResolved(info: NsdServiceInfo) {
        val host = info.host?.hostAddress ?: return
        val attributes = info.attributes ?: return
        val name = attributes["name"]?.toString(Charsets.UTF_8)
            ?.takeIf { it.isNotBlank() }
            ?: info.serviceName
        val code = attributes["code"]?.toString(Charsets.UTF_8).orEmpty()
        val token = attributes["token"]?.toString(Charsets.UTF_8).orEmpty()
        if (token.isBlank()) return
        if (ownCode != null && code == ownCode) return

        val peer = PeerDevice(
            serviceName = info.serviceName,
            name = name,
            host = host,
            port = info.port,
            token = token,
            code = code
        )
        synchronized(lock) {
            val stale = peersByService.filterValues { it.code == code && it.serviceName != peer.serviceName }
            stale.keys.forEach { peersByService.remove(it) }
            peersByService[peer.serviceName] = peer
            _peers.value = peersByService.values.sortedBy { it.name.lowercase() }
        }
    }

    companion object {
        private const val SERVICE_TYPE = "_sendit._tcp."
    }
}
