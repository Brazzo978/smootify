// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2026 mp0rta and mqvpn contributors

package com.mqvpn.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mqvpn.app.data.DemoSettings
import com.mqvpn.sdk.core.model.MqvpnState
import com.mqvpn.sdk.network.NetworkPath
import com.mqvpn.sdk.network.PathType

private fun transportEnabled(path: NetworkPath, settings: DemoSettings): Boolean = when (path.type) {
    PathType.CELLULAR -> settings.cellularPathEnabled
    PathType.WIFI -> settings.wifiPathEnabled
    PathType.ETHERNET -> settings.ethernetPathEnabled
    PathType.BLUETOOTH -> settings.bluetoothPathEnabled
    else -> false
}

private fun identityKnown(path: NetworkPath, settings: DemoSettings): Boolean =
    (path.type != PathType.CELLULAR || settings.excludedSubscriptionIds.isEmpty() ||
        path.subscriptionId != null) &&
        (settings.excludedInterfaces.isEmpty() || path.interfaceName != null)

private fun allowed(path: NetworkPath, settings: DemoSettings): Boolean =
    transportEnabled(path, settings) && identityKnown(path, settings) &&
        (path.subscriptionId == null || path.subscriptionId !in settings.excludedSubscriptionIds) &&
        (path.interfaceName == null || path.interfaceName !in settings.excludedInterfaces)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(onNavigateUp: () -> Unit, viewModel: TuningViewModel = hiltViewModel()) {
    val loaded by viewModel.loaded.collectAsStateWithLifecycle()
    val available by viewModel.available.collectAsStateWithLifecycle()
    val activePaths by viewModel.paths.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var seeded by rememberSaveable { mutableStateOf(false) }
    var multipath by rememberSaveable { mutableStateOf(true) }
    var cellular by rememberSaveable { mutableStateOf(true) }
    var wifi by rememberSaveable { mutableStateOf(true) }
    var ethernet by rememberSaveable { mutableStateOf(true) }
    var bluetooth by rememberSaveable { mutableStateOf(false) }
    var excludedSubs by rememberSaveable { mutableStateOf(emptyList<Int>()) }
    var excludedIfaces by rememberSaveable { mutableStateOf(emptyList<String>()) }

    val current = loaded
    if (current != null && !seeded) {
        val source = viewModel.connectionsDraftSnapshot() ?: current
        multipath = source.multipathEnabled
        cellular = source.cellularPathEnabled
        wifi = source.wifiPathEnabled
        ethernet = source.ethernetPathEnabled
        bluetooth = source.bluetoothPathEnabled
        excludedSubs = source.excludedSubscriptionIds
        excludedIfaces = source.excludedInterfaces
        seeded = true
    }
    val draft = (current ?: DemoSettings()).copy(
        multipathEnabled = multipath,
        cellularPathEnabled = cellular,
        wifiPathEnabled = wifi,
        ethernetPathEnabled = ethernet,
        bluetoothPathEnabled = bluetooth,
        excludedSubscriptionIds = excludedSubs,
        excludedInterfaces = excludedIfaces,
    )
    val editable = current != null && !busy
    val connected = vpnState is MqvpnState.Connected
    val dirty = current != null && (
        current.multipathEnabled != multipath ||
        current.cellularPathEnabled != cellular || current.wifiPathEnabled != wifi ||
        current.ethernetPathEnabled != ethernet || current.bluetoothPathEnabled != bluetooth ||
        current.excludedSubscriptionIds != excludedSubs ||
        current.excludedInterfaces != excludedIfaces)
    SideEffect {
        if (seeded && current != null) {
            if (dirty) viewModel.retainConnectionsDraft(draft) else viewModel.clearConnectionsDraft()
        }
    }
    val allowedCount = available.count { allowed(it, draft) }
    val actualCount = if (connected) activePaths.count { it.status == 1 } else 0

    BackHandler(enabled = busy) {}
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Tunnel connections") },
            navigationIcon = {
                IconButton(onClick = onNavigateUp, enabled = !busy) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
        )
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(2.dp))
            Text("Choose networks to use", style = MaterialTheme.typography.headlineSmall)
            Text(
                "You can allow Wi-Fi, mobile, Ethernet, and Bluetooth. Allowed networks are used when available, starting with the next connection.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("$allowedCount visible networks allowed", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (connected) "$actualCount paths active in the tunnel now"
                        else "Tunnel disconnected · no active paths",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (dirty) Text(
                        "Selection changed, not yet applied",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(16.dp)) {
                    LabeledSwitchRow("Use multiple networks together", multipath, { multipath = it }, editable)
                    Text("When off, MQVPN uses one allowed network at a time.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text("Connection types", style = MaterialTheme.typography.titleLarge)
            TransportCard("Wi-Fi", "Available wireless networks", wifi, { wifi = it }, editable,
                MaterialTheme.colorScheme.primary, Icons.Default.Wifi)
            TransportCard("Mobile network", "SIM and 2G / 3G / 4G / 5G data", cellular, { cellular = it }, editable,
                MaterialTheme.colorScheme.tertiary, Icons.Default.SignalCellularAlt)
            TransportCard("Ethernet", "Cable or USB adapter", ethernet, { ethernet = it }, editable,
                MaterialTheme.colorScheme.secondary, Icons.Default.Cable)
            TransportCard("Bluetooth", "Bluetooth tethering", bluetooth, { bluetooth = it }, editable,
                MaterialTheme.colorScheme.primary, Icons.Default.Bluetooth)

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Detected networks", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = viewModel::refreshNetworks, enabled = !busy) { Text("Refresh") }
            }
            Text(
                "Choose individual SIMs and interfaces here. ‘Allowed’ is a preference; ‘active’ means the path is actually in use.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (available.isEmpty()) {
                Card(shape = RoundedCornerShape(24.dp)) { Text("No eligible network is visible. Connect a network and tap Refresh.",
                    modifier = Modifier.fillMaxWidth().padding(16.dp)) }
            }
            available.forEach { path ->
                val isAllowed = allowed(path, draft)
                val canIdentify = identityKnown(path, draft)
                val actual = if (connected) activePaths.firstOrNull { it.iface == path.name } else null
                val observed = when (actual?.status) {
                    1 -> "Active in tunnel"
                    0 -> "Path pending"
                    2 -> "Path degraded"
                    3 -> "Path on standby"
                    4 -> "Path closed"
                    else -> "Not active in tunnel"
                }
                Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(path.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (isAllowed) "Allowed by selection" else "Excluded by selection",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isAllowed) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = isAllowed,
                                onCheckedChange = { on ->
                                    path.subscriptionId?.let { id ->
                                        excludedSubs = if (on) excludedSubs - id else (excludedSubs + id).distinct()
                                    }
                                    path.interfaceName?.let { iface ->
                                        if (on || path.subscriptionId == null) {
                                            excludedIfaces = if (on) excludedIfaces - iface
                                                             else (excludedIfaces + iface).distinct()
                                        }
                                    }
                                },
                                enabled = editable && transportEnabled(path, draft) &&
                                    canIdentify && (path.subscriptionId != null || path.interfaceName != null),
                            )
                        }
                        Text(observed, style = MaterialTheme.typography.bodySmall,
                            color = if (actual?.status == 1) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!canIdentify) Text(
                            "Identity unavailable: excluded to honor SIM and interface preferences.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { viewModel.saveConnections(draft, false) },
                    enabled = editable && dirty,
                    modifier = Modifier.weight(1f),
                ) { Text("Save for later") }
                Button(
                    onClick = { viewModel.saveConnections(draft, true) },
                    enabled = editable && dirty && connected,
                    modifier = Modifier.weight(1f),
                ) { Text("Apply and reconnect") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(message, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TransportCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    tint: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (checked) tint.copy(alpha = 0.12f)
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(icon, contentDescription = null, tint = tint)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(if (checked) "Included" else "Excluded", style = MaterialTheme.typography.labelMedium,
                    color = if (checked) tint else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}
