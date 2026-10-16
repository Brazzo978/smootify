// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.network.NetworkMonitor

private val PATH_STATUS_NAMES = mapOf(
    0 to "Pending",
    1 to "Active",
    2 to "Degraded",
    3 to "Standby",
    4 to "Closed",
)

fun pathStatusName(status: Int): String = PATH_STATUS_NAMES[status] ?: "Unknown"

/** Shared status colors also used by the event list. */
fun pathStatusColor(status: Int): Color = when (status) {
    1 -> Color(0xFF27866F)
    0, 3 -> Color(0xFFD69719)
    2 -> Color(0xFFC17816)
    4 -> Color.Gray
    else -> Color(0xFFBA3237)
}

@Composable
fun PathCard(path: PathInfo) {
    val icon = when {
        path.iface.startsWith("wifi") || path.iface.startsWith("wlan") -> Icons.Default.Wifi
        path.iface.startsWith("sim") || path.iface.startsWith("sub") ||
            path.iface.startsWith("cellular") || path.iface.startsWith("rmnet") ||
            path.iface.startsWith("ccmni") -> Icons.Default.SignalCellularAlt
        path.iface.startsWith("bluetooth") -> Icons.Default.Bluetooth
        else -> Icons.Default.Cable
    }
    val context = LocalContext.current
    val networkPath = remember(path.iface, path.status) {
        runCatching {
            NetworkMonitor(context).snapshotPaths().firstOrNull { it.name == path.iface }
        }.getOrNull()
    }
    val displayLabel = networkPath?.displayName ?: path.iface
    val statusLabel = when (path.status) {
        0 -> "Pending"
        1 -> "Active"
        2 -> "Degraded quality"
        3 -> "On standby"
        4 -> "Closed"
        else -> "Unknown status"
    }

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(displayLabel, style = MaterialTheme.typography.titleSmall)
                Text(statusLabel, style = MaterialTheme.typography.labelMedium,
                    color = pathStatusColor(path.status))
                Text("RTT ${path.srttMs} ms · sent ${formatPathBytes(path.bytesTx)} · received ${formatPathBytes(path.bytesRx)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun formatPathBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
