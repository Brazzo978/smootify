// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.sdk.network

import android.net.Network

/**
 * Transport type classification for a network path.
 */
enum class PathType {
    WIFI,
    CELLULAR,
    ETHERNET,
    BLUETOOTH,
    OTHER,
}

/**
 * Represents a usable network path for VPN traffic.
 */
data class NetworkPath(
    val network: Network,
    val type: PathType,
    val name: String,
    val isMetered: Boolean,
    /** Best-effort identity; Android may redact the network's subscription specifier. */
    val subscriptionId: Int? = null,
    val displayName: String = name,
    val interfaceName: String? = null,
    val isIms: Boolean = false,
)
