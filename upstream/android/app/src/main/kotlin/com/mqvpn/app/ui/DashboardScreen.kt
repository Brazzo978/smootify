// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.core.model.PathInfo
import com.mqvpn.sdk.core.model.ReorderStats
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: MqvpnViewModel,
    onOpenSettings: () -> Unit,
    onOpenIperf: () -> Unit,
    onOpenServer: () -> Unit,
    onOpenRadio: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenCompatibility: () -> Unit,
    onOpenConnections: () -> Unit = onOpenSettings,
    embedded: Boolean = false,
    homeStatus: HomeStatusViewModel = hiltViewModel(),
) {
    val state by viewModel.vpnState.collectAsStateWithLifecycle()
    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val paths by viewModel.paths.collectAsStateWithLifecycle()
    val reorderStats by viewModel.reorderStats.collectAsStateWithLifecycle()
    val connectPending by viewModel.connectPending.collectAsStateWithLifecycle()
    val connectError by viewModel.connectError.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val bandwidthHistory by viewModel.bandwidthHistory.collectAsStateWithLifecycle()
    val settings by homeStatus.settings.collectAsStateWithLifecycle()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) viewModel.connectWithSavedSettings()
    }
    val startConnectFlow = {
        val prepareIntent = viewModel.prepareVpn()
        if (prepareIntent != null) vpnPermissionLauncher.launch(prepareIntent)
        else viewModel.connectWithSavedSettings()
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> startConnectFlow() }

    val configured = settings?.isValid() == true
    val isConnected = state is MqvpnState.Connected
    val status = when (state) {
        is MqvpnState.Connected -> "Connected"
        is MqvpnState.Connecting -> "Connecting"
        is MqvpnState.Reconnecting -> "Reconnecting"
        is MqvpnState.Error -> "Connection interrupted"
        MqvpnState.Disconnected -> "Disconnected"
    }
    val statusDetail = when (val current = state) {
        is MqvpnState.Connected -> "Traffic is flowing through MQVPN."
        is MqvpnState.Connecting -> "Waiting for the tunnel to connect."
        is MqvpnState.Reconnecting -> "Retrying in ${current.info.delaySec} s."
        is MqvpnState.Error -> current.error.message ?: "Check the configuration and try again."
        MqvpnState.Disconnected -> "Ready to connect."
    }

    Scaffold(topBar = {
        if (!embedded) TopAppBar(title = { Text("MQVPN") }, actions = {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        })
    }) { inset ->
        Column(
            Modifier.fillMaxSize().padding(inset).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Your connection", style = MaterialTheme.typography.headlineSmall)
                Text("Tunnel and path status", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("VPN MQVPN", style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                        Text(status, style = MaterialTheme.typography.headlineSmall)
                        Text(statusDetail, style = MaterialTheme.typography.bodyMedium,
                            color = if (state is MqvpnState.Error) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (settings != null && !configured && state !is MqvpnState.Connected) {
                        Text("Set up the server before connecting.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error)
                    }
                    Button(
                        onClick = {
                            when (state) {
                                is MqvpnState.Connected, is MqvpnState.Reconnecting -> viewModel.disconnect()
                                is MqvpnState.Disconnected, is MqvpnState.Error -> {
                                    if (!configured) onOpenSetup()
                                    else {
                                        val needsNotificationPermission =
                                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                                ContextCompat.checkSelfPermission(
                                                    context, Manifest.permission.POST_NOTIFICATIONS
                                                ) != PackageManager.PERMISSION_GRANTED
                                        if (needsNotificationPermission)
                                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        else startConnectFlow()
                                    }
                                }
                                else -> Unit
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = (settings != null || state is MqvpnState.Connected || state is MqvpnState.Reconnecting) &&
                            state !is MqvpnState.Connecting && !connectPending,
                    ) {
                        Text(when {
                            state is MqvpnState.Connecting || connectPending -> "Connecting…"
                            state is MqvpnState.Connected || state is MqvpnState.Reconnecting -> "Disconnect"
                            !configured -> "Set up server"
                            else -> "Connect"
                        })
                    }
                    if (connectError != null) {
                        Text(connectError.orEmpty(), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                    if (isConnected) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Metric("Received", formatBytes(stats.bytesRx), Modifier.weight(1f))
                            Metric("Sent", formatBytes(stats.bytesTx), Modifier.weight(1f))
                            Metric("Latency", "${stats.srttMs} ms", Modifier.weight(1f))
                        }
                    }
                }
            }

            if (state is MqvpnState.Connected || state is MqvpnState.Reconnecting) {
                PathsSection(paths, bandwidthHistory)
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenConnections, modifier = Modifier.weight(1f)) {
                    Text("Connections")
                }
                OutlinedButton(onClick = onOpenIperf, modifier = Modifier.weight(1f)) {
                    Text("Speed test")
                }
            }

            TextButton(onClick = { showDetails = !showDetails }) {
                Text(if (showDetails) "Hide technical details" else "Show technical details")
            }
            if (showDetails) {
                if (state is MqvpnState.Connected) {
                    val tunnel = (state as MqvpnState.Connected).tunnelInfo
                    Text("IP ${tunnel.assignedIp}/${tunnel.prefix} · MTU ${tunnel.mtu}",
                        style = MaterialTheme.typography.bodySmall)
                    if (tunnel.hasV6 && tunnel.assignedIp6 != null) {
                        Text("IPv6 ${tunnel.assignedIp6}/${tunnel.prefix6}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Datagrams: ${stats.dgramSent} sent · ${stats.dgramRecv} received · ${stats.dgramLost} lost",
                        style = MaterialTheme.typography.bodySmall)
                    if (reorderStats.delivered > 0 || reorderStats.gapCount > 0) ReorderStatsCard(reorderStats)
                }
                TextButton(onClick = onOpenServer) { Text("Server and access") }
                TextButton(onClick = onOpenDiagnostics) { Text("Open diagnostics") }
                Text("Recent events", style = MaterialTheme.typography.titleSmall)
                if (events.isEmpty()) Text("No events", style = MaterialTheme.typography.bodySmall)
                else {
                    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.ITALY) }
                    events.take(8).forEach { EventRow(it, timeFormat) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PathsSection(paths: List<PathInfo>, bandwidthHistory: BandwidthHistoryState) {
    if (paths.isEmpty() && bandwidthHistory.samples.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Active paths", style = MaterialTheme.typography.titleMedium)
        BandwidthChart(bandwidthHistory)
        paths.forEach { PathCard(it) }
    }
}

@Composable
private fun EventRow(event: LogEvent, timeFormat: SimpleDateFormat) {
    val (icon, tint) = eventIconAndTint(event.kind)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        Text(timeFormat.format(Date(event.time)), style = MaterialTheme.typography.bodySmall)
        Text(eventText(event.kind), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun eventIconAndTint(kind: LogEvent.Kind): Pair<ImageVector, Color> = when (kind) {
    is LogEvent.Kind.CoreState -> Icons.Filled.Bolt to MaterialTheme.colorScheme.primary
    is LogEvent.Kind.PathAdded -> Icons.Filled.AddCircle to Color(0xFF27866F)
    is LogEvent.Kind.PathRemoved -> Icons.Filled.RemoveCircle to Color.Gray
    is LogEvent.Kind.PathStatus -> Icons.Filled.Autorenew to pathStatusColor(kind.to)
    is LogEvent.Kind.Error -> Icons.Filled.ErrorOutline to MaterialTheme.colorScheme.error
    is LogEvent.Kind.Reconnecting -> Icons.Filled.Refresh to MaterialTheme.colorScheme.tertiary
}

@Composable
private fun ReorderStatsCard(rs: ReorderStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Packet reordering", style = MaterialTheme.typography.titleSmall)
            val fillRate = if (rs.gapCount > 0) "%.1f%%".format(rs.gapFilled * 100.0 / rs.gapCount) else "—"
            Text("Delivered: ${rs.delivered} · Gaps: ${rs.gapCount} · Filled: $fillRate")
            Text("Timeout: ${rs.gapTimeout} · ACK demote: ${rs.ackDemote}",
                style = MaterialTheme.typography.bodySmall)
            Text("Delay: p50 ${rs.bufferedP50Ms} ms · p99 ${rs.bufferedP99Ms} ms",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
