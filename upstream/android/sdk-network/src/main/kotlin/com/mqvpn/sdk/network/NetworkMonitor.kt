// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.sdk.network

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.getSystemService
import java.util.concurrent.ConcurrentHashMap

/**
 * Monitors WiFi / Cellular / Ethernet / Bluetooth availability via
 * ConnectivityManager.
 *
 * Normal internet paths must be validated. A cellular IMS path can be used
 * without INTERNET when it advertises NOT_RESTRICTED; reachability to the
 * configured server is checked later by the path socket itself.
 *
 * Android keeps a network connected only while a request is served by it,
 * and the default request is served by one network at a time. The monitor
 * therefore holds one request per transport while it runs: without them an
 * Ethernet adapter takes Wi-Fi down, and a Bluetooth tether never comes up
 * next to Wi-Fi. The holds only keep networks connected; paths still come
 * from the listening callback.
 *
 * Only selected transport types are used as paths and held while running.
 * Exclusions apply to the tunnel; snapshotPaths() still enumerates all eligible
 * existing networks for diagnostics.
 */
class NetworkMonitor(
    private val context: Context,
    private val holdBluetooth: Boolean = false,
    private val cellularPathEnabled: Boolean = true,
    private val wifiPathEnabled: Boolean = true,
    private val ethernetPathEnabled: Boolean = true,
    excludedSubscriptionIds: List<Int> = emptyList(),
    excludedInterfaces: List<String> = emptyList(),
) {

    private val cm = context.getSystemService<ConnectivityManager>()!!

    private val _activeNetworks = ConcurrentHashMap<Network, NetworkPath>()
    val activeNetworks: Map<Network, NetworkPath> get() = _activeNetworks

    // Every network the listener matches, active or not: a network dropped
    // with removeNetwork() is announced again from here.
    private val knownNetworks = ConcurrentHashMap<Network, NetworkPath>()
    private val knownCapabilities = ConcurrentHashMap<Network, NetworkCapabilities>()
    private val excludedSubscriptionIds = excludedSubscriptionIds.toSet()
    private val excludedInterfaces = excludedInterfaces.toSet()

    private var callback: ConnectivityManager.NetworkCallback? = null
    private val holds = mutableListOf<ConnectivityManager.NetworkCallback>()

    /** Current eligible networks, without holding or bringing up any network. */
    fun snapshotPaths(): List<NetworkPath> = try {
        cm.allNetworks.mapNotNull { network ->
            try {
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (isEligible(caps)) pathFor(network, caps) else null
            } catch (e: SecurityException) {
                Log.w(TAG, "Cannot inspect network $network for this app UID: ${e.message}")
                null
            }
        }
    } catch (e: SecurityException) {
        Log.w(TAG, "Cannot enumerate networks for this app UID: ${e.message}")
        emptyList()
    }

    fun start(listener: (NetworkEvent) -> Unit) {
        val request = NetworkRequest.Builder()
            // Keep the builder's NOT_RESTRICTED / NOT_VPN defaults. Removing
            // INTERNET is essential: the second SIM may expose only IMS.
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                /* capabilities may not be available yet; wait for onCapabilitiesChanged */
            }

            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities,
            ) {
                if (!isEligible(capabilities)) {
                    knownCapabilities.remove(network)
                    knownNetworks.remove(network)
                    _activeNetworks.remove(network)?.let { listener(NetworkEvent.Lost(it)) }
                    return
                }
                knownCapabilities[network] = capabilities
                val path = pathFor(network, capabilities)
                updateSelection(path)
            }

            // A dual-stack network can validate over IPv6 before DHCPv4
            // finishes; an IPv4-only server name resolves only once the IPv4
            // address arrives, and that changes the link properties, not the
            // capabilities. A path whose bind failed is retried from here too.
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                knownCapabilities[network]?.let { caps ->
                    updateSelection(pathFor(network, caps, linkProperties))
                }
            }

            override fun onLost(network: Network) {
                knownCapabilities.remove(network)
                knownNetworks.remove(network)
                val path = _activeNetworks.remove(network) ?: return
                Log.d(TAG, "Lost: $path")
                listener(NetworkEvent.Lost(path))
            }

            private fun updateSelection(path: NetworkPath) {
                val previouslyKnown = knownNetworks.put(path.network, path)
                val reason = selectionRejection(path)
                if (reason != null) {
                    val removed = _activeNetworks.remove(path.network)
                    if (previouslyKnown == null || removed != null) Log.i(TAG, "Skipping $path: $reason")
                    removed?.let { listener(NetworkEvent.Lost(it)) }
                    return
                }
                val isNew = _activeNetworks.put(path.network, path) == null
                if (isNew) {
                    Log.d(TAG, "Available: $path")
                    listener(NetworkEvent.Available(path))
                }
            }
        }

        // Each callback is recorded only once registered: when a registration
        // throws, stop() releases exactly the ones that went through.
        try {
            cm.registerNetworkCallback(request, cb)
        } catch (e: SecurityException) {
            // Some platform policies reject a listener broad enough to see
            // IMS. Keep ordinary validated paths working and report the loss.
            Log.w(TAG, "IMS network listener refused; only validated INTERNET paths can be monitored: ${e.message}")
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    .build(),
                cb,
            )
        }
        callback = cb

        for (transport in holdTransports(
            holdBluetooth, cellularPathEnabled, wifiPathEnabled, ethernetPathEnabled,
        )) {
            val hold = ConnectivityManager.NetworkCallback()
            cm.requestNetwork(
                NetworkRequest.Builder()
                    .addTransportType(transport)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                hold,
            )
            holds += hold
        }
    }

    /** Remove a network so the next capability or link-property update treats it as new. */
    fun removeNetwork(network: Network) {
        _activeNetworks.remove(network)
    }

    fun stop() {
        callback?.let { cm.unregisterNetworkCallback(it) }
        callback = null
        holds.forEach { cm.unregisterNetworkCallback(it) }
        holds.clear()
        _activeNetworks.clear()
        knownNetworks.clear()
        knownCapabilities.clear()
    }

    companion object {
        private const val TAG = "NetworkMonitor"

        /** Eligibility shared by the live monitor and the no-server diagnostic. */
        fun isEligible(caps: NetworkCapabilities): Boolean {
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
            val internet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val cellularIms = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)
            return internet || cellularIms
        }

        internal fun classifyTransport(caps: NetworkCapabilities): PathType = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> PathType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> PathType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> PathType.ETHERNET
            caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> PathType.BLUETOOTH
            else -> PathType.OTHER
        }

        /** Transports whose network is held while the monitor runs. */
        internal fun holdTransports(
            holdBluetooth: Boolean,
            cellularPathEnabled: Boolean = true,
            wifiPathEnabled: Boolean = true,
            ethernetPathEnabled: Boolean = true,
        ): List<Int> = buildList {
            if (wifiPathEnabled) add(NetworkCapabilities.TRANSPORT_WIFI)
            if (cellularPathEnabled) add(NetworkCapabilities.TRANSPORT_CELLULAR)
            if (ethernetPathEnabled) add(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (holdBluetooth) add(NetworkCapabilities.TRANSPORT_BLUETOOTH)
        }

        internal fun networkName(network: Network, type: PathType): String {
            // Network.getNetworkHandle() keeps the network id in its HIGH bits;
            // the low bits are a fixed magic value and collide for every path.
            val suffix = network.toString()
            return type.name.lowercase().take(14 - suffix.length) + "-$suffix"
        }
    }

    /** Null means this path is selected for the current VPN session. */
    internal fun selectionRejection(path: NetworkPath): String? {
        val transportEnabled = when (path.type) {
            PathType.CELLULAR -> cellularPathEnabled
            PathType.WIFI -> wifiPathEnabled
            PathType.ETHERNET -> ethernetPathEnabled
            PathType.BLUETOOTH -> holdBluetooth
            PathType.OTHER -> false
        }
        if (!transportEnabled) return "${path.type} disabled"
        if (path.type == PathType.CELLULAR && excludedSubscriptionIds.isNotEmpty()) {
            val subId = path.subscriptionId
                ?: return "subscription ID unavailable while SIM exclusions are configured"
            if (subId in excludedSubscriptionIds) return "subscription $subId excluded"
        }
        if (excludedInterfaces.isNotEmpty()) {
            val iface = path.interfaceName
                ?: return "interface name unavailable while interface exclusions are configured"
            if (iface in excludedInterfaces) return "interface $iface excluded"
        }
        return null
    }

    private fun pathFor(
        network: Network,
        caps: NetworkCapabilities,
        linkProperties: LinkProperties? = null,
    ): NetworkPath {
        val type = classifyTransport(caps)
        val subId = if (type == PathType.CELLULAR && Build.VERSION.SDK_INT >= 30) {
            try {
                (caps.networkSpecifier as? TelephonyNetworkSpecifier)?.subscriptionId
                    ?: if (Build.VERSION.SDK_INT >= 31) caps.subscriptionIds.singleOrNull() else null
            } catch (_: SecurityException) { null }
        } else null
        val info = if (subId != null &&
            context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        ) {
            try {
                context.getSystemService(SubscriptionManager::class.java)?.getActiveSubscriptionInfo(subId)
            } catch (e: SecurityException) {
                Log.w(TAG, "Subscription labels unavailable: ${e.message}")
                null
            }
        } else null
        val iface = if (linkProperties != null) linkProperties.interfaceName
            else try { cm.getLinkProperties(network)?.interfaceName } catch (_: SecurityException) { null }
        val slot = info?.simSlotIndex?.takeIf { it >= 0 }
        // The native path name is char[16]. Preserve the network id even when
        // subscription data is unavailable or redacted by Android.
        val suffix = network.toString()
        val name = when {
            type == PathType.CELLULAR && slot != null ->
                "sim${slot + 1}".take(14 - suffix.length) + "-$suffix"
            type == PathType.CELLULAR && subId != null ->
                "sub$subId".take(14 - suffix.length) + "-$suffix"
            else -> networkName(network, type)
        }
        val label = if (type == PathType.CELLULAR) {
            listOfNotNull(
                slot?.let { "SIM ${it + 1}" } ?: subId?.let { "Subscription $it" },
                info?.carrierName?.toString()?.takeIf { it.isNotBlank() } ?: "Cellular",
            ).joinToString(" | ")
        } else type.name.lowercase().replaceFirstChar { it.uppercase() }
        return NetworkPath(
            network = network,
            type = type,
            name = name,
            isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            subscriptionId = subId,
            displayName = "$label | ${iface ?: "interface unknown"} | net$network",
            interfaceName = iface,
            isIms = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS),
        )
    }
}
